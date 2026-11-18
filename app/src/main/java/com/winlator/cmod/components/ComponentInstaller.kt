package com.winlator.cmod.components

import android.content.Context
import android.util.Log
import com.winlator.cmod.components.offline.OfflineTools
import com.winlator.cmod.components.offline.RegValue
import com.winlator.cmod.components.offline.WineRegistryFile
import com.winlator.cmod.container.Container
import com.winlator.cmod.core.ContainerSessionGate
import java.io.File

/**
 * Installs and removes the local Windows components in a container's prefix. Installation copies
 * the component's files into drive_c/windows/{system32,syswow64}, records DLL overrides in
 * user.reg, and journals everything so removal restores the prefix exactly.
 */
object ComponentInstaller {
    private const val TAG = "ComponentInstaller"
    private const val PREFS = "winlite_components"
    private const val DLL_OVERRIDES = "Software\\Wine\\DllOverrides"
    private const val TYPE_NATIVE = "native,builtin"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun prefKey(container: Container) = "c${container.id}"

    /** Installed component ids: the journals are the source of truth; prefs cache them per container. */
    fun installedIds(context: Context, container: Container): Set<String> {
        val journals = ComponentUndo.installedNames(container.rootDir).toSet()
        if (journals.isNotEmpty()) return journals
        return prefs(context).getStringSet(prefKey(container), emptySet()).orEmpty()
    }

    /**
     * Installs [component] into [container]'s prefix. [onProgress] gets 0..1, [onStage] a line for
     * what is happening. Returns null on success, else why it failed.
     */
    fun install(
        context: Context, container: Container, component: LocalComponent,
        onProgress: (Float) -> Unit = {}, onStage: (String) -> Unit = {},
    ): String? {
        val wine = File(container.rootDir, ".wine")
        if (!wine.isDirectory) return "noprefix"
        if (!ContainerSessionGate.edit(container.id)) return "session"
        try {
            if (!LocalComponents.ensureReady(context)) return "bundle"
            val src = LocalComponents.componentDir(context, component.id) ?: return "missing:${component.id}"

            onStage("files")
            val journal = ComponentUndo.open(container.rootDir, component.id)
            try {
                // The bundle layout mirrors the target: system32/... -> drive_c/windows/system32/...
                val windows = File(wine, "drive_c/windows")
                val files = OfflineTools.walkFiles(src)
                var done = 0
                val base = src.path + File.separator
                for (file in files) {
                    val rel = file.path.substring(base.length).replace(File.separatorChar, '/')
                    journal.placeSingle(file, windows, rel)
                    done++
                    onProgress(0.7f * done / files.size.coerceAtLeast(1))
                }

                // Inside the journal, so removing the component restores the overrides too.
                onStage("registry")
                if (component.overrides.isNotEmpty()) {
                    val user = WineRegistryFile(File(wine, "user.reg"))
                    for (name in component.overrides) user.apply(RegValue("HKCU", DLL_OVERRIDES, name, "sz", TYPE_NATIVE))
                    user.save()
                }
            } finally {
                journal.close()
            }
            onProgress(1f)
            prefs(context).edit().putStringSet(prefKey(container), installedIds(context, container) + component.id).apply()
            Log.i(TAG, "${component.id}: installed")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "install ${component.id}", e)
            return e.message ?: e.javaClass.simpleName
        } finally {
            ContainerSessionGate.edited(container.id)
        }
    }

    /**
     * Removes [componentId] from [container]'s prefix, restoring the files and registry the
     * journal recorded. Returns null on success, else why it failed.
     */
    fun remove(
        context: Context, container: Container, componentId: String,
        onProgress: (Float) -> Unit = {},
    ): String? {
        val wine = File(container.rootDir, ".wine")
        if (!wine.isDirectory) return "noprefix"
        if (!ContainerSessionGate.edit(container.id)) return "session"
        try {
            if (ComponentUndo.journalsFor(container.rootDir, componentId).isEmpty()) return "nojournal"
            ComponentUndo.revert(container.rootDir, componentId, onProgress)
            prefs(context).edit().putStringSet(prefKey(container), installedIds(context, container) - componentId).apply()
            Log.i(TAG, "$componentId: removed")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "remove $componentId", e)
            return e.message ?: e.javaClass.simpleName
        } finally {
            ContainerSessionGate.edited(container.id)
        }
    }
}
