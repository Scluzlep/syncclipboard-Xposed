package com.scluzlep.syncclipboard.config

import android.content.Context
import android.content.SharedPreferences
import com.scluzlep.syncclipboard.model.AppConfig
import com.scluzlep.syncclipboard.model.NetworkTransportRule
import com.scluzlep.syncclipboard.model.NodeNetworkRule
import com.scluzlep.syncclipboard.model.PaletteStyleOption
import com.scluzlep.syncclipboard.model.SelectorConfig
import com.scluzlep.syncclipboard.model.SelectorMode
import com.scluzlep.syncclipboard.model.ServerNode
import com.scluzlep.syncclipboard.model.ServerType
import com.scluzlep.syncclipboard.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

interface ConfigBackingStore {
    fun read(): String?
    fun write(raw: String)
}

class SharedPrefsConfigBackingStore(
    private val prefs: SharedPreferences,
    private val key: String = KEY_CONFIG_JSON
) : ConfigBackingStore {
    override fun read(): String? = prefs.getString(key, null)

    override fun write(raw: String) {
        prefs.edit().putString(key, raw).apply()
    }

    companion object {
        const val PREFS_NAME = "syncclipboard_config"
        const val KEY_CONFIG_JSON = "app_config_json"
    }
}

class InMemoryConfigBackingStore(initialJson: String? = null) : ConfigBackingStore {
    @Volatile
    private var stored: String? = initialJson

    override fun read(): String? = stored

    override fun write(raw: String) {
        stored = raw
    }
}

/**
 * Single-process Credential-Encrypted (CE) config store with JSON persistence and export/import.
 */
class ConfigStore(
    private val backingStore: ConfigBackingStore
) {
    constructor(context: Context) : this(
        SharedPrefsConfigBackingStore(
            context.getSharedPreferences(
                SharedPrefsConfigBackingStore.PREFS_NAME,
                Context.MODE_PRIVATE
            )
        )
    )

    private val lock = Any()
    private val _configFlow = MutableStateFlow(loadInitialConfig())
    val configFlow: StateFlow<AppConfig> = _configFlow.asStateFlow()

    val current: AppConfig
        get() = _configFlow.value

    private fun loadInitialConfig(): AppConfig {
        val raw = backingStore.read() ?: return AppConfig()
        return parseConfigJson(raw).getOrElse { AppConfig() }
    }

    fun update(transform: (AppConfig) -> AppConfig): AppConfig {
        synchronized(lock) {
            val updated = sanitizeConfig(transform(_configFlow.value))
            _configFlow.value = updated
            backingStore.write(serializeConfig(updated, includePasswords = true))
            return updated
        }
    }

    fun upsertNode(node: ServerNode): AppConfig {
        val normalized = normalizeNode(node)
        return update { cfg ->
            val idx = cfg.nodes.indexOfFirst { it.id == normalized.id }
            val nextNodes = if (idx >= 0) {
                cfg.nodes.toMutableList().apply { set(idx, normalized) }
            } else {
                cfg.nodes + normalized
            }
            val nextSelector = if (cfg.selector.manualNodeId == null && nextNodes.size == 1) {
                cfg.selector.copy(manualNodeId = normalized.id)
            } else {
                cfg.selector
            }
            cfg.copy(nodes = nextNodes, selector = nextSelector)
        }
    }

    fun deleteNode(nodeId: String): AppConfig {
        return update { cfg ->
            val nextNodes = cfg.nodes.filterNot { it.id == nodeId }
            val nextManual = if (cfg.selector.manualNodeId == nodeId) {
                nextNodes.firstOrNull { it.enabled }?.id ?: nextNodes.firstOrNull()?.id
            } else {
                cfg.selector.manualNodeId
            }
            cfg.copy(
                nodes = nextNodes,
                selector = cfg.selector.copy(manualNodeId = nextManual)
            )
        }
    }

    fun exportConfigJson(includePasswords: Boolean): String {
        synchronized(lock) {
            return serializeConfig(_configFlow.value, includePasswords = includePasswords)
        }
    }

    fun importConfigJson(json: String): Result<Unit> {
        return parseConfigJson(json, existingForRedacted = _configFlow.value).mapCatching { parsed ->
            synchronized(lock) {
                val sanitized = sanitizeConfig(parsed)
                _configFlow.value = sanitized
                backingStore.write(serializeConfig(sanitized, includePasswords = true))
            }
        }
    }

    companion object {
        private const val SCHEMA_VERSION = 1

        fun normalizeBaseUrl(raw: String): String {
            return raw.trim().trimEnd('/')
        }

        private fun normalizeNode(node: ServerNode): ServerNode {
            val id = node.id.trim().ifEmpty { UUID.randomUUID().toString() }
            return node.copy(
                id = id,
                name = node.name.trim().ifEmpty { normalizeBaseUrl(node.baseUrl) },
                baseUrl = normalizeBaseUrl(node.baseUrl),
                username = node.username.trim(),
                customCertSha256 = node.customCertSha256?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(),
                networkRule = node.networkRule.copy(
                    cidrAllowlist = node.networkRule.cidrAllowlist.map { it.trim() }.filter { it.isNotEmpty() },
                    ssidAllowlist = node.networkRule.ssidAllowlist.map { it.trim() }.filter { it.isNotEmpty() }
                )
            )
        }

        private fun sanitizeConfig(config: AppConfig): AppConfig {
            val normalizedNodes = config.nodes
                .map(::normalizeNode)
                .distinctBy { it.id }
            val validIds = normalizedNodes.map { it.id }.toSet()
            val manualId = config.selector.manualNodeId?.takeIf { it in validIds }
            val sel = config.selector.copy(
                manualNodeId = manualId,
                failOverThreshold = config.selector.failOverThreshold.coerceIn(1, 20),
                recoveryHoldSec = config.selector.recoveryHoldSec.coerceIn(5, 3600),
                urlTestMinGainMs = config.selector.urlTestMinGainMs.coerceIn(0, 5000),
                probeIntervalSec = config.selector.probeIntervalSec.coerceIn(10, 3600)
            )
            return config.copy(
                nodes = normalizedNodes,
                selector = sel,
                screenOffDisconnectDelaySec = config.screenOffDisconnectDelaySec.coerceAtLeast(0),
                autoDownloadMaxSizeMb = config.autoDownloadMaxSizeMb.coerceIn(1, 2048)
            )
        }

        fun serializeConfig(config: AppConfig, includePasswords: Boolean): String {
            val root = JSONObject()
            root.put("schemaVersion", SCHEMA_VERSION)
            root.put("otpCaptureEnabled", config.otpCaptureEnabled)
            root.put("screenOffDisconnectDelaySec", config.screenOffDisconnectDelaySec)
            root.put("autoSyncEnabled", config.autoSyncEnabled)
            root.put("autoUploadEnabled", config.autoUploadEnabled)
            root.put("autoDownloadEnabled", config.autoDownloadEnabled)
            root.put("writeImageToClipboard", config.writeImageToClipboard)
            root.put("writeOtpToClipboard", config.writeOtpToClipboard)
            root.put("autoDownloadMaxSizeMb", config.autoDownloadMaxSizeMb)
            root.put("themeMode", config.themeMode.name)
            root.put("monetEnabled", config.monetEnabled)
            root.put("paletteStyle", config.paletteStyle.name)
            root.put("hideLauncherIcon", config.hideLauncherIcon)

            val selObj = JSONObject().apply {
                put("mode", config.selector.mode.name)
                if (config.selector.manualNodeId != null) {
                    put("manualNodeId", config.selector.manualNodeId)
                } else {
                    put("manualNodeId", JSONObject.NULL)
                }
                put("failOverThreshold", config.selector.failOverThreshold)
                put("recoveryHoldSec", config.selector.recoveryHoldSec)
                put("urlTestMinGainMs", config.selector.urlTestMinGainMs)
                put("probeIntervalSec", config.selector.probeIntervalSec)
            }
            root.put("selector", selObj)

            val nodesArr = JSONArray()
            for (node in config.nodes) {
                val nObj = JSONObject().apply {
                    put("id", node.id)
                    put("name", node.name)
                    put("enabled", node.enabled)
                    put("serverType", node.serverType.name)
                    put("baseUrl", normalizeBaseUrl(node.baseUrl))
                    put("username", node.username)
                    if (includePasswords) {
                        put("password", node.password)
                    }
                    put("allowCleartext", node.allowCleartext)
                    if (node.customCertSha256 != null) {
                        put("customCertSha256", node.customCertSha256)
                    } else {
                        put("customCertSha256", JSONObject.NULL)
                    }
                    put("priority", node.priority)

                    val ruleObj = JSONObject().apply {
                        val transportsArr = JSONArray()
                        node.networkRule.allowedTransports.forEach { transportsArr.put(it.name) }
                        put("allowedTransports", transportsArr)

                        val cidrArr = JSONArray()
                        node.networkRule.cidrAllowlist.forEach { cidrArr.put(it) }
                        put("cidrAllowlist", cidrArr)

                        val ssidArr = JSONArray()
                        node.networkRule.ssidAllowlist.forEach { ssidArr.put(it) }
                        put("ssidAllowlist", ssidArr)
                    }
                    put("networkRule", ruleObj)
                }
                nodesArr.put(nObj)
            }
            root.put("nodes", nodesArr)
            return root.toString(2)
        }

        fun parseConfigJson(
            raw: String,
            existingForRedacted: AppConfig? = null
        ): Result<AppConfig> = runCatching {
            val root = JSONObject(raw)
            val existingById = existingForRedacted?.nodes?.associateBy { it.id }.orEmpty()

            val nodesList = mutableListOf<ServerNode>()
            val nodesArr = root.optJSONArray("nodes") ?: JSONArray()
            for (i in 0 until nodesArr.length()) {
                val nObj = nodesArr.getJSONObject(i)
                val id = nObj.optString("id", "").trim().ifEmpty { UUID.randomUUID().toString() }
                val baseUrl = normalizeBaseUrl(nObj.optString("baseUrl", ""))
                if (baseUrl.isEmpty()) {
                    throw IllegalArgumentException("Node $id has empty baseUrl")
                }
                val serverType = parseEnum(nObj.optString("serverType"), ServerType.OFFICIAL)
                val password = if (nObj.has("password") && !nObj.isNull("password")) {
                    nObj.getString("password")
                } else {
                    existingById[id]?.password ?: ""
                }
                val customCert = if (nObj.isNull("customCertSha256")) {
                    null
                } else {
                    nObj.optString("customCertSha256", "").trim().takeIf { it.isNotEmpty() }
                }

                val ruleObj = nObj.optJSONObject("networkRule")
                val networkRule = if (ruleObj != null) {
                    val transports = if (ruleObj.has("allowedTransports")) {
                        val arr = ruleObj.optJSONArray("allowedTransports") ?: JSONArray()
                        buildSet {
                            for (t in 0 until arr.length()) {
                                parseEnumOrNull<NetworkTransportRule>(arr.optString(t))?.let(::add)
                            }
                        }
                    } else {
                        NodeNetworkRule().allowedTransports
                    }
                    val cidrs = ruleObj.optJSONArray("cidrAllowlist").toStringList()
                    val ssids = ruleObj.optJSONArray("ssidAllowlist").toStringList()
                    NodeNetworkRule(
                        allowedTransports = transports,
                        cidrAllowlist = cidrs,
                        ssidAllowlist = ssids
                    )
                } else {
                    NodeNetworkRule()
                }

                nodesList += ServerNode(
                    id = id,
                    name = nObj.optString("name", baseUrl),
                    enabled = nObj.optBoolean("enabled", true),
                    serverType = serverType,
                    baseUrl = baseUrl,
                    username = nObj.optString("username", ""),
                    password = password,
                    allowCleartext = nObj.optBoolean("allowCleartext", false),
                    customCertSha256 = customCert,
                    priority = nObj.optInt("priority", 0),
                    networkRule = networkRule
                )
            }

            val selObj = root.optJSONObject("selector")
            val selector = if (selObj != null) {
                SelectorConfig(
                    mode = parseEnum(selObj.optString("mode"), SelectorMode.FALLBACK),
                    manualNodeId = selObj.optString("manualNodeId", "")
                        .takeIf { !selObj.isNull("manualNodeId") && it.isNotBlank() },
                    failOverThreshold = selObj.optInt("failOverThreshold", 3),
                    recoveryHoldSec = selObj.optInt("recoveryHoldSec", 60),
                    urlTestMinGainMs = selObj.optInt("urlTestMinGainMs", 80),
                    probeIntervalSec = selObj.optInt("probeIntervalSec", 60)
                )
            } else {
                SelectorConfig()
            }

            sanitizeConfig(
                AppConfig(
                    nodes = nodesList,
                    selector = selector,
                    otpCaptureEnabled = root.optBoolean("otpCaptureEnabled", true),
                    screenOffDisconnectDelaySec = root.optInt("screenOffDisconnectDelaySec", 0),
                    autoSyncEnabled = root.optBoolean("autoSyncEnabled", true),
                    autoUploadEnabled = root.optBoolean("autoUploadEnabled", true),
                    autoDownloadEnabled = root.optBoolean("autoDownloadEnabled", true),
                    writeImageToClipboard = root.optBoolean("writeImageToClipboard", true),
                    writeOtpToClipboard = root.optBoolean("writeOtpToClipboard", true),
                    autoDownloadMaxSizeMb = root.optInt("autoDownloadMaxSizeMb", 20),
                    themeMode = parseEnum(root.optString("themeMode"), ThemeMode.SYSTEM),
                    monetEnabled = root.optBoolean("monetEnabled", true),
                    paletteStyle = parseEnum(root.optString("paletteStyle"), PaletteStyleOption.TonalSpot),
                    hideLauncherIcon = root.optBoolean("hideLauncherIcon", false)
                )
            )
        }

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            val out = ArrayList<String>(length())
            for (i in 0 until length()) {
                val item = optString(i, "").trim()
                if (item.isNotEmpty()) out += item
            }
            return out
        }

        private inline fun <reified T : Enum<T>> parseEnum(raw: String?, fallback: T): T {
            return parseEnumOrNull<T>(raw) ?: fallback
        }

        private inline fun <reified T : Enum<T>> parseEnumOrNull(raw: String?): T? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim()
            return enumValues<T>().firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        }
    }
}
