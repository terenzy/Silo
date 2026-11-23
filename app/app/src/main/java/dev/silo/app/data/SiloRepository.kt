package dev.silo.app.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.silo.app.ipc.*
import kotlinx.serialization.json.*

data class BlocklistParseResult(
    val entries: List<String>,
    val truncatedByByteCap: Boolean = false,
    val truncatedByEntryCap: Boolean = false,
    val truncatedByStreamCap: Boolean = false,
)

object BlocklistParser {
    const val MAX_ENTRIES: Int = 50_000

    /** Daemon MAX_IPC_LINE_BYTES = 4 MiB; keep serialized JSON safely under 3.5 MiB (APP-H20). */
    const val MAX_SERIALIZED_JSON_BYTES: Int = 3_500_000

    /** 8 MiB SAF streaming read cap to prevent OOM on large arbitrary files (APP-M05). */
    const val MAX_STREAM_READ_BYTES: Long = 8L * 1024L * 1024L

    private const val MAX_LINE_CHARS: Int = 65_536
    private val tokenSplitRegex = Regex("""[\s,;]+""")

    /**
     * Strictly validates a single IPv4/IPv6 or CIDR token matching `daemon/src/config.rs:validate_cidr`.
     * Pure numeric parser — NEVER calls `InetAddress.getByName` (zero DNS lookups).
     * Returns normalized token if valid & safe, or `null` if invalid/unsafe.
     */
    fun validateEntry(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty() || s.length > 64) return null
        val slashIdx = s.indexOf('/')
        if (slashIdx != -1 && s.indexOf('/', slashIdx + 1) != -1) return null

        val ipPart = if (slashIdx == -1) s else s.substring(0, slashIdx)
        val prefixStr = if (slashIdx == -1) null else s.substring(slashIdx + 1)

        return if (ipPart.contains(':')) {
            if (isValidSafeIpv6(ipPart, prefixStr)) s.lowercase(java.util.Locale.ROOT) else null
        } else {
            if (isValidSafeIpv4(ipPart, prefixStr)) s else null
        }
    }

    private fun parsePrefix(prefixStr: String?, maxPrefix: Int): Int? {
        if (prefixStr == null) return maxPrefix
        if (prefixStr.isEmpty() || prefixStr.length > 3) return null
        if (prefixStr.length > 1 && prefixStr[0] == '0') return null
        var value = 0
        for (ch in prefixStr) {
            if (ch !in '0'..'9') return null
            value = value * 10 + (ch - '0')
        }
        return if (value in 1..maxPrefix) value else null
    }

    private fun parseIpv4Octets(ip: String): IntArray? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3) return null
            if (part.length > 1 && part[0] == '0') return null
            var v = 0
            for (ch in part) {
                if (ch !in '0'..'9') return null
                v = v * 10 + (ch - '0')
            }
            if (v !in 0..255) return null
            octets[i] = v
        }
        return octets
    }

    private fun isValidSafeIpv4(ipPart: String, prefixStr: String?): Boolean {
        val octets = parseIpv4Octets(ipPart) ?: return false
        val prefix = parsePrefix(prefixStr, 32) ?: return false

        val ipBits = (octets[0].toLong() shl 24) or
            (octets[1].toLong() shl 16) or
            (octets[2].toLong() shl 8) or
            octets[3].toLong()
        // Reject loopback (127.0.0.0/8) and unspecified (0.0.0.0)
        if (octets[0] == 127 || ipBits == 0L) return false

        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val masked = ipBits and mask
        val m0 = ((masked ushr 24) and 0xFFL).toInt()
        if (m0 == 127 || (prefix <= 8 && m0 == 0)) return false
        return true
    }

    private fun parseHexGroup(group: String): Int? {
        if (group.isEmpty() || group.length > 4) return null
        var v = 0
        for (ch in group) {
            val digit = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'a'..'f' -> ch - 'a' + 10
                in 'A'..'F' -> ch - 'A' + 10
                else -> return null
            }
            v = (v shl 4) or digit
        }
        return v
    }

    private fun parseIpv6Half(side: String, allowTrailingIpv4: Boolean): List<Int>? {
        if (side.isEmpty()) return emptyList()
        val rawGroups = side.split(':')
        val out = ArrayList<Int>(rawGroups.size + 1)
        for (i in rawGroups.indices) {
            val g = rawGroups[i]
            if (g.isEmpty()) return null
            if (g.contains('.')) {
                if (!allowTrailingIpv4 || i != rawGroups.lastIndex) return null
                val octets = parseIpv4Octets(g) ?: return null
                out.add((octets[0] shl 8) or octets[1])
                out.add((octets[2] shl 8) or octets[3])
            } else {
                out.add(parseHexGroup(g) ?: return null)
            }
        }
        return out
    }

    private fun isValidSafeIpv6(ipPart: String, prefixStr: String?): Boolean {
        if (ipPart.contains(":::")) return false
        if (ipPart.startsWith(":") && !ipPart.startsWith("::")) return false
        if (ipPart.endsWith(":") && !ipPart.endsWith("::")) return false

        val prefix = parsePrefix(prefixStr, 128) ?: return false

        val firstDc = ipPart.indexOf("::")
        val words = IntArray(8)
        if (firstDc == -1) {
            val groups = parseIpv6Half(ipPart, allowTrailingIpv4 = true) ?: return false
            if (groups.size != 8) return false
            for (i in 0 until 8) words[i] = groups[i]
        } else {
            if (ipPart.indexOf("::", firstDc + 2) != -1) return false
            val leftStr = ipPart.substring(0, firstDc)
            val rightStr = ipPart.substring(firstDc + 2)
            val left = parseIpv6Half(leftStr, allowTrailingIpv4 = false) ?: return false
            val right = parseIpv6Half(rightStr, allowTrailingIpv4 = true) ?: return false
            if (left.size + right.size >= 8) return false
            for (i in left.indices) words[i] = left[i]
            val rightStart = 8 - right.size
            for (i in right.indices) words[rightStart + i] = right[i]
        }

        if (isIpv6Unspecified(words) || isIpv6Loopback(words)) return false

        val masked = IntArray(8)
        for (i in 0 until 8) {
            val bitStart = i * 16
            masked[i] = when {
                prefix <= bitStart -> 0
                prefix >= bitStart + 16 -> words[i]
                else -> {
                    val keep = prefix - bitStart
                    words[i] and ((0xFFFF shl (16 - keep)) and 0xFFFF)
                }
            }
        }
        if (isIpv6Unspecified(masked) || isIpv6Loopback(masked)) return false
        return true
    }

    private fun isIpv6Unspecified(w: IntArray): Boolean = w.all { it == 0 }

    private fun isIpv6Loopback(w: IntArray): Boolean =
        w[0] == 0 && w[1] == 0 && w[2] == 0 && w[3] == 0 &&
            w[4] == 0 && w[5] == 0 && w[6] == 0 && w[7] == 1

    /**
     * Extracts ALL valid IPv4/IPv6/CIDR tokens from multi-IP lines after stripping inline `#` and `//`
     * comments and skipping full-line `;`, `#`, `//` comments. Enforces entry & JSON-byte caps.
     */
    fun parseLinesDetailed(
        rawText: String,
        maxEntries: Int = MAX_ENTRIES,
        maxJsonBytes: Int = MAX_SERIALIZED_JSON_BYTES,
    ): BlocklistParseResult {
        val out = LinkedHashSet<String>()
        var approxJsonBytes = 128 // JSON-RPC envelope overhead
        var hitByteCap = false
        var hitEntryCap = false

        outer@ for (line in rawText.lineSequence()) {
            val trimmedStart = line.trimStart()
            if (trimmedStart.isEmpty() ||
                trimmedStart.startsWith("#") ||
                trimmedStart.startsWith("//") ||
                trimmedStart.startsWith(";")
            ) continue

            val stripped = trimmedStart
                .substringBefore('#')
                .substringBefore("//")
                .trim()
            if (stripped.isEmpty()) continue

            for (tok in stripped.split(tokenSplitRegex)) {
                if (tok.isEmpty()) continue
                val valid = validateEntry(tok) ?: continue
                if (out.contains(valid)) continue
                if (out.size >= maxEntries) {
                    hitEntryCap = true
                    break@outer
                }
                val entryBytes = valid.length + 3 // `"...",`
                if (approxJsonBytes + entryBytes > maxJsonBytes) {
                    hitByteCap = true
                    break@outer
                }
                out.add(valid)
                approxJsonBytes += entryBytes
            }
        }
        return BlocklistParseResult(
            entries = out.toList(),
            truncatedByByteCap = hitByteCap,
            truncatedByEntryCap = hitEntryCap,
        )
    }

    fun parseLines(
        rawText: String,
        maxEntries: Int = MAX_ENTRIES,
        maxJsonBytes: Int = MAX_SERIALIZED_JSON_BYTES,
    ): List<String> = parseLinesDetailed(rawText, maxEntries, maxJsonBytes).entries

    /**
     * Streaming SAF-safe parser enforcing an 8 MiB raw byte cap + 3.5 MiB JSON payload cap
     * without allocating the entire input file in RAM (APP-M05, APP-H20).
     */
    fun parseStream(
        inputStream: java.io.InputStream,
        maxEntries: Int = MAX_ENTRIES,
        maxJsonBytes: Int = MAX_SERIALIZED_JSON_BYTES,
        maxReadBytes: Long = MAX_STREAM_READ_BYTES,
    ): BlocklistParseResult {
        var streamTruncated = false
        val bounded = object : java.io.InputStream() {
            private var remaining = maxReadBytes
            override fun read(): Int {
                if (remaining <= 0L) {
                    if (inputStream.read() != -1) streamTruncated = true
                    return -1
                }
                val b = inputStream.read()
                if (b != -1) remaining--
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len <= 0) return 0
                if (remaining <= 0L) {
                    if (inputStream.read() != -1) streamTruncated = true
                    return -1
                }
                val toRead = minOf(len.toLong(), remaining).toInt()
                val n = inputStream.read(b, off, toRead)
                if (n > 0) remaining -= n
                return n
            }

            override fun close() {
                inputStream.close()
            }
        }

        val out = LinkedHashSet<String>()
        var approxJsonBytes = 128
        var hitByteCap = false
        var hitEntryCap = false

        java.io.BufferedReader(java.io.InputStreamReader(bounded, Charsets.UTF_8)).use { br ->
            val lineBuf = StringBuilder(256)
            outer@ while (true) {
                lineBuf.setLength(0)
                var chInt = br.read()
                if (chInt == -1) break
                while (chInt != -1 && chInt != '\n'.code) {
                    if (chInt != '\r'.code && lineBuf.length < MAX_LINE_CHARS) {
                        lineBuf.append(chInt.toChar())
                    }
                    chInt = br.read()
                }
                val trimmedStart = lineBuf.toString().trimStart()
                if (trimmedStart.isEmpty() ||
                    trimmedStart.startsWith("#") ||
                    trimmedStart.startsWith("//") ||
                    trimmedStart.startsWith(";")
                ) continue

                val stripped = trimmedStart
                    .substringBefore('#')
                    .substringBefore("//")
                    .trim()
                if (stripped.isEmpty()) continue

                for (tok in stripped.split(tokenSplitRegex)) {
                    if (tok.isEmpty()) continue
                    val valid = validateEntry(tok) ?: continue
                    if (out.contains(valid)) continue
                    if (out.size >= maxEntries) {
                        hitEntryCap = true
                        break@outer
                    }
                    val entryBytes = valid.length + 3
                    if (approxJsonBytes + entryBytes > maxJsonBytes) {
                        hitByteCap = true
                        break@outer
                    }
                    out.add(valid)
                    approxJsonBytes += entryBytes
                }
            }
        }
        return BlocklistParseResult(
            entries = out.toList(),
            truncatedByByteCap = hitByteCap,
            truncatedByEntryCap = hitEntryCap,
            truncatedByStreamCap = streamTruncated,
        )
    }
}

enum class AppLanguagePref(val key: String) {
    SYSTEM("system"),
    INDONESIAN("id"),
    ENGLISH("en");

    companion object {
        fun fromKey(k: String?): AppLanguagePref = entries.firstOrNull { it.key == k } ?: SYSTEM
    }
}

enum class AppThemePref(val key: String) {
    OBSIDIAN("obsidian"),
    AMOLED("amoled"),
    DYNAMIC("dynamic");

    companion object {
        fun fromKey(k: String?): AppThemePref = entries.firstOrNull { it.key == k } ?: OBSIDIAN
    }
}

class SiloRepository(private val client: SiloClient = SiloClient()) {
    private val json = client.json

    private fun prefs(context: Context) =
        context.getSharedPreferences("silo_ui_prefs", Context.MODE_PRIVATE)

    fun getLanguagePref(context: Context): AppLanguagePref =
        AppLanguagePref.fromKey(prefs(context).getString("language", "system"))

    fun setLanguagePref(context: Context, pref: AppLanguagePref) {
        prefs(context).edit().putString("language", pref.key).apply()
    }

    fun getThemePref(context: Context): AppThemePref =
        AppThemePref.fromKey(prefs(context).getString("theme", "obsidian"))

    fun setThemePref(context: Context, pref: AppThemePref) {
        prefs(context).edit().putString("theme", pref.key).apply()
    }

    /** Native Android 12-16 clipboard wipe guaranteed to work on One UI 7 even when shell cmd fails. */
    fun clearClipboardNative(context: Context): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cm.clearPrimaryClip()
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
            true
        }.getOrDefault(false)
    }

    suspend fun getStatus(): Result<DaemonStatus> = client.call("get_status")
    suspend fun getConfig(): Result<SiloConfig> = client.call("get_config")

    suspend fun applyPreset(preset: SecurityPreset): Result<SiloConfig> =
        client.call("apply_preset", buildJsonObject {
            put("preset", json.encodeToJsonElement(preset))
        }, timeoutMs = 25_000L)

    suspend fun setFirewallConfig(config: FirewallConfig): Result<FirewallConfig> =
        client.call("set_firewall_config", buildJsonObject {
            put("config", json.encodeToJsonElement(config))
        })

    suspend fun setAppFirewallRule(uid: Int, rule: AppFirewallRule): Result<Unit> =
        client.callRaw("set_app_firewall_rule", buildJsonObject {
            put("uid", uid)
            put("rule", json.encodeToJsonElement(rule))
        }).map { }

    suspend fun removeAppFirewallRule(uid: Int): Result<Unit> =
        client.callRaw("remove_app_firewall_rule", buildJsonObject {
            put("uid", uid)
        }).map { }

    suspend fun setTempUnblock(uid: Int, durationMinutes: Int): Result<Long> =
        client.call<JsonObject>("set_temp_unblock", buildJsonObject {
            put("uid", uid)
            put("duration_minutes", durationMinutes)
        }).map { it["remaining_secs"]?.jsonPrimitive?.longOrNull ?: (durationMinutes * 60L) }

    suspend fun clearTempUnblock(uid: Int): Result<Unit> =
        client.callRaw("clear_temp_unblock", buildJsonObject {
            put("uid", uid)
        }).map { }

    suspend fun replaceIpBlocklist(entries: List<String>): Result<JsonObject> =
        client.call("replace_ip_blocklist", buildJsonObject {
            put("entries", json.encodeToJsonElement(entries))
        }, timeoutMs = 35_000L)

    suspend fun emergencyFlushFirewall(): Result<Unit> =
        client.callRaw("emergency_flush_firewall").map { }

    suspend fun killUidSockets(uid: Int): Result<Int> =
        client.call<JsonObject>("kill_uid_sockets", buildJsonObject {
            put("uid", uid)
        }).map { it["killed"]?.jsonPrimitive?.intOrNull ?: 0 }

    suspend fun killConnection(conn: ActiveConnection): Result<Int> =
        client.call<JsonObject>("kill_connection", buildJsonObject {
            put("family", conn.family)
            put("local_ip", conn.localIp)
            put("local_port", conn.localPort)
            put("remote_ip", conn.remoteIp)
            put("remote_port", conn.remotePort)
        }).map { it["killed"]?.jsonPrimitive?.intOrNull ?: 0 }

    suspend fun getLiveConnections(): Result<List<ActiveConnection>> =
        client.call("get_live_connections")

    suspend fun getTelemetry(limit: Int = 200): Result<TelemetrySnapshot> =
        client.call("get_telemetry", buildJsonObject {
            put("limit", limit)
        })

    suspend fun clearTelemetry(): Result<Unit> =
        client.callRaw("clear_telemetry").map { }

    suspend fun getFreezerStatus(): Result<FreezerStatus> =
        client.call("get_freezer_status")

    suspend fun setFreezerEnabled(enabled: Boolean, graceSecs: Int): Result<FreezerStatus> =
        client.call("set_freezer_enabled", buildJsonObject {
            put("enabled", enabled)
            put("background_grace_secs", graceSecs)
        })

    suspend fun setFreezerEntry(uid: Int, entry: FreezerEntry): Result<FreezerStatus> =
        client.call("set_freezer_entry", buildJsonObject {
            put("uid", uid)
            put("entry", json.encodeToJsonElement(entry))
        })

    suspend fun removeFreezerEntry(uid: Int): Result<FreezerStatus> =
        client.call("remove_freezer_entry", buildJsonObject {
            put("uid", uid)
        })

    suspend fun freezeUidNow(uid: Int): Result<FreezerStatus> =
        client.call("freeze_uid_now", buildJsonObject {
            put("uid", uid)
        })

    suspend fun thawUidNow(uid: Int): Result<FreezerStatus> =
        client.call("thaw_uid_now", buildJsonObject {
            put("uid", uid)
        })

    suspend fun thawAllNow(): Result<FreezerStatus> =
        client.call("thaw_all_now")

    @Deprecated("Legacy v2 scope API; use setAppPermissionAction / setAppPermGuard")
    suspend fun setAppScope(packageName: String, scope: AppIsolationScope): Result<Unit> =
        client.callRaw("set_app_scope", buildJsonObject {
            put("package_name", packageName)
            put("scope", json.encodeToJsonElement(scope))
        }).map { }

    @Deprecated("Legacy v2 scope API; use setAppPermissionAction / setAppPermGuard")
    suspend fun applyHardenedScope(packageName: String): Result<AppIsolationScope> =
        client.call("apply_hardened_scope", buildJsonObject {
            put("package_name", packageName)
        })

    suspend fun clearAppScope(packageName: String): Result<Unit> =
        client.callRaw("clear_app_scope", buildJsonObject {
            put("package_name", packageName)
        }).map { }

    suspend fun inspectAppPermissions(packageName: String): Result<AppLivePermissions> =
        client.call("inspect_app_permissions", buildJsonObject {
            put("package_name", packageName)
        }, timeoutMs = 15_000L)

    suspend fun setAppPermissionAction(
        packageName: String,
        category: String,
        action: String,
    ): Result<AppLivePermissions> =
        client.call("set_app_permission_action", buildJsonObject {
            put("package_name", packageName)
            put("category", category)
            put("action", action)
        }, timeoutMs = 15_000L)

    suspend fun setAppPermGuard(packageName: String, guard: AppPermGuard): Result<ScopesConfig> =
        client.call("set_app_perm_guard", buildJsonObject {
            put("package_name", packageName)
            put("guard", json.encodeToJsonElement(guard))
        }, timeoutMs = 25_000L)

    suspend fun bulkRevokeCategory(category: String, packages: List<String>): Result<Int> =
        client.call<JsonObject>("bulk_revoke_category", buildJsonObject {
            put("category", category)
            put("packages", json.encodeToJsonElement(packages))
        }, timeoutMs = 35_000L).map { it["revoked_count"]?.jsonPrimitive?.intOrNull ?: 0 }

    suspend fun getPrivacyAccessLog(limit: Int = 80): Result<List<PrivacyAccessEvent>> =
        client.call("get_privacy_access_log", buildJsonObject {
            put("limit", limit)
        }, timeoutMs = 25_000L)

    suspend fun setAutoClearClipboard(enabled: Boolean, clearSecs: Int): Result<ScopesConfig> =
        client.call("set_auto_clear_clipboard", buildJsonObject {
            put("enabled", enabled)
            put("clear_secs", clearSecs)
        })

    suspend fun clearClipboardNow(): Result<Unit> =
        client.callRaw("clear_clipboard_now").map { }

    suspend fun getHardeningStatus(): Result<HardeningStatus> =
        client.call("get_hardening_status")

    suspend fun setHardeningConfig(config: HardeningConfig): Result<HardeningStatus> =
        client.call("set_hardening_config", buildJsonObject {
            put("config", json.encodeToJsonElement(config))
        })

    suspend fun rollbackHardening(): Result<HardeningStatus> =
        client.call("rollback_hardening")

    suspend fun setAutomationConfig(config: AutomationConfig): Result<AutomationConfig> =
        client.call("set_automation_config", buildJsonObject {
            put("config", json.encodeToJsonElement(config))
        })

    suspend fun setSensorsOff(enabled: Boolean): Result<SensorPrivacyState> =
        client.call("set_sensors_off", buildJsonObject {
            put("enabled", enabled)
        })

    suspend fun setSensorPrivacy(sensor: String, enabled: Boolean): Result<SensorPrivacyState> =
        client.call("set_sensor_privacy", buildJsonObject {
            put("sensor", sensor)
            put("enabled", enabled)
        })

    suspend fun setPrivateDns(mode: String, specifier: String = ""): Result<PrivateDnsState> =
        client.call("set_private_dns", buildJsonObject {
            put("mode", mode)
            put("specifier", specifier)
        })

    suspend fun setRadioPrivacy(
        enhancedMacRandomization: Boolean? = null,
        disableWifiBleScanning: Boolean? = null,
        disableCaptivePortal: Boolean? = null,
    ): Result<RadioPrivacyState> =
        client.call("set_radio_privacy", buildJsonObject {
            if (enhancedMacRandomization != null) put("enhanced_mac_randomization", enhancedMacRandomization)
            if (disableWifiBleScanning != null) put("disable_wifi_ble_scanning", disableWifiBleScanning)
            if (disableCaptivePortal != null) put("disable_captive_portal", disableCaptivePortal)
        })

    suspend fun triggerPanicLockdown(): Result<PanicLockdownReport> =
        client.call("trigger_panic_lockdown", timeoutMs = 25_000L)

    suspend fun triggerPanicReboot(): Result<Unit> =
        client.callRaw("trigger_panic_reboot", buildJsonObject {
            put("confirm_token", "CONFIRM_SILO_BFU_REBOOT")
        }).map { }

    suspend fun runSentinelAudit(): Result<SentinelReport> =
        client.call("run_sentinel_audit", timeoutMs = 25_000L)

    suspend fun runLiveProof(): Result<LiveProofReport> =
        client.call("run_live_proof", timeoutMs = 25_000L)

    suspend fun exportConfigJson(): Result<String> =
        client.call<JsonObject>("export_config_json").map {
            it["json"]?.jsonPrimitive?.content ?: error("Empty export payload")
        }

    suspend fun importConfigJson(rawJson: String): Result<SiloConfig> =
        client.call("import_config_json", buildJsonObject {
            put("json", rawJson)
        }, timeoutMs = 30_000L)

    fun isDisguiseEnabled(context: Context): Boolean {
        val pm = context.packageManager
        val disguise = ComponentName(context, "dev.silo.app.LauncherDisguise")
        return pm.getComponentEnabledSetting(disguise) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }

    fun setDisguiseEnabled(context: Context, enabled: Boolean) {
        val pm = context.packageManager
        val primary = ComponentName(context, "dev.silo.app.LauncherDefault")
        val disguise = ComponentName(context, "dev.silo.app.LauncherDisguise")
        if (enabled) {
            pm.setComponentEnabledSetting(disguise, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(primary, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        } else {
            pm.setComponentEnabledSetting(primary, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(disguise, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}
