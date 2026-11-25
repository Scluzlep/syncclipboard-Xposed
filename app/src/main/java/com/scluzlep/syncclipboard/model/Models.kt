package com.scluzlep.syncclipboard.model

import kotlinx.coroutines.flow.StateFlow

enum class ProfileType {
    Text,
    File,
    Image,
    Group,
    Unknown,
    None;

    companion object {
        fun fromWire(value: String?): ProfileType {
            if (value.isNullOrBlank()) return None
            return entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: Unknown
        }
    }
}

object ProfileTypeFilter {
    const val NONE = 0
    const val TEXT = 1 shl 0
    const val FILE = 1 shl 1
    const val IMAGE = 1 shl 2
    const val GROUP = 1 shl 3
    const val FILE_AND_GROUP = FILE or GROUP
    const val ALL = TEXT or FILE or IMAGE or GROUP

    fun maskFor(type: ProfileType): Int = when (type) {
        ProfileType.Text -> TEXT
        ProfileType.File -> FILE
        ProfileType.Image -> IMAGE
        ProfileType.Group -> GROUP
        ProfileType.Unknown, ProfileType.None -> NONE
    }
}

data class ProfileDto(
    val type: ProfileType = ProfileType.Text,
    val hash: String = "",
    val text: String = "",
    val hasData: Boolean = false,
    val dataName: String? = null,
    val transferDataHash: String? = null,
    val size: Long? = null,
    val syncedAt: String? = null
)

data class ServerCapabilitiesDto(
    val historySync: Boolean = false,
    val historyTags: Boolean = false,
    val historyTitle: Boolean = false
)

data class HistoryRecordDto(
    val hash: String = "",
    val type: ProfileType = ProfileType.None,
    val text: String = "",
    val createTime: String = "",
    val lastModified: String = "",
    val lastAccessed: String = "",
    val tags: List<String>? = null,
    val title: String? = null,
    val starred: Boolean = false,
    val pinned: Boolean = false,
    val size: Long = 0L,
    val hasData: Boolean = false,
    val version: Int = 0,
    val isDeleted: Boolean = false
)

/**
 * Partial update DTO for `PATCH /api/history/{type}/{hash}` and 409 conflict payloads.
 * Note: `tags == null` omits the field (preserving server tags); `tags == emptyList()` serializes `"tags": []` to clear tags.
 * Similarly, `title == null` omits the field; `title == ""` clears the title.
 */
data class HistoryRecordUpdateDto(
    val tags: List<String>? = null,
    val title: String? = null,
    val starred: Boolean? = null,
    val pinned: Boolean? = null,
    val isDelete: Boolean? = null,
    val version: Int? = null,
    val lastModified: String? = null,
    val lastAccessed: String? = null
)

data class HistoryQueryParams(
    val page: Int = 1,
    val before: String? = null,
    val after: String? = null,
    val modifiedAfter: String? = null,
    val types: Int = ProfileTypeFilter.ALL,
    val searchText: String? = null,
    val searchTitles: Boolean = false,
    val starred: Boolean? = null,
    val sortByLastAccessed: Boolean = false,
    val onlyUntagged: Boolean = false,
    val tags: List<String>? = null
)

data class HistoryStatisticsDto(
    val activeCount: Int = 0,
    val starredCount: Int = 0,
    val deletedCount: Int = 0,
    val totalCount: Int = 0,
    val totalFileSizeMB: Double = 0.0
)

data class HistoryFilterState(
    val searchText: String = "",
    val searchTitles: Boolean = true,
    val selectedType: ProfileType? = null,
    val starredOnly: Boolean = false,
    val selectedTag: String? = null,
    val onlyUntagged: Boolean = false,
    val includeDeleted: Boolean = false
)

data class LocalHistoryItem(
    val nodeStorageKey: String,
    val type: ProfileType,
    val hashUpper: String,
    val text: String,
    val title: String? = null,
    val tags: List<String>? = null,
    val starred: Boolean = false,
    val pinned: Boolean = false,
    val size: Long = 0L,
    val hasData: Boolean = false,
    val transferDataHash: String? = null,
    val localDataPath: String? = null,
    val createTimeEpochMs: Long,
    val lastModifiedEpochMs: Long,
    val lastAccessedEpochMs: Long,
    val version: Int = 0,
    val isDeleted: Boolean = false,
    val needsMetadataBackfill: Boolean = false
)

enum class ServerType {
    OFFICIAL,
    WEBDAV
}

enum class SelectorMode {
    MANUAL,
    FALLBACK,
    URL_TEST,
    RULE_AUTO
}

enum class NetworkTransportRule {
    WIFI,
    CELLULAR,
    VPN,
    ETHERNET
}

data class NodeNetworkRule(
    val allowedTransports: Set<NetworkTransportRule> = setOf(
        NetworkTransportRule.WIFI,
        NetworkTransportRule.CELLULAR,
        NetworkTransportRule.VPN,
        NetworkTransportRule.ETHERNET
    ),
    val cidrAllowlist: List<String> = emptyList(),
    val ssidAllowlist: List<String> = emptyList()
)

data class ServerNode(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val serverType: ServerType = ServerType.OFFICIAL,
    val baseUrl: String,
    val username: String = "",
    val password: String = "",
    val allowCleartext: Boolean = false,
    val customCertSha256: String? = null,
    val priority: Int = 0,
    val networkRule: NodeNetworkRule = NodeNetworkRule()
)

data class SelectorConfig(
    val mode: SelectorMode = SelectorMode.FALLBACK,
    val manualNodeId: String? = null,
    val failOverThreshold: Int = 3,
    val recoveryHoldSec: Int = 60,
    val urlTestMinGainMs: Int = 80,
    val probeIntervalSec: Int = 60
)

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class PaletteStyleOption {
    TonalSpot,
    Neutral,
    Vibrant,
    Expressive,
    Rainbow,
    FruitSalad,
    Monochrome,
    Fidelity,
    Content
}

data class AppConfig(
    val nodes: List<ServerNode> = emptyList(),
    val selector: SelectorConfig = SelectorConfig(),
    val otpCaptureEnabled: Boolean = true,
    val screenOffDisconnectDelaySec: Int = 0,
    val autoSyncEnabled: Boolean = true,
    val autoUploadEnabled: Boolean = true,
    val autoDownloadEnabled: Boolean = true,
    val writeImageToClipboard: Boolean = true,
    val writeOtpToClipboard: Boolean = true,
    val autoDownloadMaxSizeMb: Int = 20,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val monetEnabled: Boolean = true,
    val paletteStyle: PaletteStyleOption = PaletteStyleOption.TonalSpot,
    val hideLauncherIcon: Boolean = false
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    POLLING,
    ERROR
}

data class NodeProbeResult(
    val nodeId: String,
    val reachable: Boolean,
    val rttMs: Long? = null,
    val serverVersion: String? = null,
    val capabilities: ServerCapabilitiesDto? = null,
    val error: String? = null,
    val probedAtMs: Long = System.currentTimeMillis()
)

data class EngineStatusSnapshot(
    val activeNodeId: String? = null,
    val activeNodeName: String? = null,
    val activeNodeType: ServerType? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val lastRttMs: Long? = null,
    val systemServerBound: Boolean = false,
    val systemUiBound: Boolean = false,
    val dozeWhitelisted: Boolean = false,
    val xposedApiActive: Boolean = false,
    val xposedFrameworkName: String = "",
    val scopeSystemUi: Boolean = false,
    val scopeSystem: Boolean = false,
    val lastSyncSummary: String = "",
    val lastSyncAtMs: Long = 0L,
    val lastError: String? = null,
    val serverCapabilities: ServerCapabilitiesDto? = null
)

interface SyncEngineController {
    val configFlow: StateFlow<AppConfig>
    val statusFlow: StateFlow<EngineStatusSnapshot>
    val historyFlow: StateFlow<List<LocalHistoryItem>>
    val availableTagsFlow: StateFlow<List<String>>
    val historyFilterFlow: StateFlow<HistoryFilterState>
    val probeResultsFlow: StateFlow<Map<String, NodeProbeResult>>

    fun updateConfig(transform: (AppConfig) -> AppConfig)
    fun upsertNode(node: ServerNode)
    fun deleteNode(nodeId: String)
    suspend fun selectActiveNode(nodeId: String)
    suspend fun setSelectorMode(mode: SelectorMode)
    suspend fun testNode(node: ServerNode): NodeProbeResult
    suspend fun probeAllNodes(): Map<String, NodeProbeResult>

    suspend fun pushCurrentClipboard(): Result<Unit>
    suspend fun pullLatestFromRemote(): Result<Unit>
    suspend fun syncNow(): Result<Unit>

    fun setHistoryFilter(filter: HistoryFilterState)
    suspend fun refreshHistory(resetPagination: Boolean = false): Result<Unit>
    suspend fun loadMoreHistory(): Result<Boolean>
    suspend fun updateHistoryItemMetadata(
        type: ProfileType,
        hashUpper: String,
        title: String? = null,
        tags: List<String>? = null,
        starred: Boolean? = null,
        pinned: Boolean? = null
    ): Result<Unit>
    suspend fun softDeleteHistoryItem(type: ProfileType, hashUpper: String): Result<Unit>
    suspend fun clearAllHistory(): Result<Unit>
    suspend fun copyHistoryItemToClipboard(type: ProfileType, hashUpper: String): Result<Unit>
    suspend fun downloadHistoryItemData(type: ProfileType, hashUpper: String): Result<String>

    suspend fun shareUrisOrText(text: String?, uriStrings: List<String>): Result<String>
    fun exportConfigJson(includePasswords: Boolean): String
    fun importConfigJson(json: String): Result<Unit>
    fun requestXposedScopes(): Boolean
}
