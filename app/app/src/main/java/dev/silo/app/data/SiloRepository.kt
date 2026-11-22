package dev.silo.app.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dev.silo.app.ipc.*
import kotlinx.serialization.json.*

/**
 * Pure parser for user-imported local IP/CIDR blocklists (FireHOL, Spamhaus, custom text files).
 * Ignores comments, blank lines, and extracts clean IPv4/IPv6/CIDR tokens safely.
 */
object BlocklistParser {
    private val ipv4Regex = Regex("""^\d{1,3}(\.\d{1,3}){3}(/\d{1,2})?$""")
    private val ipv6Regex = Regex("""^[0-9a-fA-F:]+(/\d{1,3})?$""")

    fun parseLines(rawText: String, maxEntries: Int = 50_000): List<String> {
        val out = LinkedHashSet<String>()
        rawText.lineSequence().forEach { line ->
            if (out.size >= maxEntries) return@forEach
            val stripped = line.substringBefore('#').substringBefore(';').trim()
            if (stripped.isEmpty()) return@forEach
            // Handle hosts-like or CSV/whitespace lines by scanning tokens
            val token = stripped.split(Regex("""[\s,]+""")).firstOrNull { tok ->
                ipv4Regex.matches(tok) || (tok.contains(':') && ipv6Regex.matches(tok))
            }
            if (token != null) {
                out.add(token)
            }
        }
        return out.toList()
    }
}

class SiloRepository(private val client: SiloClient = SiloClient()) {
    private val json = client.json

    suspend fun getStatus(): Result<DaemonStatus> = client.call("get_status")
    suspend fun getConfig(): Result<SiloConfig> = client.call("get_config")

    suspend fun applyPreset(preset: SecurityPreset): Result<SiloConfig> =
        client.call("apply_preset", buildJsonObject {
            put("preset", json.encodeToJsonElement(preset))
        })

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

    suspend fun replaceIpBlocklist(entries: List<String>): Result<JsonObject> =
        client.call("replace_ip_blocklist", buildJsonObject {
            put("entries", json.encodeToJsonElement(entries))
        }, timeoutMs = 25_000L)

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

    suspend fun setAppScope(packageName: String, scope: AppIsolationScope): Result<Unit> =
        client.callRaw("set_app_scope", buildJsonObject {
            put("package_name", packageName)
            put("scope", json.encodeToJsonElement(scope))
        }).map { }

    suspend fun applyHardenedScope(packageName: String): Result<AppIsolationScope> =
        client.call("apply_hardened_scope", buildJsonObject {
            put("package_name", packageName)
        })

    suspend fun clearAppScope(packageName: String): Result<Unit> =
        client.callRaw("clear_app_scope", buildJsonObject {
            put("package_name", packageName)
        }).map { }

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

    suspend fun setSensorsOff(enabled: Boolean): Result<Boolean> =
        client.call<JsonObject>("set_sensors_off", buildJsonObject {
            put("enabled", enabled)
        }).map { it["sensors_off"]?.jsonPrimitive?.booleanOrNull ?: enabled }

    suspend fun triggerPanicReboot(): Result<Unit> =
        client.callRaw("trigger_panic_reboot", buildJsonObject {
            put("confirm_token", "CONFIRM_SILO_BFU_REBOOT")
        }).map { }

    suspend fun runSentinelAudit(): Result<SentinelReport> =
        client.call("run_sentinel_audit", timeoutMs = 15_000L)

    suspend fun exportConfigJson(): Result<String> =
        client.call<JsonObject>("export_config_json").map {
            it["json"]?.jsonPrimitive?.content ?: error("Empty export payload")
        }

    suspend fun importConfigJson(rawJson: String): Result<SiloConfig> =
        client.call("import_config_json", buildJsonObject {
            put("json", rawJson)
        }, timeoutMs = 20_000L)

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
            pm.setComponentEnabledSetting(
                disguise,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            pm.setComponentEnabledSetting(
                primary,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        } else {
            pm.setComponentEnabledSetting(
                primary,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            pm.setComponentEnabledSetting(
                disguise,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
