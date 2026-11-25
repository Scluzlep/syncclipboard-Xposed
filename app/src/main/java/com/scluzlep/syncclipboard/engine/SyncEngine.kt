package com.scluzlep.syncclipboard.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.scluzlep.syncclipboard.config.ConfigStore
import com.scluzlep.syncclipboard.history.HistoryIsoTime
import com.scluzlep.syncclipboard.history.HistoryRepository
import com.scluzlep.syncclipboard.history.NodeStorageKeys
import com.scluzlep.syncclipboard.history.PendingUploadItem
import com.scluzlep.syncclipboard.history.SyncCursorRecord
import com.scluzlep.syncclipboard.ipc.ISyncEngineBridge
import com.scluzlep.syncclipboard.ipc.ISystemUiClipboardClient
import com.scluzlep.syncclipboard.model.AppConfig
import com.scluzlep.syncclipboard.model.ConnectionState
import com.scluzlep.syncclipboard.model.EngineStatusSnapshot
import com.scluzlep.syncclipboard.model.HistoryFilterState
import com.scluzlep.syncclipboard.model.HistoryQueryParams
import com.scluzlep.syncclipboard.model.HistoryRecordDto
import com.scluzlep.syncclipboard.model.HistoryRecordUpdateDto
import com.scluzlep.syncclipboard.model.LocalHistoryItem
import com.scluzlep.syncclipboard.model.NodeProbeResult
import com.scluzlep.syncclipboard.model.ProfileDto
import com.scluzlep.syncclipboard.model.ProfileType
import com.scluzlep.syncclipboard.model.ProfileTypeFilter
import com.scluzlep.syncclipboard.model.SelectorMode
import com.scluzlep.syncclipboard.model.ServerCapabilitiesDto
import com.scluzlep.syncclipboard.model.ServerNode
import com.scluzlep.syncclipboard.model.ServerType
import com.scluzlep.syncclipboard.model.SyncEngineController
import com.scluzlep.syncclipboard.protocol.ClipboardTransportClient
import com.scluzlep.syncclipboard.protocol.GroupArchiveCodec
import com.scluzlep.syncclipboard.protocol.OfficialClipboardClient
import com.scluzlep.syncclipboard.protocol.ProfileHashUtils
import com.scluzlep.syncclipboard.protocol.RemoteConflictException
import com.scluzlep.syncclipboard.protocol.RemoteNotFoundException
import com.scluzlep.syncclipboard.protocol.SignalRClient
import com.scluzlep.syncclipboard.protocol.TlsHttpFactory
import com.scluzlep.syncclipboard.protocol.WebDavClipboardClient
import com.scluzlep.syncclipboard.selector.NetworkMonitor
import com.scluzlep.syncclipboard.selector.NetworkSnapshot
import com.scluzlep.syncclipboard.selector.NodeProber
import com.scluzlep.syncclipboard.selector.NodeSelector
import com.scluzlep.syncclipboard.service.XposedServiceBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

interface NodeSessionTransport : AutoCloseable {
    val client: ClipboardTransportClient
    val officialClient: OfficialClipboardClient?
    val webDavClient: WebDavClipboardClient?

    suspend fun connectRealtime(
        scope: CoroutineScope,
        onConnected: () -> Unit,
        onProfileChanged: (ProfileDto) -> Unit,
        onHistoryChanged: (HistoryRecordDto) -> Unit,
        onDisconnected: (Throwable?) -> Unit
    )

    suspend fun disconnectRealtime()
}

fun interface NodeTransportFactory {
    fun create(node: ServerNode): NodeSessionTransport
}

class DefaultNodeTransportFactory : NodeTransportFactory {
    override fun create(node: ServerNode): NodeSessionTransport {
        val httpClient = TlsHttpFactory.createClient(node)
        return when (node.serverType) {
            ServerType.OFFICIAL -> {
                val official = OfficialClipboardClient(node, httpClient, ownsClient = false)
                object : NodeSessionTransport {
                    override val client: ClipboardTransportClient = official
                    override val officialClient: OfficialClipboardClient = official
                    override val webDavClient: WebDavClipboardClient? = null

                    @Volatile
                    private var signalR: SignalRClient? = null
                    @Volatile
                    private var reconnectJob: Job? = null
                    private val closed = AtomicBoolean(false)

                    override suspend fun connectRealtime(
                        scope: CoroutineScope,
                        onConnected: () -> Unit,
                        onProfileChanged: (ProfileDto) -> Unit,
                        onHistoryChanged: (HistoryRecordDto) -> Unit,
                        onDisconnected: (Throwable?) -> Unit
                    ) {
                        if (closed.get()) return
                        disconnectRealtime()
                        val sr = SignalRClient(
                            node = node,
                            httpClient = httpClient,
                            scope = scope,
                            listener = object : SignalRClient.Listener {
                                override fun onConnected() = onConnected()
                                override fun onProfileChanged(profile: ProfileDto) = onProfileChanged(profile)
                                override fun onHistoryChanged(record: HistoryRecordDto) = onHistoryChanged(record)
                                override fun onDisconnected(error: Throwable?) {
                                    onDisconnected(error)
                                    if (!closed.get() && scope.isActive) {
                                        reconnectJob?.cancel()
                                        reconnectJob = scope.launch(Dispatchers.IO) {
                                            delay(RECONNECT_DELAY_MS)
                                            if (!closed.get() && isActive) {
                                                runCatching {
                                                    connectRealtime(
                                                        scope,
                                                        onConnected,
                                                        onProfileChanged,
                                                        onHistoryChanged,
                                                        onDisconnected
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        )
                        signalR = sr
                        sr.connect()
                    }

                    override suspend fun disconnectRealtime() {
                        reconnectJob?.cancel()
                        reconnectJob = null
                        signalR?.disconnect()
                        signalR = null
                    }

                    override fun close() {
                        if (closed.compareAndSet(false, true)) {
                            reconnectJob?.cancel()
                            signalR?.disconnect()
                            signalR = null
                            official.close()
                            httpClient.dispatcher.executorService.shutdown()
                            httpClient.connectionPool.evictAll()
                        }
                    }
                }
            }

            ServerType.WEBDAV -> {
                val webdav = WebDavClipboardClient(node, httpClient, ownsClient = false)
                object : NodeSessionTransport {
                    override val client: ClipboardTransportClient = webdav
                    override val officialClient: OfficialClipboardClient? = null
                    override val webDavClient: WebDavClipboardClient = webdav

                    @Volatile
                    private var pollJob: Job? = null
                    private val closed = AtomicBoolean(false)

                    override suspend fun connectRealtime(
                        scope: CoroutineScope,
                        onConnected: () -> Unit,
                        onProfileChanged: (ProfileDto) -> Unit,
                        onHistoryChanged: (HistoryRecordDto) -> Unit,
                        onDisconnected: (Throwable?) -> Unit
                    ) {
                        if (closed.get()) return
                        disconnectRealtime()
                        onConnected()
                        pollJob = scope.launch(Dispatchers.IO) {
                            while (isActive && !closed.get()) {
                                delay(WEBDAV_POLL_INTERVAL_MS)
                                runCatching { webdav.getProfile() }
                                    .onSuccess { snapshot ->
                                        onProfileChanged(snapshot.profile)
                                    }
                                    .onFailure { err ->
                                        onDisconnected(err)
                                    }
                            }
                        }
                    }

                    override suspend fun disconnectRealtime() {
                        pollJob?.cancel()
                        pollJob = null
                    }

                    override fun close() {
                        if (closed.compareAndSet(false, true)) {
                            pollJob?.cancel()
                            pollJob = null
                            webdav.close()
                            httpClient.dispatcher.executorService.shutdown()
                            httpClient.connectionPool.evictAll()
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val RECONNECT_DELAY_MS = 10_000L
        private const val WEBDAV_POLL_INTERVAL_MS = 5_000L
    }
}

/**
 * Core single-active multi-node SyncClipboard v3.3.4 engine + Binder IPC bridge.
 */
class SyncEngine(
    private val context: Context,
    private val configStore: ConfigStore,
    private val historyStore: HistoryRepository,
    private val networkMonitor: NetworkMonitor,
    private val nodeProber: NodeProber = NodeProber(),
    private val nodeSelector: NodeSelector = NodeSelector(),
    private val transportFactory: NodeTransportFactory = DefaultNodeTransportFactory(),
    private val recentHashDedup: RecentHashDedup = RecentHashDedup(),
    private val engineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onHideLauncherIconChanged: ((Boolean) -> Unit)? = null
) : SyncEngineController {

    private class NodeSession(
        val node: ServerNode,
        val nodeStorageKey: String,
        val transport: NodeSessionTransport,
        val sessionScope: CoroutineScope,
        private val activeCounter: AtomicInteger
    ) {
        private val closed = AtomicBoolean(false)

        @Volatile
        var capabilities: ServerCapabilitiesDto = ServerCapabilitiesDto()

        suspend fun closeAndAwait() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { transport.disconnectRealtime() }
            sessionScope.cancel()
            runCatching {
                withTimeoutOrNull(2_000L) {
                    sessionScope.coroutineContext[Job]?.join()
                }
            }
            runCatching { transport.close() }
            activeCounter.decrementAndGet()
        }
    }

    private val powerManager: PowerManager? =
        runCatching { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }.getOrNull()

    private val sessionMutex = Mutex()
    private val transferMutex = Mutex()
    private val _activeConnectionCount = AtomicInteger(0)
    private val _peakConnectionCount = AtomicInteger(0)

    val activeConnectionCount: Int get() = _activeConnectionCount.get()
    val peakConnectionCount: Int get() = _peakConnectionCount.get()

    @Volatile
    private var activeSession: NodeSession? = null

    @Volatile
    private var clipboardClient: ISystemUiClipboardClient? = null

    @Volatile
    private var systemServerToken: IBinder? = null

    @Volatile
    private var lastLocalWrittenHash: String? = null

    @Volatile
    private var lastLocalWriteAtMs: Long = 0L

    @Volatile
    private var lastInFlightUploadHash: String? = null

    @Volatile
    private var currentHistoryPage: Int = 1

    @Volatile
    private var screenOffJob: Job? = null

    @Volatile
    private var lastHideLauncherIcon: Boolean = configStore.current.hideLauncherIcon

    private val started = AtomicBoolean(false)

    private val _statusFlow = MutableStateFlow(EngineStatusSnapshot())
    override val statusFlow: StateFlow<EngineStatusSnapshot> = _statusFlow.asStateFlow()

    private val _probeResultsFlow = MutableStateFlow<Map<String, NodeProbeResult>>(emptyMap())
    override val probeResultsFlow: StateFlow<Map<String, NodeProbeResult>> = _probeResultsFlow.asStateFlow()

    override val configFlow: StateFlow<AppConfig> = configStore.configFlow
    override val historyFlow: StateFlow<List<LocalHistoryItem>> = historyStore.historyFlow
    override val availableTagsFlow: StateFlow<List<String>> = historyStore.availableTagsFlow
    override val historyFilterFlow: StateFlow<HistoryFilterState> = historyStore.filterFlow

    val engineBridge: ISyncEngineBridge.Stub = object : ISyncEngineBridge.Stub() {
        override fun registerSystemServerHeartbeat(token: IBinder?, dozeWhitelisted: Boolean) {
            systemServerToken = token
            runCatching {
                token?.linkToDeath(
                    {
                        systemServerToken = null
                        updateStatus { it.copy(systemServerBound = false) }
                    },
                    0
                )
            }
            updateStatus {
                it.copy(
                    systemServerBound = true,
                    dozeWhitelisted = dozeWhitelisted
                )
            }
        }

        override fun registerClipboardClient(client: ISystemUiClipboardClient?) {
            clipboardClient = client
            runCatching {
                client?.asBinder()?.linkToDeath(
                    {
                        if (clipboardClient?.asBinder() == client.asBinder()) {
                            clipboardClient = null
                            updateStatus { it.copy(systemUiBound = false) }
                        }
                    },
                    0
                )
            }
            updateStatus { it.copy(systemUiBound = client != null) }
        }

        override fun unregisterClipboardClient(client: ISystemUiClipboardClient?) {
            if (client == null || clipboardClient?.asBinder() == client.asBinder()) {
                clipboardClient = null
                updateStatus { it.copy(systemUiBound = false) }
            }
        }

        override fun onClipboardTextChanged(text: String?, isSensitive: Boolean, timestampMs: Long) {
            if (text.isNullOrEmpty()) return
            engineScope.launch {
                handleLocalClipboardText(text, isSensitive)
            }
        }

        override fun onClipboardFilesChanged(
            fds: MutableList<ParcelFileDescriptor>?,
            fileNames: MutableList<String>?,
            mimeTypes: MutableList<String>?,
            fallbackText: String?,
            timestampMs: Long
        ) {
            val copiedFiles = mutableListOf<Pair<File, String>>()
            val tempDir = File(context.cacheDir, "clipboard_in/${UUID.randomUUID()}").apply { mkdirs() }
            try {
                if (fds != null) {
                    for (i in fds.indices) {
                        val pfd = fds[i]
                        val rawName = fileNames?.getOrNull(i).orEmpty()
                        val mime = mimeTypes?.getOrNull(i).orEmpty()
                        val safeName = resolveInboundFileName(rawName, mime, i)
                        val dest = File(tempDir, safeName)
                        runCatching {
                            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                                FileOutputStream(dest).use { output ->
                                    input.copyTo(output, bufferSize = 81920)
                                }
                            }
                            if (dest.exists() && dest.length() > 0L) {
                                copiedFiles += dest to mime
                            }
                        }
                    }
                }
            } finally {
                fds?.forEach { runCatching { it.close() } }
            }

            engineScope.launch {
                try {
                    if (copiedFiles.isEmpty()) {
                        if (!fallbackText.isNullOrEmpty()) {
                            handleLocalClipboardText(fallbackText, isSensitive = false)
                        }
                    } else {
                        handleLocalClipboardFiles(copiedFiles)
                    }
                } finally {
                    tempDir.deleteRecursively()
                }
            }
        }

        override fun onOtpNotificationCandidate(sourcePackage: String?, body: String?, timestampMs: Long) {
            if (body.isNullOrBlank()) return
            engineScope.launch {
                handleOtpCandidate(sourcePackage.orEmpty(), body)
            }
        }
    }

    @Suppress("OPT_IN_USAGE")
    fun start() {
        if (!started.compareAndSet(false, true)) return

        networkMonitor.start()
        registerScreenReceiver()

        engineScope.launch {
            XposedServiceBridge.stateFlow.collectLatest { xs ->
                updateStatus {
                    it.copy(
                        xposedApiActive = xs.isServiceBound,
                        xposedFrameworkName = xs.frameworkName,
                        scopeSystemUi = xs.scopeSystemUi,
                        scopeSystem = xs.scopeSystem
                    )
                }
            }
        }

        engineScope.launch {
            configStore.configFlow.collectLatest { cfg ->
                if (cfg.hideLauncherIcon != lastHideLauncherIcon) {
                    lastHideLauncherIcon = cfg.hideLauncherIcon
                    onHideLauncherIconChanged?.invoke(cfg.hideLauncherIcon)
                }
                reconcileActiveNode(forceReconnect = false)
            }
        }

        engineScope.launch {
            networkMonitor.networkFlow
                .debounce(300L)
                .collectLatest { snapshot ->
                    handleNetworkChanged(snapshot)
                }
        }

        engineScope.launch {
            while (isActive) {
                val cfg = configStore.current
                val intervalSec = cfg.selector.probeIntervalSec.coerceIn(10, 3600)
                delay(intervalSec * 1000L)
                if (cfg.nodes.any { it.enabled } &&
                    (cfg.selector.mode == SelectorMode.URL_TEST ||
                        cfg.selector.mode == SelectorMode.FALLBACK ||
                        cfg.selector.mode == SelectorMode.RULE_AUTO)
                ) {
                    runCatching { probeAllNodes() }
                    reconcileActiveNode(forceReconnect = false)
                }
            }
        }
    }

    fun shutdown() {
        engineScope.launch {
            sessionMutex.withLock {
                activeSession?.closeAndAwait()
                activeSession = null
            }
        }
        networkMonitor.stop()
        engineScope.cancel()
    }

    override fun updateConfig(transform: (AppConfig) -> AppConfig) {
        configStore.update(transform)
    }

    override fun upsertNode(node: ServerNode) {
        configStore.upsertNode(node)
    }

    override fun deleteNode(nodeId: String) {
        configStore.deleteNode(nodeId)
    }

    override suspend fun selectActiveNode(nodeId: String) {
        val node = configStore.current.nodes.firstOrNull { it.id == nodeId } ?: return
        if (configStore.current.selector.mode == SelectorMode.MANUAL) {
            configStore.update { cfg ->
                cfg.copy(selector = cfg.selector.copy(manualNodeId = node.id))
            }
        }
        activateNode(node)
    }

    override suspend fun setSelectorMode(mode: SelectorMode) {
        configStore.update { cfg ->
            cfg.copy(selector = cfg.selector.copy(mode = mode))
        }
        reconcileActiveNode(forceReconnect = false)
    }

    override suspend fun testNode(node: ServerNode): NodeProbeResult {
        val res = nodeProber.probeNode(node)
        _probeResultsFlow.value = _probeResultsFlow.value + (node.id to res)
        return res
    }

    override suspend fun probeAllNodes(): Map<String, NodeProbeResult> {
        val nodes = configStore.current.nodes
        val results = nodeProber.probeAll(nodes)
        _probeResultsFlow.value = results
        return results
    }

    override suspend fun pushCurrentClipboard(): Result<Unit> = runCatching {
        val client = clipboardClient
        if (client != null) {
            client.requestCurrentClipboardPush()
        } else {
            throw IllegalStateException("SystemUI clipboard client is not bound")
        }
    }

    override suspend fun pullLatestFromRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val session = activeSession ?: throw IllegalStateException("No active server node")
            withBoundedWakeLock("PullLatest", MAX_WAKELOCK_MS) {
                reconcileRemoteClipboard(session, forceApply = true)
            }
        }
    }

    override suspend fun syncNow(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val session = activeSession ?: throw IllegalStateException("No active server node")
            withBoundedWakeLock("SyncNow", MAX_WAKELOCK_MS) {
                drainUploadQueue(session)
                reconcileRemoteClipboard(session, forceApply = false)
                refreshHistory(resetPagination = false).getOrThrow()
            }
        }
    }

    override fun setHistoryFilter(filter: HistoryFilterState) {
        historyStore.setFilter(filter)
    }

    override suspend fun refreshHistory(resetPagination: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val session = activeSession ?: return@runCatching
            val official = session.transport.officialClient ?: return@runCatching
            if (!session.capabilities.historySync) return@runCatching

            checkClockSkew(official)
            if (resetPagination) {
                currentHistoryPage = 1
            }
            val cursor = historyStore.getCursor(session.nodeStorageKey)
            val records = official.queryHistory(
                HistoryQueryParams(
                    page = 1,
                    modifiedAfter = if (resetPagination) null else cursor.lastModifiedCursor,
                    types = ProfileTypeFilter.ALL
                )
            )
            historyStore.mergeRemoteRecords(session.nodeStorageKey, records, session.capabilities)
            val newestModified = records.maxOfOrNull { it.lastModified }
            if (!newestModified.isNullOrBlank()) {
                historyStore.saveCursor(
                    historyStore.getCursor(session.nodeStorageKey).copy(
                        lastSyncTimeMs = System.currentTimeMillis(),
                        lastModifiedCursor = newestModified
                    )
                )
            }
            backfillMissingMetadata(session)
        }
    }

    override suspend fun loadMoreHistory(): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val session = activeSession ?: return@runCatching false
            val official = session.transport.officialClient ?: return@runCatching false
            if (!session.capabilities.historySync) return@runCatching false

            val nextPage = currentHistoryPage + 1
            val records = official.queryHistory(
                HistoryQueryParams(
                    page = nextPage,
                    types = ProfileTypeFilter.ALL
                )
            )
            if (records.isNotEmpty()) {
                currentHistoryPage = nextPage
                historyStore.mergeRemoteRecords(session.nodeStorageKey, records, session.capabilities)
            }
            records.size >= HISTORY_PAGE_SIZE
        }
    }

    override suspend fun updateHistoryItemMetadata(
        type: ProfileType,
        hashUpper: String,
        title: String?,
        tags: List<String>?,
        starred: Boolean?,
        pinned: Boolean?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val session = activeSession ?: throw IllegalStateException("No active server node")
            val normHash = hashUpper.uppercase(Locale.ROOT)
            val existing = historyStore.getItem(session.nodeStorageKey, type, normHash)
                ?: throw IllegalArgumentException("History item not found locally")

            val normTitle = title?.let { ProfileHashUtils.normalizeTitle(it) }
            val normTags = tags?.let { ProfileHashUtils.normalizeTags(it) }
            val nowMs = System.currentTimeMillis()
            val nextVersion = existing.version + 1
            val nowIso = HistoryIsoTime.formatUtcIso(nowMs)

            val updatedLocal = existing.copy(
                title = normTitle ?: existing.title,
                tags = normTags ?: existing.tags,
                starred = starred ?: existing.starred,
                pinned = pinned ?: existing.pinned,
                version = nextVersion,
                lastModifiedEpochMs = nowMs,
                isDeleted = false,
                needsMetadataBackfill = false
            )
            historyStore.upsertItem(updatedLocal)

            val official = session.transport.officialClient ?: return@runCatching
            if (!session.capabilities.historySync) return@runCatching

            val updateDto = HistoryRecordUpdateDto(
                tags = normTags,
                title = normTitle,
                starred = starred,
                pinned = pinned,
                isDelete = if (existing.isDeleted) false else null,
                version = nextVersion,
                lastModified = nowIso
            )
            try {
                val resp = official.patchHistory(type, normHash, updateDto)
                if (resp != null) {
                    historyStore.applyServerUpdate(session.nodeStorageKey, type, normHash, resp, session.capabilities)
                }
            } catch (conflict: RemoteConflictException) {
                conflict.serverUpdate?.let { srv ->
                    historyStore.applyServerUpdate(session.nodeStorageKey, type, normHash, srv, session.capabilities)
                }
            } catch (_: RemoteNotFoundException) {
                // v3.3.4 deleted-with-data fallback: re-upload full record via POST /api/history
                reuploadHistoryRecord(session, official, updatedLocal)
            }
            Unit
        }
    }

    override suspend fun softDeleteHistoryItem(type: ProfileType, hashUpper: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val session = activeSession ?: throw IllegalStateException("No active server node")
                val normHash = hashUpper.uppercase(Locale.ROOT)
                val existing = historyStore.getItem(session.nodeStorageKey, type, normHash)
                    ?: return@runCatching

                val nowMs = System.currentTimeMillis()
                val nextVersion = existing.version + 1
                val updated = existing.copy(
                    isDeleted = true,
                    version = nextVersion,
                    lastModifiedEpochMs = nowMs
                )
                historyStore.upsertItem(updated)

                val official = session.transport.officialClient ?: return@runCatching
                if (!session.capabilities.historySync) return@runCatching

                val dto = HistoryRecordUpdateDto(
                    isDelete = true,
                    version = nextVersion,
                    lastModified = HistoryIsoTime.formatUtcIso(nowMs)
                )
                try {
                    official.patchHistory(type, normHash, dto)
                } catch (_: RemoteNotFoundException) {
                    // Already absent on server
                } catch (conflict: RemoteConflictException) {
                    conflict.serverUpdate?.let { srv ->
                        historyStore.applyServerUpdate(session.nodeStorageKey, type, normHash, srv, session.capabilities)
                    }
                }
                Unit
            }
        }

    override suspend fun clearAllHistory(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val session = activeSession ?: throw IllegalStateException("No active server node")
            session.transport.officialClient?.let { official ->
                if (session.capabilities.historySync) {
                    official.clearAllHistory()
                }
            }
            historyStore.clearNodeHistory(session.nodeStorageKey)
            historyStore.saveCursor(
                historyStore.getCursor(session.nodeStorageKey).copy(
                    lastModifiedCursor = null
                )
            )
        }
    }

    override suspend fun copyHistoryItemToClipboard(type: ProfileType, hashUpper: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val session = activeSession ?: throw IllegalStateException("No active server node")
                val normHash = hashUpper.uppercase(Locale.ROOT)
                val item = historyStore.getItem(session.nodeStorageKey, type, normHash)
                    ?: throw IllegalArgumentException("History item not found")
                val effectiveType = ProfileHashUtils.resolveEffectiveProfileType(item.type, item.text)

                when (effectiveType) {
                    ProfileType.Text -> {
                        val fullText = if (!item.hasData) {
                            item.text
                        } else {
                            val localPath = item.localDataPath?.takeIf { File(it).exists() }
                                ?: downloadHistoryItemData(type, normHash).getOrThrow()
                            ProfileHashUtils.readUtf8NoBom(File(localPath))
                        }
                        writeTextToSystemUi(fullText, sensitive = false, suppressOverlay = false)
                    }

                    ProfileType.Image, ProfileType.File -> {
                        val localPath = item.localDataPath?.takeIf { File(it).exists() }
                            ?: downloadHistoryItemData(type, normHash).getOrThrow()
                        val file = File(localPath)
                        writeFileUriToSystemUi(file, guessMimeType(file.name), suppressOverlay = false)
                    }

                    ProfileType.Group -> {
                        val localPath = item.localDataPath?.takeIf { File(it).exists() }
                            ?: downloadHistoryItemData(type, normHash).getOrThrow()
                        val file = File(localPath)
                        if (file.isFile) {
                            writeFileUriToSystemUi(file, "application/zip", suppressOverlay = false)
                        } else {
                            writeTextToSystemUi(item.text, sensitive = false, suppressOverlay = false)
                        }
                    }

                    else -> writeTextToSystemUi(item.text, sensitive = false, suppressOverlay = false)
                }
            }
        }

    override suspend fun downloadHistoryItemData(type: ProfileType, hashUpper: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val session = activeSession ?: throw IllegalStateException("No active server node")
                val normHash = hashUpper.uppercase(Locale.ROOT)
                val item = historyStore.getItem(session.nodeStorageKey, type, normHash)
                    ?: throw IllegalArgumentException("History item not found")

                val downloadsDir = nodeDownloadsDir(session.nodeStorageKey)
                val baseName = when (type) {
                    ProfileType.Text -> "Text_${normHash.take(12)}.txt"
                    ProfileType.Group -> "Group_${normHash.take(12)}.zip"
                    else -> ProfileHashUtils.safeFileName(item.text.ifBlank { "${type.name}_${normHash.take(12)}" })
                }
                val destFile = File(downloadsDir, baseName)
                val official = session.transport.officialClient
                val sha = if (official != null && session.capabilities.historySync) {
                    official.downloadHistoryData(type, normHash, destFile, item.transferDataHash)
                } else {
                    val dataName = item.text.ifBlank { baseName }
                    session.transport.client.downloadFile(dataName, destFile)
                    ProfileHashUtils.sha256FileHex(destFile)
                }
                historyStore.updateLocalDataPath(session.nodeStorageKey, type, normHash, destFile.absolutePath, sha)
                destFile.absolutePath
            }
        }

    override suspend fun shareUrisOrText(text: String?, uriStrings: List<String>): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val session = activeSession ?: throw IllegalStateException("当前无活跃同步节点")
                if (uriStrings.isEmpty()) {
                    val clean = text?.takeIf { it.isNotEmpty() }
                        ?: throw IllegalArgumentException("没有可分享的内容")
                    withBoundedWakeLock("ShareText", MAX_WAKELOCK_MS) {
                        uploadTextInternal(session, clean)
                    }
                    return@runCatching "已上传文本 (${clean.length} 字符)"
                }

                val tempDir = File(context.cacheDir, "share_in/${UUID.randomUUID()}").apply { mkdirs() }
                try {
                    val copied = mutableListOf<Pair<File, String>>()
                    for ((idx, rawUri) in uriStrings.withIndex()) {
                        val uri = Uri.parse(rawUri)
                        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                        val displayName = queryUriDisplayName(uri)
                        val safeName = resolveInboundFileName(displayName.orEmpty(), mime, idx)
                        val uniqueFile = allocateUniqueFile(tempDir, safeName)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(uniqueFile).use { output ->
                                input.copyTo(output, bufferSize = 81920)
                            }
                        } ?: throw IOException("无法读取分享的文件 URI")
                        copied += uniqueFile to mime
                    }

                    withBoundedWakeLock("ShareFiles", MAX_WAKELOCK_MS) {
                        uploadFilesInternal(session, copied)
                    }
                    if (copied.size == 1) {
                        "已上传文件：${copied[0].first.name}"
                    } else {
                        "已打包并上传 ${copied.size} 个文件 (Group)"
                    }
                } finally {
                    tempDir.deleteRecursively()
                }
            }
        }

    override fun exportConfigJson(includePasswords: Boolean): String =
        configStore.exportConfigJson(includePasswords)

    override fun importConfigJson(json: String): Result<Unit> =
        configStore.importConfigJson(json)

    override fun requestXposedScopes(): Boolean =
        XposedServiceBridge.requestScopes()

    /**
     * Serializes node session teardown and activation under [sessionMutex], guaranteeing
     * [activeConnectionCount] <= 1 at all times.
     */
    suspend fun activateNode(node: ServerNode) {
        sessionMutex.withLock {
            activateNodeLocked(node)
        }
    }

    private suspend fun reconcileActiveNode(forceReconnect: Boolean) {
        val cfg = configStore.current
        val probes = _probeResultsFlow.value
        val net = networkMonitor.currentSnapshot
        val target = nodeSelector.selectActiveNode(
            nodes = cfg.nodes,
            selectorConfig = cfg.selector,
            currentActiveNodeId = activeSession?.node?.id,
            probeResults = probes,
            networkSnapshot = net
        )

        sessionMutex.withLock {
            val current = activeSession
            if (target == null) {
                if (current != null) {
                    current.closeAndAwait()
                    activeSession = null
                }
                historyStore.setActiveNode(null)
                updateStatus {
                    it.copy(
                        activeNodeId = null,
                        activeNodeName = null,
                        activeNodeType = null,
                        connectionState = ConnectionState.DISCONNECTED,
                        serverCapabilities = null
                    )
                }
                return@withLock
            }

            if (!forceReconnect && current != null && current.node == target) {
                return@withLock
            }

            activateNodeLocked(target)
        }
    }

    private suspend fun activateNodeLocked(node: ServerNode) {
        activeSession?.let { old ->
            old.closeAndAwait()
            activeSession = null
        }

        val nodeKey = NodeStorageKeys.forNode(node)
        historyStore.setActiveNode(nodeKey)

        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = transportFactory.create(node)
        val newCount = _activeConnectionCount.incrementAndGet()
        _peakConnectionCount.updateAndGet { prev -> maxOf(prev, newCount) }
        check(newCount <= 1) { "Invariant violated: activeConnectionCount=$newCount > 1" }

        val session = NodeSession(
            node = node,
            nodeStorageKey = nodeKey,
            transport = transport,
            sessionScope = sessionScope,
            activeCounter = _activeConnectionCount
        )
        activeSession = session

        val cachedCursor = historyStore.getCursor(nodeKey)
        session.capabilities = cachedCursor.cachedCapabilities ?: ServerCapabilitiesDto()

        updateStatus {
            it.copy(
                activeNodeId = node.id,
                activeNodeName = node.name,
                activeNodeType = node.serverType,
                connectionState = ConnectionState.CONNECTING,
                lastRttMs = _probeResultsFlow.value[node.id]?.rttMs,
                lastError = null,
                serverCapabilities = session.capabilities
            )
        }

        sessionScope.launch {
            initializeSession(session)
        }
    }

    private suspend fun initializeSession(session: NodeSession) {
        val node = session.node
        runCatching {
            session.transport.client.initialize()
            val official = session.transport.officialClient
            if (official != null) {
                val caps = official.getCapabilities()
                session.capabilities = caps
                val cursor = historyStore.getCursor(session.nodeStorageKey)
                historyStore.saveCursor(cursor.copy(cachedCapabilities = caps))
                updateStatus { it.copy(serverCapabilities = caps) }
                checkClockSkew(official)
            }

            session.transport.connectRealtime(
                scope = session.sessionScope,
                onConnected = {
                    nodeSelector.recordOutcome(node.id, success = true)
                    updateStatus {
                        it.copy(
                            connectionState = if (node.serverType == ServerType.OFFICIAL) {
                                ConnectionState.CONNECTED
                            } else {
                                ConnectionState.POLLING
                            },
                            lastError = null
                        )
                    }
                    session.sessionScope.launch {
                        withBoundedWakeLock("InitialSync", MAX_WAKELOCK_MS) {
                            runCatching { drainUploadQueue(session) }
                            runCatching { reconcileRemoteClipboard(session, forceApply = false) }
                            runCatching { refreshHistory(resetPagination = false) }
                        }
                    }
                },
                onProfileChanged = { profile ->
                    session.sessionScope.launch {
                        withBoundedWakeLock("RemoteProfile", MAX_WAKELOCK_MS) {
                            runCatching { applyRemoteProfile(session, profile, forceApply = false) }
                        }
                    }
                },
                onHistoryChanged = { record ->
                    session.sessionScope.launch {
                        historyStore.mergeRemoteRecords(
                            session.nodeStorageKey,
                            listOf(record),
                            session.capabilities
                        )
                    }
                },
                onDisconnected = { err ->
                    if (err != null) {
                        nodeSelector.recordOutcome(node.id, success = false)
                        updateStatus {
                            it.copy(
                                connectionState = ConnectionState.ERROR,
                                lastError = err.message ?: err.javaClass.simpleName
                            )
                        }
                        if (configStore.current.selector.mode == SelectorMode.FALLBACK &&
                            nodeSelector.failureCount(node.id) >= configStore.current.selector.failOverThreshold
                        ) {
                            engineScope.launch { reconcileActiveNode(forceReconnect = false) }
                        }
                    } else {
                        updateStatus { it.copy(connectionState = ConnectionState.DISCONNECTED) }
                    }
                }
            )
        }.onFailure { err ->
            nodeSelector.recordOutcome(node.id, success = false)
            updateStatus {
                it.copy(
                    connectionState = ConnectionState.ERROR,
                    lastError = err.message ?: err.javaClass.simpleName
                )
            }
        }
    }

    private suspend fun handleNetworkChanged(snapshot: NetworkSnapshot) {
        val cfg = configStore.current
        if (!snapshot.isConnected || cfg.nodes.isEmpty()) return

        if (cfg.selector.mode == SelectorMode.URL_TEST || cfg.selector.mode == SelectorMode.RULE_AUTO) {
            runCatching { probeAllNodes() }
        }
        reconcileActiveNode(forceReconnect = false)
        activeSession?.let { session ->
            session.sessionScope.launch {
                withBoundedWakeLock("NetReconnectPull", MAX_WAKELOCK_MS) {
                    runCatching { drainUploadQueue(session) }
                    runCatching { reconcileRemoteClipboard(session, forceApply = false) }
                }
            }
        }
    }

    private suspend fun handleOtpCandidate(sourcePackage: String, body: String) {
        val cfg = configStore.current
        if (!cfg.otpCaptureEnabled) return
        val code = OtpExtractor.extract(body) ?: return
        Log.i(TAG, "OTP candidate extracted from $sourcePackage len=${code.length}")

        if (!recentHashDedup.tryClaimText(code)) {
            Log.d(TAG, "OTP dedup skipped len=${code.length}")
            return
        }

        if (cfg.writeOtpToClipboard) {
            writeTextToSystemUi(code, sensitive = true, suppressOverlay = shouldSuppressOverlay())
        }

        if (!cfg.autoSyncEnabled || !cfg.autoUploadEnabled) return
        val session = activeSession ?: return
        withBoundedWakeLock("OtpUpload", MAX_WAKELOCK_MS) {
            runCatching {
                uploadTextInternal(session, code)
                updateStatus {
                    it.copy(
                        lastSyncSummary = "已自动同步验证码 (len=${code.length})",
                        lastSyncAtMs = System.currentTimeMillis()
                    )
                }
            }.onFailure { err ->
                Log.w(TAG, "OTP upload failed: ${err.message}")
            }
        }
    }

    private suspend fun handleLocalClipboardText(text: String, isSensitive: Boolean) {
        val cfg = configStore.current
        if (!cfg.autoSyncEnabled || !cfg.autoUploadEnabled) return

        val textHash = ProfileHashUtils.sha256Hex(text)
        val now = System.currentTimeMillis()
        if (textHash.equals(lastLocalWrittenHash, ignoreCase = true) &&
            (now - lastLocalWriteAtMs) < ECHO_SUPPRESS_WINDOW_MS
        ) {
            return
        }

        if (!recentHashDedup.tryClaimHash(textHash)) {
            // Suppresses duplicate upload when user taps "复制验证码" within 10s of OTP capture.
            return
        }

        val session = activeSession ?: return
        withBoundedWakeLock("ClipTextUpload", MAX_WAKELOCK_MS) {
            runCatching {
                uploadTextInternal(session, text)
            }.onFailure { err ->
                updateStatus { it.copy(lastError = err.message) }
            }
        }
    }

    private suspend fun handleLocalClipboardFiles(files: List<Pair<File, String>>) {
        val cfg = configStore.current
        if (!cfg.autoSyncEnabled || !cfg.autoUploadEnabled || files.isEmpty()) return

        val session = activeSession ?: return
        withBoundedWakeLock("ClipFileUpload", MAX_WAKELOCK_MS) {
            runCatching {
                uploadFilesInternal(session, files)
            }.onFailure { err ->
                updateStatus { it.copy(lastError = err.message) }
            }
        }
    }

    private suspend fun uploadTextInternal(session: NodeSession, fullText: String) {
        transferMutex.withLock {
            val parts = ProfileHashUtils.prepareTextProfile(fullText)
            var tempTxtFile: File? = null
            if (parts.hasTransferData) {
                val fileName = parts.transferDataName!!
                val f = File(nodeCacheDir(session.nodeStorageKey), fileName)
                ProfileHashUtils.writeUtf8NoBom(f, fullText)
                tempTxtFile = f
            }

            val nowIso = HistoryIsoTime.formatUtcIso(System.currentTimeMillis())
            val profile = ProfileDto(
                type = ProfileType.Text,
                hash = parts.fullHashUpper,
                text = parts.inlineText,
                hasData = parts.hasTransferData,
                dataName = parts.transferDataName,
                transferDataHash = if (parts.hasTransferData) parts.fullHashUpper else null,
                size = parts.utf16Size,
                syncedAt = nowIso
            )

            try {
                uploadProfileAndRecord(session, profile, tempTxtFile)
            } finally {
                if (tempTxtFile != null && !parts.hasTransferData) {
                    tempTxtFile.delete()
                }
            }
        }
    }

    private suspend fun uploadFilesInternal(session: NodeSession, files: List<Pair<File, String>>) {
        transferMutex.withLock {
            val nowMs = System.currentTimeMillis()
            val nowIso = HistoryIsoTime.formatUtcIso(nowMs)

            if (files.size == 1) {
                val (file, mime) = files[0]
                val rawName = ensureExtensionForMime(ProfileHashUtils.safeFileName(file.name), mime)
                val dataHash = ProfileHashUtils.sha256FileHex(file)
                val profileHash = ProfileHashUtils.combineHash(rawName, dataHash)
                val effectiveType = if (ProfileHashUtils.isImageFileName(rawName) || mime.startsWith("image/")) {
                    ProfileType.Image
                } else {
                    ProfileType.File
                }

                if (profileHash.equals(lastLocalWrittenHash, ignoreCase = true) &&
                    (nowMs - lastLocalWriteAtMs) < ECHO_SUPPRESS_WINDOW_MS
                ) {
                    return
                }

                val profile = ProfileDto(
                    type = effectiveType,
                    hash = profileHash,
                    text = rawName,
                    hasData = true,
                    dataName = rawName,
                    transferDataHash = dataHash,
                    size = file.length(),
                    syncedAt = nowIso
                )
                uploadProfileAndRecord(session, profile, file)
            } else {
                val zipName = ProfileHashUtils.createTimeBasedFileName("File", "zip")
                val zipFile = File(nodeCacheDir(session.nodeStorageKey), zipName)
                val packResult = GroupArchiveCodec.packGroup(files.map { it.first }, zipFile)
                val profile = ProfileDto(
                    type = ProfileType.Group,
                    hash = packResult.profileHashUpper,
                    text = packResult.topLevelNames.joinToString("\n"),
                    hasData = true,
                    dataName = zipName,
                    transferDataHash = packResult.transferDataHashUpper,
                    size = packResult.totalSize,
                    syncedAt = nowIso
                )
                uploadProfileAndRecord(session, profile, zipFile)
            }
        }
    }

    private suspend fun uploadProfileAndRecord(
        session: NodeSession,
        profile: ProfileDto,
        dataFile: File?
    ) {
        val nowMs = System.currentTimeMillis()
        val nowIso = profile.syncedAt ?: HistoryIsoTime.formatUtcIso(nowMs)
        val localItem = LocalHistoryItem(
            nodeStorageKey = session.nodeStorageKey,
            type = profile.type,
            hashUpper = profile.hash.uppercase(Locale.ROOT),
            text = profile.text,
            size = profile.size ?: 0L,
            hasData = profile.hasData,
            transferDataHash = profile.transferDataHash,
            localDataPath = dataFile?.absolutePath,
            createTimeEpochMs = nowMs,
            lastModifiedEpochMs = nowMs,
            lastAccessedEpochMs = nowMs,
            version = 0,
            isDeleted = false
        )
        historyStore.upsertItem(localItem)
        val normUploadHash = profile.hash.uppercase(Locale.ROOT)
        lastInFlightUploadHash = normUploadHash
        val preCursor = historyStore.getCursor(session.nodeStorageKey)
        historyStore.saveCursor(
            preCursor.copy(
                lastSyncTimeMs = nowMs,
                lastUploadedHash = normUploadHash
            )
        )

        try {
            val official = session.transport.officialClient
            if (official != null) {
                if (profile.hasData && dataFile != null && session.capabilities.historySync) {
                    val recordDto = HistoryRecordDto(
                        hash = profile.hash.uppercase(Locale.ROOT),
                        type = profile.type,
                        text = profile.text,
                        createTime = nowIso,
                        lastModified = nowIso,
                        lastAccessed = nowIso,
                        size = profile.size ?: dataFile.length(),
                        hasData = true,
                        version = 0,
                        isDeleted = false
                    )
                    try {
                        val saved = official.uploadHistory(recordDto, dataFile, profile.transferDataHash)
                        historyStore.mergeRemoteRecords(session.nodeStorageKey, listOf(saved), session.capabilities)
                    } catch (conflict: RemoteConflictException) {
                        conflict.serverUpdate?.let { srv ->
                            historyStore.applyServerUpdate(
                                session.nodeStorageKey,
                                profile.type,
                                profile.hash,
                                srv,
                                session.capabilities
                            )
                        }
                    }
                }
                official.setProfile(profile)
            } else {
                val webdav = session.transport.webDavClient
                if (webdav != null) {
                    if (profile.hasData && dataFile != null && !profile.dataName.isNullOrBlank()) {
                        webdav.uploadFile(profile.dataName, dataFile)
                    }
                    webdav.setProfile(profile)
                }
            }

            val cursor = historyStore.getCursor(session.nodeStorageKey)
            historyStore.saveCursor(
                cursor.copy(
                    lastSyncTimeMs = nowMs,
                    lastUploadedHash = profile.hash.uppercase(Locale.ROOT)
                )
            )
            historyStore.removeUpload(session.nodeStorageKey, profile.type, profile.hash)
            nodeSelector.recordOutcome(session.node.id, success = true)
            updateStatus {
                it.copy(
                    lastSyncSummary = "已上传 ${profile.type.name}",
                    lastSyncAtMs = nowMs,
                    lastError = null
                )
            }
        } catch (err: Throwable) {
            nodeSelector.recordOutcome(session.node.id, success = false)
            historyStore.enqueueUpload(
                PendingUploadItem(
                    nodeStorageKey = session.nodeStorageKey,
                    type = profile.type,
                    hashUpper = profile.hash.uppercase(Locale.ROOT),
                    text = profile.text,
                    dataFilePath = dataFile?.absolutePath,
                    dataName = profile.dataName,
                    transferDataHash = profile.transferDataHash,
                    sizeBytes = profile.size ?: 0L,
                    createdAtMs = nowMs,
                    retryCount = 1,
                    nextAttemptAtMs = nowMs + 10_000L,
                    lastError = err.message
                )
            )
            throw err
        }
    }

    private suspend fun drainUploadQueue(session: NodeSession) {
        val due = historyStore.peekDueUploads(session.nodeStorageKey, nowMs = System.currentTimeMillis())
        for (pending in due) {
            val file = pending.dataFilePath?.let { File(it) }?.takeIf { it.exists() }
            if (pending.dataFilePath != null && file == null) {
                historyStore.removeUpload(session.nodeStorageKey, pending.type, pending.hashUpper)
                continue
            }
            val profile = ProfileDto(
                type = pending.type,
                hash = pending.hashUpper,
                text = pending.text,
                hasData = file != null,
                dataName = pending.dataName,
                transferDataHash = pending.transferDataHash,
                size = pending.sizeBytes,
                syncedAt = HistoryIsoTime.formatUtcIso(pending.createdAtMs)
            )
            runCatching {
                uploadProfileAndRecord(session, profile, file)
            }.onFailure { err ->
                val nextRetry = pending.retryCount + 1
                if (nextRetry >= 5) {
                    historyStore.removeUpload(session.nodeStorageKey, pending.type, pending.hashUpper)
                } else {
                    val backoffMs = (10_000L shl (nextRetry - 1)).coerceAtMost(300_000L)
                    historyStore.markUploadRetry(
                        nodeStorageKey = session.nodeStorageKey,
                        type = pending.type,
                        hashUpper = pending.hashUpper,
                        retryCount = nextRetry,
                        nextAttemptAtMs = System.currentTimeMillis() + backoffMs,
                        lastError = err.message
                    )
                }
            }
        }
    }

    private suspend fun reconcileRemoteClipboard(session: NodeSession, forceApply: Boolean) {
        val snapshot = session.transport.client.getProfile()
        applyRemoteProfile(session, snapshot.profile, forceApply = forceApply)
    }

    private suspend fun applyRemoteProfile(
        session: NodeSession,
        profile: ProfileDto,
        forceApply: Boolean
    ) {
        val cfg = configStore.current
        if (!forceApply && (!cfg.autoSyncEnabled || !cfg.autoDownloadEnabled)) return
        val normHash = profile.hash.trim().uppercase(Locale.ROOT)
        if (normHash.isEmpty()) return

        val cursor = historyStore.getCursor(session.nodeStorageKey)
        if (!forceApply) {
            if (normHash.equals(lastInFlightUploadHash, ignoreCase = true) ||
                normHash.equals(cursor.lastUploadedHash, ignoreCase = true) ||
                normHash.equals(cursor.lastRemoteHash, ignoreCase = true)
            ) {
                return
            }
        }

        val effectiveType = ProfileHashUtils.resolveEffectiveProfileType(profile.type, profile.dataName)
        val nowMs = System.currentTimeMillis()
        val maxAutoBytes = cfg.autoDownloadMaxSizeMb.toLong() * 1024L * 1024L

        when (effectiveType) {
            ProfileType.Text -> {
                if (!profile.hasData) {
                    recentHashDedup.tryClaimHash(normHash)
                    writeTextToSystemUi(profile.text, sensitive = false, suppressOverlay = shouldSuppressOverlay())
                    upsertLocalRemoteItem(session.nodeStorageKey, profile, localPath = null, nowMs = nowMs)
                } else {
                    val estimatedBytes = (profile.size ?: 0L) * 3L
                    if (!forceApply && estimatedBytes > maxAutoBytes) {
                        upsertLocalRemoteItem(session.nodeStorageKey, profile, localPath = null, nowMs = nowMs)
                        return
                    }
                    val destFile = File(
                        nodeDownloadsDir(session.nodeStorageKey),
                        ProfileHashUtils.safeFileName(profile.dataName ?: "Text_${normHash.take(12)}.txt")
                    )
                    downloadAndVerifyProfileData(session, profile, destFile)
                    val fullText = ProfileHashUtils.readUtf8NoBom(destFile)
                    val actualTextHash = ProfileHashUtils.sha256Hex(fullText)
                    if (!ProfileHashUtils.sha256Same(actualTextHash, normHash)) {
                        destFile.delete()
                        throw IOException("Downloaded long text hash mismatch")
                    }
                    recentHashDedup.tryClaimHash(actualTextHash)
                    writeTextToSystemUi(fullText, sensitive = false, suppressOverlay = shouldSuppressOverlay())
                    upsertLocalRemoteItem(session.nodeStorageKey, profile, destFile.absolutePath, nowMs)
                }
            }

            ProfileType.Image -> {
                if (!forceApply && (profile.size ?: 0L) > maxAutoBytes) {
                    upsertLocalRemoteItem(session.nodeStorageKey, profile, localPath = null, nowMs = nowMs)
                    return
                }
                val fileName = ProfileHashUtils.safeFileName(profile.dataName ?: "Image_${normHash.take(12)}.png")
                val destFile = File(nodeDownloadsDir(session.nodeStorageKey), fileName)
                val actualSha = downloadAndVerifyProfileData(session, profile, destFile)
                val originalName = profile.dataName ?: profile.text
                if (originalName.isNotBlank()) {
                    val expectedProfileHash = ProfileHashUtils.combineHash(originalName, actualSha)
                    if (!ProfileHashUtils.sha256Same(expectedProfileHash, normHash)) {
                        destFile.delete()
                        throw IOException("Image CombineHash mismatch")
                    }
                }
                if (cfg.writeImageToClipboard) {
                    writeFileUriToSystemUi(destFile, guessMimeType(destFile.name), shouldSuppressOverlay())
                }
                upsertLocalRemoteItem(
                    session.nodeStorageKey,
                    profile.copy(type = ProfileType.Image),
                    destFile.absolutePath,
                    nowMs
                )
            }

            ProfileType.File -> {
                if (!forceApply && (profile.size ?: 0L) > maxAutoBytes) {
                    upsertLocalRemoteItem(session.nodeStorageKey, profile, localPath = null, nowMs = nowMs)
                    return
                }
                val fileName = ProfileHashUtils.safeFileName(profile.dataName ?: profile.text.ifBlank { "File_${normHash.take(12)}" })
                val destFile = File(nodeDownloadsDir(session.nodeStorageKey), fileName)
                val actualSha = downloadAndVerifyProfileData(session, profile, destFile)
                val originalName = profile.dataName ?: profile.text
                if (originalName.isNotBlank()) {
                    val expectedProfileHash = ProfileHashUtils.combineHash(originalName, actualSha)
                    if (!ProfileHashUtils.sha256Same(expectedProfileHash, normHash)) {
                        destFile.delete()
                        throw IOException("File CombineHash mismatch")
                    }
                }
                upsertLocalRemoteItem(session.nodeStorageKey, profile, destFile.absolutePath, nowMs)
            }

            ProfileType.Group -> {
                if (!forceApply && (profile.size ?: 0L) > maxAutoBytes) {
                    upsertLocalRemoteItem(session.nodeStorageKey, profile, localPath = null, nowMs = nowMs)
                    return
                }
                val zipName = ProfileHashUtils.safeFileName(profile.dataName ?: "Group_${normHash.take(12)}.zip")
                val zipFile = File(nodeDownloadsDir(session.nodeStorageKey), zipName)
                downloadAndVerifyProfileData(session, profile, zipFile)
                val extractDir = File(nodeDownloadsDir(session.nodeStorageKey), "group_${normHash.take(16)}")
                runCatching {
                    if (extractDir.exists()) extractDir.deleteRecursively()
                    GroupArchiveCodec.unpackGroup(zipFile, extractDir, expectedProfileHash = normHash)
                }.onFailure { err ->
                    extractDir.deleteRecursively()
                    throw err
                }
                upsertLocalRemoteItem(session.nodeStorageKey, profile, zipFile.absolutePath, nowMs)
            }

            else -> return
        }

        historyStore.saveCursor(
            historyStore.getCursor(session.nodeStorageKey).copy(
                lastSyncTimeMs = nowMs,
                lastRemoteHash = normHash
            )
        )
        updateStatus {
            it.copy(
                lastSyncSummary = "已同步远端 ${effectiveType.name}",
                lastSyncAtMs = nowMs,
                lastError = null
            )
        }
    }

    private suspend fun downloadAndVerifyProfileData(
        session: NodeSession,
        profile: ProfileDto,
        destFile: File
    ): String {
        val official = session.transport.officialClient
        val expectedTransfer = profile.transferDataHash
            ?: if (profile.type == ProfileType.Text) profile.hash else null

        val actualSha = if (!profile.dataName.isNullOrBlank()) {
            session.transport.client.downloadFile(profile.dataName, destFile)
            ProfileHashUtils.sha256FileHex(destFile)
        } else if (official != null && session.capabilities.historySync) {
            official.downloadHistoryData(profile.type, profile.hash, destFile, expectedTransfer)
        } else {
            throw IOException("Missing dataName for remote profile ${profile.hash}")
        }

        if (expectedTransfer != null && !ProfileHashUtils.sha256Same(actualSha, expectedTransfer)) {
            destFile.delete()
            throw IOException("Transfer data SHA-256 mismatch")
        }
        return actualSha
    }

    private fun upsertLocalRemoteItem(
        nodeStorageKey: String,
        profile: ProfileDto,
        localPath: String?,
        nowMs: Long
    ) {
        val normHash = profile.hash.uppercase(Locale.ROOT)
        val existing = historyStore.getItem(nodeStorageKey, profile.type, normHash)
        val item = if (existing != null) {
            existing.copy(
                text = if (profile.text.isNotEmpty()) profile.text else existing.text,
                size = profile.size ?: existing.size,
                hasData = profile.hasData || existing.hasData,
                transferDataHash = profile.transferDataHash ?: existing.transferDataHash,
                localDataPath = localPath ?: existing.localDataPath,
                lastAccessedEpochMs = nowMs
            )
        } else {
            LocalHistoryItem(
                nodeStorageKey = nodeStorageKey,
                type = profile.type,
                hashUpper = normHash,
                text = profile.text,
                size = profile.size ?: 0L,
                hasData = profile.hasData,
                transferDataHash = profile.transferDataHash,
                localDataPath = localPath,
                createTimeEpochMs = nowMs,
                lastModifiedEpochMs = nowMs,
                lastAccessedEpochMs = nowMs
            )
        }
        historyStore.upsertItem(item)
    }

    private suspend fun backfillMissingMetadata(session: NodeSession) {
        val official = session.transport.officialClient ?: return
        if (!session.capabilities.historySync) return
        val pending = historyStore.itemsNeedingMetadataBackfill(session.nodeStorageKey)
        for (item in pending) {
            val patchDto = HistoryRecordUpdateDto(
                tags = if (session.capabilities.historyTags) item.tags else null,
                title = if (session.capabilities.historyTitle) item.title else null,
                version = item.version + 1,
                lastModified = HistoryIsoTime.formatUtcIso(System.currentTimeMillis())
            )
            runCatching {
                official.patchHistory(item.type, item.hashUpper, patchDto)
                historyStore.clearBackfillFlag(session.nodeStorageKey, item.type, item.hashUpper)
            }
        }
    }

    private suspend fun reuploadHistoryRecord(
        session: NodeSession,
        official: OfficialClipboardClient,
        item: LocalHistoryItem
    ) {
        var dataFile = item.localDataPath?.let { File(it) }?.takeIf { it.exists() && it.isFile }
        if (item.type == ProfileType.Text && item.hasData && dataFile == null) {
            val fallbackFile = File(nodeCacheDir(session.nodeStorageKey), "Text_${item.hashUpper.take(12)}.txt")
            if (item.text.length > ProfileHashUtils.TEXT_TRANSFER_THRESHOLD) {
                ProfileHashUtils.writeUtf8NoBom(fallbackFile, item.text)
                dataFile = fallbackFile
            }
        }
        val dto = HistoryRecordDto(
            hash = item.hashUpper,
            type = item.type,
            text = item.text,
            createTime = HistoryIsoTime.formatUtcIso(item.createTimeEpochMs),
            lastModified = HistoryIsoTime.formatUtcIso(item.lastModifiedEpochMs),
            lastAccessed = HistoryIsoTime.formatUtcIso(item.lastAccessedEpochMs),
            tags = item.tags,
            title = item.title,
            starred = item.starred,
            pinned = item.pinned,
            size = item.size,
            hasData = item.hasData && dataFile != null,
            version = item.version,
            isDeleted = false
        )
        val saved = official.uploadHistory(dto, dataFile, item.transferDataHash)
        historyStore.mergeRemoteRecords(session.nodeStorageKey, listOf(saved), session.capabilities)
    }

    private suspend fun checkClockSkew(official: OfficialClipboardClient) {
        val rawServerTime = runCatching { official.getServerTime() }.getOrNull() ?: return
        val serverMs = HistoryIsoTime.parseEpochMs(rawServerTime, 0L)
        if (serverMs <= 0L) return
        val skewMs = abs(System.currentTimeMillis() - serverMs)
        if (skewMs > FIVE_MINUTES_MS) {
            updateStatus {
                it.copy(lastError = "设备与服务器时钟偏差超过 5 分钟 (${skewMs / 60_000L}m)")
            }
        }
    }

    private fun writeTextToSystemUi(text: String, sensitive: Boolean, suppressOverlay: Boolean) {
        val hash = ProfileHashUtils.sha256Hex(text)
        lastLocalWrittenHash = hash
        lastLocalWriteAtMs = System.currentTimeMillis()
        recentHashDedup.tryClaimHash(hash)
        val client = clipboardClient ?: return
        runCatching {
            client.writeTextToClipboard(text, sensitive, suppressOverlay)
        }.onFailure { err ->
            Log.w(TAG, "writeTextToClipboard failed: ${err.message}")
        }
    }

    private fun writeFileUriToSystemUi(file: File, mimeType: String, suppressOverlay: Boolean) {
        val fileSha = ProfileHashUtils.sha256FileHex(file)
        val combined = ProfileHashUtils.combineHash(file.name, fileSha)
        lastLocalWrittenHash = combined
        lastLocalWriteAtMs = System.currentTimeMillis()

        val client = clipboardClient ?: return
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        context.grantUriPermission(
            PACKAGE_SYSTEM_UI,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        runCatching {
            client.writeUriToClipboard(uri.toString(), mimeType, file.name, false, suppressOverlay)
        }.onFailure { err ->
            Log.w(TAG, "writeUriToClipboard failed: ${err.message}")
        }
    }

    private fun shouldSuppressOverlay(): Boolean {
        val interactive = runCatching { powerManager?.isInteractive ?: true }.getOrDefault(true)
        return !interactive
    }

    internal suspend fun <T> withBoundedWakeLock(
        tag: String,
        timeoutMs: Long = MAX_WAKELOCK_MS,
        block: suspend () -> T
    ): T {
        val clampedMs = timeoutMs.coerceIn(1L, MAX_WAKELOCK_MS)
        val wl = runCatching {
            powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SyncClipboard:$tag")?.apply {
                setReferenceCounted(false)
                acquire(clampedMs)
            }
        }.getOrNull()
        return try {
            block()
        } finally {
            if (wl != null) {
                runCatching {
                    if (wl.isHeld) wl.release()
                }
            }
        }
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        val delaySec = configStore.current.screenOffDisconnectDelaySec
                        if (delaySec > 0) {
                            screenOffJob?.cancel()
                            screenOffJob = engineScope.launch {
                                delay(delaySec * 1000L)
                                sessionMutex.withLock {
                                    activeSession?.closeAndAwait()
                                    activeSession = null
                                }
                                updateStatus { it.copy(connectionState = ConnectionState.DISCONNECTED) }
                            }
                        }
                    }

                    Intent.ACTION_SCREEN_ON -> {
                        screenOffJob?.cancel()
                        screenOffJob = null
                        engineScope.launch {
                            if (activeSession == null) {
                                reconcileActiveNode(forceReconnect = true)
                            } else {
                                activeSession?.let { session ->
                                    withBoundedWakeLock("ScreenOnSync", MAX_WAKELOCK_MS) {
                                        runCatching { reconcileRemoteClipboard(session, forceApply = false) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        runCatching { context.registerReceiver(receiver, filter) }
    }

    private fun nodeCacheDir(nodeStorageKey: String): File {
        val base = context.cacheDir ?: File(System.getProperty("java.io.tmpdir"), "scx_cache")
        return File(base, "nodes/$nodeStorageKey").apply { mkdirs() }
    }

    private fun nodeDownloadsDir(nodeStorageKey: String): File {
        val base = context.filesDir ?: File(System.getProperty("java.io.tmpdir"), "scx_files")
        return File(base, "nodes/$nodeStorageKey/downloads").apply { mkdirs() }
    }

    private fun queryUriDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private fun resolveInboundFileName(rawName: String, mimeType: String, index: Int): String {
        val clean = ProfileHashUtils.safeFileName(rawName.ifBlank { "shared_${index + 1}" })
        return ensureExtensionForMime(clean, mimeType)
    }

    private fun ensureExtensionForMime(name: String, mimeType: String): String {
        if (name.contains('.') && name.substringAfterLast('.').isNotBlank()) return name
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)?.takeIf { it.isNotBlank() }
        return if (ext != null) "$name.$ext" else name
    }

    private fun allocateUniqueFile(dir: File, preferredName: String): File {
        var candidate = File(dir, preferredName)
        if (!candidate.exists()) return candidate
        val stem = preferredName.substringBeforeLast('.', preferredName)
        val ext = preferredName.substringAfterLast('.', "")
        var counter = 1
        while (candidate.exists()) {
            val suffix = if (ext.isNotEmpty()) "_$counter.$ext" else "_$counter"
            candidate = File(dir, "$stem$suffix")
            counter++
        }
        return candidate
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun updateStatus(transform: (EngineStatusSnapshot) -> EngineStatusSnapshot) {
        _statusFlow.value = transform(_statusFlow.value)
    }

    companion object {
        private const val TAG = "SyncEngine"
        private const val PACKAGE_SYSTEM_UI = "com.android.systemui"
        const val MAX_WAKELOCK_MS = 15_000L
        private const val FIVE_MINUTES_MS = 5L * 60L * 1000L
        private const val ECHO_SUPPRESS_WINDOW_MS = 5_000L
        private const val HISTORY_PAGE_SIZE = 50
    }
}
