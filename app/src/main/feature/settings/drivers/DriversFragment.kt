package com.winlator.cmod.feature.settings
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.winlator.cmod.R
import com.winlator.cmod.app.shell.UnifiedActivity
import com.winlator.cmod.feature.setup.SetupWizardActivity
import com.winlator.cmod.runtime.content.AdrenotoolsManager
import com.winlator.cmod.runtime.content.Downloader
import com.winlator.cmod.shared.ui.toast.WinToast
import com.winlator.cmod.shared.android.DirectoryPickerDialog
import com.winlator.cmod.shared.theme.WinLiteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class DriversFragment : Fragment() {
    private lateinit var adrenotoolsManager: AdrenotoolsManager
    private lateinit var preferences: SharedPreferences

    private var driversState by mutableStateOf(DriversState())

    private val releasesBySource = linkedMapOf<String, List<DriverReleaseItem>>()
    private var sources = mutableListOf<DriverRepo>()
    private var installedDrivers: List<InstalledDriverItem> = emptyList()
    private var installedAssetNames: Set<String> = emptySet()
    private var expandedSourceApiUrl: String? = null
    private var expandedReleaseId: Long? = null
    private var loadingSourceApiUrl: String? = null
    private var downloadProgress: DownloadProgress? = null
    private var isRefreshing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adrenotoolsManager = AdrenotoolsManager(requireContext())
        preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.setTitle(R.string.settings_drivers_manager)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        loadRepos()
        refreshInstalledDrivers()
        publishState()

        return ComposeView(ctx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinLiteTheme(
                    colorScheme =
                        darkColorScheme(
                            primary = Color(0xFF1A9FFF),
                            background = Color(0xFF141B24),
                            surface = Color(0xFF1E252E),
                        ),
                ) {
                    DriversScreen(
                        state = driversState,
                        onInstallFromFile = { promptInstallDriverFromFile() },
                        onSourceTapped = { source -> onSourceSelected(source) },
                        onReleaseTapped = { release ->
                            expandedReleaseId = if (expandedReleaseId == release.id) null else release.id
                            publishState()
                        },
                        onDownloadAsset = { asset -> downloadReleaseAsset(asset) },
                        onRemoveDriver = { driver ->
                            adrenotoolsManager.removeDriver(driver.id)
                            refreshInstalledDrivers()
                            publishState()
                        },
                        onRepoAdded = { name, apiUrl ->
                            val normalized = normalizeRepoInput(name, apiUrl)
                            sources.add(normalized)
                            saveRepos()
                            refreshAllSources()
                        },
                        onRepoUpdated = { index, name, apiUrl ->
                            if (index in sources.indices) {
                                val normalized = normalizeRepoInput(name, apiUrl)
                                sources[index] = normalized
                                releasesBySource.remove(sources[index].apiUrl)
                                saveRepos()
                                refreshAllSources()
                            }
                        },
                        onRepoDeleted = { index ->
                            if (index in sources.indices) {
                                val removed = sources.removeAt(index)
                                releasesBySource.remove(removed.apiUrl)
                                if (expandedSourceApiUrl == removed.apiUrl) expandedSourceApiUrl = null
                                if (loadingSourceApiUrl == removed.apiUrl) loadingSourceApiUrl = null
                                saveRepos()
                                publishState()
                            }
                        },
                        onRestoreDefaultRepos = { restoreDefaultRepos() },
                        bridge = (requireActivity() as? UnifiedActivity)?.settingsNavBridge,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Eagerly re-fetch every source's releases on each visit (mirrors Components),
        // instead of leaving them to load lazily one card at a time on tap.
        refreshAllSources()
    }

    private fun promptInstallDriverFromFile() {
        val activity = activity ?: return
        DirectoryPickerDialog.showFile(
            activity = activity,
            title = getString(R.string.settings_drivers_install),
            allowedExtensions = setOf("zip"),
        ) { path ->
            installDriverPackage(Uri.fromFile(File(path)))
        }
    }

    private fun publishState() {
        val existingApiUrls = sources.map { it.apiUrl }.toHashSet()
        val hasMissingDefaults = defaultRepoList().any { it.apiUrl !in existingApiUrls }
        driversState =
            DriversState(
                installedDrivers = installedDrivers,
                sources = sources.toList(),
                releasesBySource = releasesBySource.toMap(),
                expandedSourceApiUrl = expandedSourceApiUrl,
                expandedReleaseId = expandedReleaseId,
                loadingSourceApiUrl = loadingSourceApiUrl,
                hasMissingDefaults = hasMissingDefaults,
                installedAssetNames = installedAssetNames,
                downloadProgress = downloadProgress,
            )
    }

    // Re-fetches releases for every configured source in parallel (forcing a fresh fetch even
    // for sources already cached), plus refreshes the installed list. This is what gives the
    // "Available" count something accurate to show without needing every card expanded first.
    private fun refreshAllSources() {
        if (isRefreshing) return
        isRefreshing = true
        refreshInstalledDrivers()
        publishState()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val fetched =
                    withContext(Dispatchers.IO) {
                        sources
                            .map { source ->
                                async {
                                    source.apiUrl to runCatching { fetchGithubReleases(source) }.getOrElse { emptyList() }
                                }
                            }.awaitAll()
                            .toMap()
                    }
                if (fetched.isNotEmpty()) {
                    releasesBySource.clear()
                    releasesBySource.putAll(fetched)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh driver sources.", e)
            } finally {
                isRefreshing = false
            }

            if (isAdded && view != null) publishState()
        }
    }

    private fun refreshInstalledDrivers() {
        val driverIds = adrenotoolsManager.enumarateInstalledDrivers()
        installedDrivers =
            driverIds
                .map { driverId ->
                    InstalledDriverItem(
                        id = driverId,
                        name = adrenotoolsManager.getDriverName(driverId).ifBlank { driverId },
                        version = adrenotoolsManager.getDriverVersion(driverId),
                    )
                }.sortedBy { it.name.lowercase(Locale.getDefault()) }
        installedAssetNames =
            driverIds
                .mapNotNull { id -> adrenotoolsManager.getSourceAsset(id).takeIf { it.isNotBlank() } }
                .toSet()
    }

    private fun defaultRepoList(): List<DriverRepo> =
        listOf(
            DriverRepo(
                name = WINLITE_DRIVERS_REPO_NAME,
                repoUrl = WINLITE_COMPONENTS_REPO_URL,
                apiUrl = WINLITE_COMPONENTS_API_URL,
            ),
        )

    private fun loadRepos() {
        val jsonStr = preferences.getString("custom_driver_repos", null)
        val newSources = mutableListOf<DriverRepo>()

        if (jsonStr == null) {
            newSources.addAll(defaultRepoList())
        } else {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    newSources.add(
                        DriverRepo(
                            name = obj.optString("name", "Unknown Repo"),
                            repoUrl = obj.optString("repoUrl", ""),
                            apiUrl = obj.optString("apiUrl", ""),
                        ),
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        sources = newSources
    }

    private fun restoreDefaultRepos() {
        val existingApiUrls = sources.map { it.apiUrl }.toHashSet()
        var added = 0
        defaultRepoList().forEach { default ->
            if (default.apiUrl !in existingApiUrls) {
                sources.add(default)
                added += 1
            }
        }
        if (added > 0) {
            saveRepos()
            refreshAllSources()
        } else {
            WinToast.show(requireContext(), getString(R.string.common_ui_defaults_already_present))
        }
    }

    private fun saveRepos() {
        try {
            val array = JSONArray()
            sources.forEach { source ->
                val obj = JSONObject()
                obj.put("name", source.name)
                obj.put("repoUrl", source.repoUrl)
                obj.put("apiUrl", source.apiUrl)
                array.put(obj)
            }
            preferences
                .edit()
                .putString("custom_driver_repos", array.toString())
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Accepts "owner/repo", "owner/repo/branch" (branch ignored — the Releases API is branch-
    // agnostic), any github.com link (repo root, /releases, /releases/tag/vX, /tree/branch), or
    // an already-correct api.github.com releases URL. Anything else (e.g. a self-hosted mirror)
    // passes through untouched.
    private fun normalizeGithubReleasesApiUrl(raw: String): String {
        val input = raw.trim()
        if (input.isEmpty()) return input

        if (input.contains("api.github.com/repos/")) {
            val match = Regex("api\\.github\\.com/repos/([^/]+)/([^/]+)").find(input)
            return if (match != null) {
                val (owner, repo) = match.destructured
                "https://api.github.com/repos/$owner/$repo/releases"
            } else {
                input
            }
        }

        if (input.contains("github.com/")) {
            val afterHost =
                input
                    .replaceFirst(Regex("^https?://", RegexOption.IGNORE_CASE), "")
                    .removePrefix("github.com/")
                    .trim('/')
            val parts = afterHost.split("/")
            return if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                "https://api.github.com/repos/${parts[0]}/${parts[1]}/releases"
            } else {
                input
            }
        }

        // Bare "owner/repo" shorthand — owner names never contain a dot, which is what tells
        // this apart from a plain hostname-and-path paste for some other service.
        val parts = input.trim('/').split("/")
        if (!input.contains("://") && parts.size >= 2 && !parts[0].contains(".") &&
            parts[0].isNotBlank() && parts[1].isNotBlank()
        ) {
            return "https://api.github.com/repos/${parts[0]}/${parts[1]}/releases"
        }

        return input
    }

    private fun normalizeRepoInput(
        name: String,
        rawUrl: String,
    ): DriverRepo {
        val url = normalizeGithubReleasesApiUrl(rawUrl)
        val repoUrl = url.replace("api.github.com/repos", "github.com")
        return DriverRepo(name = name, repoUrl = repoUrl, apiUrl = url)
    }

    private fun onSourceSelected(source: DriverRepo) {
        if (loadingSourceApiUrl == source.apiUrl) return

        if (expandedSourceApiUrl == source.apiUrl) {
            expandedSourceApiUrl = null
            expandedReleaseId = null
            publishState()
            return
        }

        expandedSourceApiUrl = source.apiUrl
        expandedReleaseId = null

        if (releasesBySource.containsKey(source.apiUrl)) {
            publishState()
            return
        }

        loadingSourceApiUrl = source.apiUrl
        publishState()

        viewLifecycleOwner.lifecycleScope.launch {
            val releases =
                withContext(Dispatchers.IO) {
                    runCatching { fetchGithubReleases(source) }.getOrElse { emptyList() }
                }

            if (!isAdded || view == null) return@launch

            if (loadingSourceApiUrl == source.apiUrl) {
                loadingSourceApiUrl = null
            }
            releasesBySource[source.apiUrl] = releases
            publishState()

            if (releases.isEmpty()) {
                WinToast.show(requireContext(), R.string.settings_drivers_repo_fetch_failed)
            }
        }
    }

    private fun fetchGithubReleases(source: DriverRepo): List<DriverReleaseItem> {
        val releases = Downloader.fetchGithubReleases(source.apiUrl)
        return buildList {
            for (index in 0 until releases.length()) {
                val releaseObject = releases.optJSONObject(index) ?: continue
                val assets = releaseObject.optJSONArray("assets").toZipAssets()
                if (assets.isEmpty()) continue

                val tagName = releaseObject.optString("tag_name")
                val releaseName = releaseObject.optString("name").ifBlank { tagName }
                val publishedAt = releaseObject.optString("published_at")
                val releaseNotes = releaseObject.optString("body").toReleaseNotes()

                add(
                    DriverReleaseItem(
                        id = releaseObject.optLong("id"),
                        title = releaseName.ifBlank { getString(R.string.common_ui_unnamed) },
                        subtitle = buildReleaseSubtitle(tagName, publishedAt, assets.size),
                        notes = releaseNotes,
                        assets = assets,
                    ),
                )
            }
        }
    }

    private fun JSONArray?.toZipAssets(): List<DriverAssetItem> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val assetObject = optJSONObject(index) ?: continue
                val assetName = assetObject.optString("name").trim()
                val downloadUrl = assetObject.optString("browser_download_url")
                if (!assetName.lowercase(Locale.ROOT).endsWith(".zip") || downloadUrl.isBlank()) continue

                add(
                    DriverAssetItem(
                        id = assetObject.optLong("id"),
                        name = assetName,
                        downloadUrl = downloadUrl,
                        sizeLabel = formatBytes(assetObject.optLong("size")),
                    ),
                )
            }
        }
    }

    private fun buildReleaseSubtitle(
        tagName: String,
        publishedAt: String,
        assetCount: Int,
    ): String {
        val parts = mutableListOf<String>()
        if (tagName.isNotBlank()) parts += tagName
        if (publishedAt.isNotBlank()) {
            runCatching {
                val formattedDate =
                    OffsetDateTime.parse(publishedAt).format(
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM),
                    )
                parts += formattedDate
            }
        }
        parts += resources.getQuantityString(R.plurals.github_repo_asset_count, assetCount, assetCount)
        return parts.joinToString("  •  ")
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return getString(R.string.settings_drivers_repo_unknown_size)
        val units = listOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        return if (unitIndex == 0) {
            "${value.toInt()} ${units[unitIndex]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIndex])
        }
    }

    private fun String.toReleaseNotes(): String =
        lineSequence()
            .map { line -> line.trim() }
            .dropWhile { it.isBlank() }
            .map { line ->
                line
                    .removePrefix("#")
                    .removePrefix("##")
                    .removePrefix("###")
                    .removePrefix("- [ ] ")
                    .removePrefix("- [x] ")
                    .removePrefix("- ")
                    .removePrefix("* ")
                    .trim()
            }.fold(mutableListOf<String>()) { acc, line ->
                if (line.isBlank()) {
                    if (acc.isNotEmpty() && acc.last().isNotBlank()) acc += ""
                } else {
                    acc += line
                }
                acc
            }.joinToString("\n")
            .trim()

    private fun downloadReleaseAsset(asset: DriverAssetItem) {
        if (asset.name in installedAssetNames) return
        if (downloadProgress != null) return

        val downloadTitle = getString(R.string.settings_content_downloading_title)
        downloadProgress =
            DownloadProgress(
                title = downloadTitle,
                assetName = asset.name,
                progress = 0f,
                indeterminate = true,
            )
        publishState()

        // Hold the app process alive across this download so screen lock can't
        // interrupt it. installDriverPackage owns its own keep-alive scope.
        val keepAliveTag = "drivers_download_${asset.downloadUrl}"
        val appCtx = requireContext().applicationContext
        com.winlator.cmod.runtime.system.SessionKeepAliveService.startDownload(appCtx, keepAliveTag)
        viewLifecycleOwner.lifecycleScope.launch {
            val output = File(requireContext().cacheDir, "driver_${System.currentTimeMillis()}.zip")
            val success =
                withContext(Dispatchers.IO) {
                    Downloader.downloadFile(asset.downloadUrl, output) { downloadedBytes, totalBytes ->
                        if (totalBytes <= 0L) {
                            updateDownloadProgress(
                                DownloadProgress(
                                    title = downloadTitle,
                                    assetName = asset.name,
                                    indeterminate = true,
                                ),
                            )
                            return@downloadFile
                        }
                        val fraction =
                            (downloadedBytes.toFloat() / totalBytes.toFloat())
                                .coerceIn(0f, 1f)
                        updateDownloadProgress(
                            DownloadProgress(
                                title = downloadTitle,
                                assetName = asset.name,
                                progress = fraction,
                                indeterminate = false,
                            ),
                        )
                    }
                }

            try {
                if (!isAdded || view == null) {
                    output.delete()
                    clearDownloadProgress()
                    return@launch
                }

                if (!success) {
                    output.delete()
                    clearDownloadProgress()
                    WinToast.show(requireContext(), R.string.settings_drivers_repo_download_failed)
                    return@launch
                }

                installDriverPackage(
                    uri = Uri.fromFile(output),
                    sourceAssetName = asset.name,
                    onComplete = { output.delete() },
                )
            } finally {
                com.winlator.cmod.runtime.system.SessionKeepAliveService.stopDownload(appCtx, keepAliveTag)
            }
        }
    }

    private fun installDriverPackage(
        uri: Uri,
        sourceAssetName: String? = null,
        onComplete: (() -> Unit)? = null,
    ) {
        val installTitle = getString(R.string.settings_drivers_install)
        val installMessage = getString(R.string.settings_content_preparing_package)
        updateDownloadProgress(
            DownloadProgress(
                title = installTitle,
                assetName = sourceAssetName ?: installMessage,
                indeterminate = true,
            ),
        )

        // Keep the process alive across the install (driver extraction +
        // move). Released after the lifecycleScope coroutine finishes.
        val installKeepAliveTag = "drivers_install_${sourceAssetName ?: uri}"
        val appCtx = requireContext().applicationContext
        com.winlator.cmod.runtime.system.SessionKeepAliveService.startDownload(appCtx, installKeepAliveTag)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val installedDriverId =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            adrenotoolsManager.installDriver(uri, sourceAssetName)
                        }.getOrDefault("")
                    }

                if (!isAdded || view == null) {
                    clearDownloadProgress()
                    onComplete?.invoke()
                    return@launch
                }

                clearDownloadProgress()
                onComplete?.invoke()

                if (installedDriverId.isBlank()) {
                    WinToast.show(requireContext(), R.string.settings_drivers_install_failed)
                    return@launch
                }

                SetupWizardActivity.recordInstalledDriver(requireContext(), installedDriverId)
                refreshInstalledDrivers()
                publishState()
            } finally {
                com.winlator.cmod.runtime.system.SessionKeepAliveService.stopDownload(appCtx, installKeepAliveTag)
            }
        }
    }

    private fun updateDownloadProgress(progress: DownloadProgress) {
        downloadProgress = progress
        publishState()
    }

    private fun clearDownloadProgress() {
        downloadProgress = null
        publishState()
    }

    companion object {
        private const val TAG = "DriversFragment"
        private const val WINLITE_DRIVERS_REPO_NAME = "WinLite Drivers"
        private const val WINLITE_COMPONENTS_REPO_URL = "https://github.com/teldommm/WinLite-Components/releases"
        private const val WINLITE_COMPONENTS_API_URL = "https://api.github.com/repos/teldommm/WinLite-Components/releases"
    }
}
