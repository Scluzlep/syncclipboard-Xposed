package com.scluzlep.syncclipboard.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scluzlep.syncclipboard.SyncClipboardApp
import com.scluzlep.syncclipboard.model.AppConfig
import com.scluzlep.syncclipboard.model.EngineStatusSnapshot
import com.scluzlep.syncclipboard.model.HistoryFilterState
import com.scluzlep.syncclipboard.model.LocalHistoryItem
import com.scluzlep.syncclipboard.model.NodeProbeResult
import com.scluzlep.syncclipboard.model.PaletteStyleOption
import com.scluzlep.syncclipboard.model.ProfileType
import com.scluzlep.syncclipboard.model.SelectorConfig
import com.scluzlep.syncclipboard.model.SelectorMode
import com.scluzlep.syncclipboard.model.ServerNode
import com.scluzlep.syncclipboard.model.SyncEngineController
import com.scluzlep.syncclipboard.model.ThemeMode
import com.scluzlep.syncclipboard.protocol.GroupArchiveCodec
import com.scluzlep.syncclipboard.service.XposedServiceBridge
import com.scluzlep.syncclipboard.service.XposedServiceState
import com.scluzlep.syncclipboard.tile.SyncToggleTileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class MainTab(val title: String, val subtitle: String) {
    OVERVIEW("概览", "运行状态与同步控制"),
    NODES("节点", "多节点与选路策略"),
    HISTORY("历史", "当前节点剪贴板历史"),
    SETTINGS("设置", "同步限制、外观与关于")
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val engine: SyncEngineController
        get() = (getApplication<Application>() as SyncClipboardApp).engine

    val configFlow: StateFlow<AppConfig> = engine.configFlow
    val statusFlow: StateFlow<EngineStatusSnapshot> = engine.statusFlow
    val historyFlow: StateFlow<List<LocalHistoryItem>> = engine.historyFlow
    val availableTagsFlow: StateFlow<List<String>> = engine.availableTagsFlow
    val historyFilterFlow: StateFlow<HistoryFilterState> = engine.historyFilterFlow
    val probeResultsFlow: StateFlow<Map<String, NodeProbeResult>> = engine.probeResultsFlow
    val xposedServiceState: StateFlow<XposedServiceState> = XposedServiceBridge.stateFlow

    private val _selectedTab = MutableStateFlow(MainTab.OVERVIEW)
    val selectedTab: StateFlow<MainTab> = _selectedTab.asStateFlow()

    private val _isScopeRequesting = MutableStateFlow(false)
    val isScopeRequesting: StateFlow<Boolean> = _isScopeRequesting.asStateFlow()

    private val _isHotReloading = MutableStateFlow(false)
    val isHotReloading: StateFlow<Boolean> = _isHotReloading.asStateFlow()

    private val _isSyncBusy = MutableStateFlow(false)
    val isSyncBusy: StateFlow<Boolean> = _isSyncBusy.asStateFlow()

    private val _isProbingAll = MutableStateFlow(false)
    val isProbingAll: StateFlow<Boolean> = _isProbingAll.asStateFlow()

    private val _isTestingNode = MutableStateFlow(false)
    val isTestingNode: StateFlow<Boolean> = _isTestingNode.asStateFlow()

    private val _testNodeResult = MutableStateFlow<NodeProbeResult?>(null)
    val testNodeResult: StateFlow<NodeProbeResult?> = _testNodeResult.asStateFlow()

    private val _isHistoryRefreshing = MutableStateFlow(false)
    val isHistoryRefreshing: StateFlow<Boolean> = _isHistoryRefreshing.asStateFlow()

    private val _isLoadingMoreHistory = MutableStateFlow(false)
    val isLoadingMoreHistory: StateFlow<Boolean> = _isLoadingMoreHistory.asStateFlow()

    private val _hasMoreHistory = MutableStateFlow(true)
    val hasMoreHistory: StateFlow<Boolean> = _hasMoreHistory.asStateFlow()

    private val _actionFeedback = MutableStateFlow("")
    val actionFeedback: StateFlow<String> = _actionFeedback.asStateFlow()

    fun selectTab(tab: MainTab) {
        _selectedTab.value = tab
    }

    fun clearFeedback() {
        _actionFeedback.value = ""
    }

    fun setFeedback(message: String) {
        _actionFeedback.value = message
    }

    fun refreshServiceStatus() {
        XposedServiceBridge.refreshStatus()
    }

    fun requestXposedScopes() {
        if (_isScopeRequesting.value) return
        _isScopeRequesting.value = true
        _actionFeedback.value = "正在向 LSPosed 申请作用域…"
        val dispatched = XposedServiceBridge.requestScopes { _, message ->
            _isScopeRequesting.value = false
            _actionFeedback.value = message
        }
        if (!dispatched) {
            // Also notify engine in case it exposes an alternative trigger
            engine.requestXposedScopes()
            _isScopeRequesting.value = false
        }
    }

    fun hotReloadModule() {
        if (_isHotReloading.value) return
        _isHotReloading.value = true
        _actionFeedback.value = "正在热重载目标进程…"
        XposedServiceBridge.hotReloadAll { message ->
            _isHotReloading.value = false
            _actionFeedback.value = message
        }
    }

    fun pushCurrentClipboard() {
        if (_isSyncBusy.value) return
        viewModelScope.launch {
            _isSyncBusy.value = true
            val res = engine.pushCurrentClipboard()
            _actionFeedback.value = res.fold(
                onSuccess = { "已推送当前剪贴板" },
                onFailure = { err -> "推送失败：${err.message ?: err.javaClass.simpleName}" }
            )
            _isSyncBusy.value = false
        }
    }

    fun pullLatestFromRemote() {
        if (_isSyncBusy.value) return
        viewModelScope.launch {
            _isSyncBusy.value = true
            val res = engine.pullLatestFromRemote()
            _actionFeedback.value = res.fold(
                onSuccess = { "已拉取远端最新剪贴板" },
                onFailure = { err -> "拉取失败：${err.message ?: err.javaClass.simpleName}" }
            )
            _isSyncBusy.value = false
        }
    }

    fun syncNow() {
        if (_isSyncBusy.value) return
        viewModelScope.launch {
            _isSyncBusy.value = true
            val res = engine.syncNow()
            _actionFeedback.value = res.fold(
                onSuccess = { "同步完成" },
                onFailure = { err -> "同步失败：${err.message ?: err.javaClass.simpleName}" }
            )
            _isSyncBusy.value = false
        }
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        engine.updateConfig { it.copy(autoSyncEnabled = enabled) }
        runCatching { SyncToggleTileService.requestTileUpdate(appContext) }
    }

    fun setAutoUploadEnabled(enabled: Boolean) {
        engine.updateConfig { it.copy(autoUploadEnabled = enabled) }
    }

    fun setAutoDownloadEnabled(enabled: Boolean) {
        engine.updateConfig { it.copy(autoDownloadEnabled = enabled) }
    }

    fun setOtpCaptureEnabled(enabled: Boolean) {
        engine.updateConfig { it.copy(otpCaptureEnabled = enabled) }
    }

    fun setWriteOtpToClipboard(enabled: Boolean) {
        engine.updateConfig { it.copy(writeOtpToClipboard = enabled) }
    }

    fun setWriteImageToClipboard(enabled: Boolean) {
        engine.updateConfig { it.copy(writeImageToClipboard = enabled) }
    }

    fun setAutoDownloadMaxSizeMb(sizeMb: Int) {
        engine.updateConfig { it.copy(autoDownloadMaxSizeMb = sizeMb.coerceIn(1, 2048)) }
    }

    fun setScreenOffDisconnectDelaySec(delaySec: Int) {
        engine.updateConfig { it.copy(screenOffDisconnectDelaySec = delaySec.coerceAtLeast(0)) }
    }

    fun setThemeMode(mode: ThemeMode) {
        engine.updateConfig { it.copy(themeMode = mode) }
    }

    fun setMonetEnabled(enabled: Boolean) {
        engine.updateConfig { it.copy(monetEnabled = enabled) }
    }

    fun setPaletteStyle(style: PaletteStyleOption) {
        engine.updateConfig { it.copy(paletteStyle = style) }
    }

    fun setHideLauncherIcon(hidden: Boolean) {
        engine.updateConfig { it.copy(hideLauncherIcon = hidden) }
        applyLauncherIconVisibility(hidden)
        _actionFeedback.value = if (hidden) {
            "已隐藏桌面图标（可从 LSPosed 或快捷磁贴进入）"
        } else {
            "已恢复显示桌面图标"
        }
    }

    private fun applyLauncherIconVisibility(hidden: Boolean) {
        SyncClipboardApp.setLauncherIconHidden(appContext, hidden)
    }

    fun setSelectorMode(mode: SelectorMode) {
        viewModelScope.launch {
            engine.setSelectorMode(mode)
        }
    }

    fun updateSelectorConfig(transform: (SelectorConfig) -> SelectorConfig) {
        engine.updateConfig { it.copy(selector = transform(it.selector)) }
    }

    fun selectActiveNode(nodeId: String) {
        viewModelScope.launch {
            engine.selectActiveNode(nodeId)
            _actionFeedback.value = "已请求切换活跃节点"
        }
    }

    fun upsertNode(node: ServerNode) {
        engine.upsertNode(node)
        _actionFeedback.value = "已保存节点「${node.name.ifBlank { node.baseUrl }}」"
    }

    fun deleteNode(nodeId: String) {
        engine.deleteNode(nodeId)
        _actionFeedback.value = "已删除节点"
    }

    fun probeAllNodes() {
        if (_isProbingAll.value) return
        viewModelScope.launch {
            _isProbingAll.value = true
            val results = engine.probeAllNodes()
            val reachableCount = results.values.count { it.reachable }
            _actionFeedback.value = "测速完成：$reachableCount / ${results.size} 个节点可达"
            _isProbingAll.value = false
        }
    }

    fun testNode(node: ServerNode) {
        if (_isTestingNode.value) return
        viewModelScope.launch {
            _isTestingNode.value = true
            _testNodeResult.value = null
            _testNodeResult.value = engine.testNode(node)
            _isTestingNode.value = false
        }
    }

    fun clearTestNodeResult() {
        _testNodeResult.value = null
    }

    fun setHistoryFilter(filter: HistoryFilterState) {
        engine.setHistoryFilter(filter)
    }

    fun refreshHistory(resetPagination: Boolean = false) {
        if (_isHistoryRefreshing.value) return
        viewModelScope.launch {
            _isHistoryRefreshing.value = true
            val res = engine.refreshHistory(resetPagination)
            if (resetPagination) {
                _hasMoreHistory.value = true
            }
            res.onFailure { err ->
                _actionFeedback.value = "刷新历史失败：${err.message ?: err.javaClass.simpleName}"
            }
            _isHistoryRefreshing.value = false
        }
    }

    fun loadMoreHistory() {
        if (_isLoadingMoreHistory.value) return
        viewModelScope.launch {
            _isLoadingMoreHistory.value = true
            val res = engine.loadMoreHistory()
            res.onSuccess { hasMore ->
                _hasMoreHistory.value = hasMore
            }.onFailure { err ->
                _actionFeedback.value = "加载更多失败：${err.message ?: err.javaClass.simpleName}"
            }
            _isLoadingMoreHistory.value = false
        }
    }

    fun updateHistoryItemMetadata(
        type: ProfileType,
        hashUpper: String,
        title: String? = null,
        tags: List<String>? = null,
        starred: Boolean? = null,
        pinned: Boolean? = null,
        onComplete: ((Result<Unit>) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val res = engine.updateHistoryItemMetadata(
                type = type,
                hashUpper = hashUpper,
                title = title,
                tags = tags,
                starred = starred,
                pinned = pinned
            )
            _actionFeedback.value = res.fold(
                onSuccess = { "已更新历史记录属性" },
                onFailure = { err -> "更新失败：${err.message ?: err.javaClass.simpleName}" }
            )
            onComplete?.invoke(res)
        }
    }

    fun softDeleteHistoryItem(
        type: ProfileType,
        hashUpper: String,
        onComplete: ((Result<Unit>) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val res = engine.softDeleteHistoryItem(type, hashUpper)
            _actionFeedback.value = res.fold(
                onSuccess = { "已删除历史记录" },
                onFailure = { err -> "删除失败：${err.message ?: err.javaClass.simpleName}" }
            )
            onComplete?.invoke(res)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            val res = engine.clearAllHistory()
            _actionFeedback.value = res.fold(
                onSuccess = { "已清空当前节点历史" },
                onFailure = { err -> "清空失败：${err.message ?: err.javaClass.simpleName}" }
            )
        }
    }

    fun copyHistoryItemToClipboard(type: ProfileType, hashUpper: String) {
        viewModelScope.launch {
            val res = engine.copyHistoryItemToClipboard(type, hashUpper)
            _actionFeedback.value = res.fold(
                onSuccess = { "已复制到系统剪贴板" },
                onFailure = { err -> "复制失败：${err.message ?: err.javaClass.simpleName}" }
            )
        }
    }

    fun downloadHistoryItemData(
        type: ProfileType,
        hashUpper: String,
        onDownloaded: (Result<File>) -> Unit
    ) {
        viewModelScope.launch {
            val res = engine.downloadHistoryItemData(type, hashUpper)
            res.onFailure { err ->
                _actionFeedback.value = "下载失败：${err.message ?: err.javaClass.simpleName}"
            }
            onDownloaded(res.map { File(it) })
        }
    }

    fun extractGroupArchive(
        zipFile: File,
        expectedHashUpper: String,
        onResult: (Result<GroupArchiveCodec.GroupUnpackResult>) -> Unit
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val safeDir = File(appContext.cacheDir, "group_extract_${expectedHashUpper.take(24)}")
                    if (safeDir.exists()) {
                        safeDir.deleteRecursively()
                    }
                    GroupArchiveCodec.unpackGroup(
                        zipFile = zipFile,
                        targetDir = safeDir,
                        expectedProfileHash = expectedHashUpper
                    )
                }
            }
            result.onFailure { err ->
                _actionFeedback.value = "解包失败：${err.message ?: err.javaClass.simpleName}"
            }
            onResult(result)
        }
    }

    fun exportConfigJson(includePasswords: Boolean): String {
        return engine.exportConfigJson(includePasswords)
    }

    fun importConfigJson(json: String) {
        val res = engine.importConfigJson(json)
        res.onSuccess {
            applyLauncherIconVisibility(configFlow.value.hideLauncherIcon)
            _actionFeedback.value = "配置导入成功"
        }.onFailure { err ->
            _actionFeedback.value = "配置导入失败：${err.message ?: err.javaClass.simpleName}"
        }
    }
}
