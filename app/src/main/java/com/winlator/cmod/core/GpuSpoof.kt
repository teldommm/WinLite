package com.winlator.cmod.core

/**
 * GPU identity the Vulkan wrapper reports instead of the real device (the WRAPPER_* spoof
 * variables of both wrappers, mesa26 and legacy). Every field is optional: an empty field keeps
 * the driver's own value.
 *
 * Stored in the container / shortcut graphicsDriverConfig ("key=value;key=value"), one key per
 * field, only for the fields that are set.
 *
 *   field          config key           env var                   example
 *   vendorId       spoofVendorId        WRAPPER_VENDOR_ID         0x10de
 *   deviceId       spoofDeviceId        WRAPPER_DEVICE_ID         0x2684
 *   deviceName     spoofDeviceName      WRAPPER_DEVICE_NAME       NVIDIA GeForce RTX 4090
 *   driverId       spoofDriverId        WRAPPER_DRIVER_ID         4
 *   driverName     spoofDriverName      WRAPPER_DRIVER_NAME       NVIDIA
 *   driverInfo     spoofDriverInfo      WRAPPER_DRIVER_INFO       560.94
 *   driverVersion  spoofDriverVersion   WRAPPER_DRIVER_VERSION    560.94 or 0x8c178000
 *
 * Vendor ID and Device ID also become WineD3D's VideoPciVendorID / VideoPciDeviceID when set
 * (16-bit values only, and Wine only uses pairs it knows), see contentdialog/WineD3DConfig.
 *
 * IDs are decimal or 0x hexadecimal (the wrappers parse both, a leading 0 is not octal). The
 * driver version is a raw number or a dotted version, which the wrapper encodes for the
 * reported vendor.
 */
data class GpuSpoof(
    val vendorId: String = "",
    val deviceId: String = "",
    val deviceName: String = "",
    val driverId: String = "",
    val driverName: String = "",
    val driverInfo: String = "",
    val driverVersion: String = ""
) {
    private fun fields(): List<String> =
        listOf(vendorId, deviceId, deviceName, driverId, driverName, driverInfo, driverVersion)

    val isEmpty: Boolean get() = fields().all { it.isBlank() }

    val overriddenCount: Int get() = fields().count { it.isNotBlank() }

    /** Every field passes [isValidId] / [isValidDriverVersion]. */
    val isValid: Boolean
        get() = isValidId(vendorId) && isValidId(deviceId) && isValidId(driverId) &&
            isValidDriverVersion(driverVersion)

    /** Trimmed, with the characters the config format can't hold removed. */
    fun normalized(): GpuSpoof = GpuSpoof(
        vendorId = vendorId.trim(),
        deviceId = deviceId.trim(),
        deviceName = sanitizeText(deviceName).trim(),
        driverId = driverId.trim(),
        driverName = sanitizeText(driverName).trim(),
        driverInfo = sanitizeText(driverInfo).trim(),
        driverVersion = driverVersion.trim()
    )

    /** [config] with the spoof keys replaced by this spoof (unset fields are left out). */
    fun writeTo(config: String?): String {
        val keys = KEYS.toSet()
        val out = config.orEmpty().split(';')
            .map(String::trim)
            .filter { token -> token.isNotEmpty() && token.substringBefore('=').trim() !in keys }
            .toMutableList()
        val n = normalized()
        listOf(
            KEY_VENDOR_ID to n.vendorId,
            KEY_DEVICE_ID to n.deviceId,
            KEY_DEVICE_NAME to n.deviceName,
            KEY_DRIVER_ID to n.driverId,
            KEY_DRIVER_NAME to n.driverName,
            KEY_DRIVER_INFO to n.driverInfo,
            KEY_DRIVER_VERSION to n.driverVersion
        ).forEach { (key, value) -> if (value.isNotEmpty()) out += "$key=$value" }
        return out.joinToString(";")
    }

    /** Puts the WRAPPER_* variables of the fields that are set and valid. */
    fun applyEnv(envVars: EnvVars) {
        val n = normalized()
        if (n.vendorId.isNotEmpty() && isValidId(n.vendorId)) envVars.put("WRAPPER_VENDOR_ID", n.vendorId)
        if (n.deviceId.isNotEmpty() && isValidId(n.deviceId)) envVars.put("WRAPPER_DEVICE_ID", n.deviceId)
        if (n.deviceName.isNotEmpty()) envVars.put("WRAPPER_DEVICE_NAME", n.deviceName)
        if (n.driverId.isNotEmpty() && isValidId(n.driverId)) envVars.put("WRAPPER_DRIVER_ID", n.driverId)
        if (n.driverName.isNotEmpty()) envVars.put("WRAPPER_DRIVER_NAME", n.driverName)
        if (n.driverInfo.isNotEmpty()) envVars.put("WRAPPER_DRIVER_INFO", n.driverInfo)
        if (n.driverVersion.isNotEmpty() && isValidDriverVersion(n.driverVersion))
            envVars.put("WRAPPER_DRIVER_VERSION", n.driverVersion)
    }

    companion object {
        const val KEY_VENDOR_ID = "spoofVendorId"
        const val KEY_DEVICE_ID = "spoofDeviceId"
        const val KEY_DEVICE_NAME = "spoofDeviceName"
        const val KEY_DRIVER_ID = "spoofDriverId"
        const val KEY_DRIVER_NAME = "spoofDriverName"
        const val KEY_DRIVER_INFO = "spoofDriverInfo"
        const val KEY_DRIVER_VERSION = "spoofDriverVersion"

        @JvmField
        val KEYS = listOf(
            KEY_VENDOR_ID, KEY_DEVICE_ID, KEY_DEVICE_NAME,
            KEY_DRIVER_ID, KEY_DRIVER_NAME, KEY_DRIVER_INFO, KEY_DRIVER_VERSION
        )

        /** VK_MAX_PHYSICAL_DEVICE_NAME_SIZE / VK_MAX_DRIVER_NAME_SIZE / VK_MAX_DRIVER_INFO_SIZE
         *  minus the terminator. */
        const val MAX_TEXT_LENGTH = 255

        private val ID_REGEX = Regex("^(0[xX][0-9a-fA-F]{1,8}|[0-9]{1,10})$")
        private val DOTTED_VERSION_REGEX = Regex("^[0-9]{1,4}(\\.[0-9]{1,5}){1,3}$")

        /** Empty, or a decimal / 0x hexadecimal number that fits in 32 bits. */
        @JvmStatic
        fun isValidId(value: String): Boolean {
            val v = value.trim()
            if (v.isEmpty()) return true
            if (!ID_REGEX.matches(v)) return false
            val number = if (v.startsWith("0x", true)) v.substring(2).toLongOrNull(16) else v.toLongOrNull()
            return number != null && number <= 0xFFFFFFFFL
        }

        /** The value of a decimal / 0x hexadecimal ID, or -1 when empty or malformed. */
        @JvmStatic
        fun parseId(value: String?): Long {
            val v = value.orEmpty().trim()
            if (v.isEmpty() || !isValidId(v)) return -1
            return if (v.startsWith("0x", true)) v.substring(2).toLong(16) else v.toLong()
        }

        /** Empty, a raw 32-bit number, or a dotted version (2 to 4 parts). */
        @JvmStatic
        fun isValidDriverVersion(value: String): Boolean {
            val v = value.trim()
            return v.isEmpty() || isValidId(v) || DOTTED_VERSION_REGEX.matches(v)
        }

        /** Drops the characters graphicsDriverConfig can't hold (';' separates entries, the
         *  parser splits on '=') and line breaks; caps the length. */
        @JvmStatic
        fun sanitizeText(value: String): String =
            value.filterNot { it == ';' || it == '=' || it == '\n' || it == '\r' }.take(MAX_TEXT_LENGTH)

        /** Keeps only what an ID can contain (digits, hex digits, the x of 0x). */
        @JvmStatic
        fun sanitizeId(value: String): String =
            value.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == 'x' || it == 'X' }.take(10)

        /** Digits, dots and hex characters, for a raw or dotted driver version. */
        @JvmStatic
        fun sanitizeVersion(value: String): String =
            value.filter { it.isDigit() || it == '.' || it in 'a'..'f' || it in 'A'..'F' || it == 'x' || it == 'X' }.take(24)

        @JvmStatic
        fun fromMap(config: Map<String, String?>?): GpuSpoof {
            if (config == null) return GpuSpoof()
            fun get(key: String) = config[key].orEmpty().trim()
            return GpuSpoof(
                vendorId = get(KEY_VENDOR_ID),
                deviceId = get(KEY_DEVICE_ID),
                deviceName = get(KEY_DEVICE_NAME),
                driverId = get(KEY_DRIVER_ID),
                driverName = get(KEY_DRIVER_NAME),
                driverInfo = get(KEY_DRIVER_INFO),
                driverVersion = get(KEY_DRIVER_VERSION)
            )
        }

        /** Reads the spoof keys of a "key=value;key=value" graphicsDriverConfig. */
        @JvmStatic
        fun fromConfig(config: String?): GpuSpoof {
            val map = HashMap<String, String>()
            config.orEmpty().split(';').forEach { token ->
                val index = token.indexOf('=')
                if (index > 0) map[token.substring(0, index).trim()] = token.substring(index + 1)
            }
            return fromMap(map)
        }

        /** Launch path: the WRAPPER_* variables for the parsed graphicsDriverConfig. */
        @JvmStatic
        fun applyEnv(config: Map<String, String?>?, envVars: EnvVars) {
            fromMap(config).applyEnv(envVars)
        }
    }
}
