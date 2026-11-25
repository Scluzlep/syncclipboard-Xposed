package com.scluzlep.syncclipboard

import android.content.Context
import android.content.ContextWrapper
import com.scluzlep.syncclipboard.config.ConfigStore
import com.scluzlep.syncclipboard.config.InMemoryConfigBackingStore
import com.scluzlep.syncclipboard.engine.NodeSessionTransport
import com.scluzlep.syncclipboard.engine.NodeTransportFactory
import com.scluzlep.syncclipboard.engine.OtpExtractor
import com.scluzlep.syncclipboard.engine.RecentHashDedup
import com.scluzlep.syncclipboard.engine.SyncEngine
import com.scluzlep.syncclipboard.history.HistoryIsoTime
import com.scluzlep.syncclipboard.history.HistoryMergePolicy
import com.scluzlep.syncclipboard.history.HistoryRepository
import com.scluzlep.syncclipboard.history.NodeStorageKeys
import com.scluzlep.syncclipboard.history.PendingUploadItem
import com.scluzlep.syncclipboard.history.SyncCursorRecord
import com.scluzlep.syncclipboard.model.AppConfig
import com.scluzlep.syncclipboard.model.HistoryFilterState
import com.scluzlep.syncclipboard.model.HistoryRecordDto
import com.scluzlep.syncclipboard.model.HistoryRecordUpdateDto
import com.scluzlep.syncclipboard.model.LocalHistoryItem
import com.scluzlep.syncclipboard.model.NetworkTransportRule
import com.scluzlep.syncclipboard.model.NodeNetworkRule
import com.scluzlep.syncclipboard.model.NodeProbeResult
import com.scluzlep.syncclipboard.model.ProfileDto
import com.scluzlep.syncclipboard.model.ProfileType
import com.scluzlep.syncclipboard.model.SelectorConfig
import com.scluzlep.syncclipboard.model.SelectorMode
import com.scluzlep.syncclipboard.model.ServerCapabilitiesDto
import com.scluzlep.syncclipboard.model.ServerNode
import com.scluzlep.syncclipboard.model.ServerType
import com.scluzlep.syncclipboard.protocol.ClipboardTransportClient
import com.scluzlep.syncclipboard.protocol.OfficialClipboardClient
import com.scluzlep.syncclipboard.protocol.RemoteProfileSnapshot
import com.scluzlep.syncclipboard.protocol.WebDavClipboardClient
import com.scluzlep.syncclipboard.selector.CidrMatcher
import com.scluzlep.syncclipboard.selector.NetworkMonitor
import com.scluzlep.syncclipboard.selector.NetworkSnapshot
import com.scluzlep.syncclipboard.selector.NodeProber
import com.scluzlep.syncclipboard.selector.NodeSelector
import com.scluzlep.syncclipboard.selector.ProbeBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SelectorAndOtpTest {

    private class InMemoryHistoryRepository : HistoryRepository {
        private val items = ConcurrentHashMap<Triple<String, ProfileType, String>, LocalHistoryItem>()
        private val cursors = ConcurrentHashMap<String, SyncCursorRecord>()
        private val uploads = ConcurrentHashMap<Triple<String, ProfileType, String>, PendingUploadItem>()
        @Volatile private var activeKey: String? = null

        private val _filterFlow = MutableStateFlow(HistoryFilterState())
        override val filterFlow: StateFlow<HistoryFilterState> = _filterFlow.asStateFlow()
        private val _historyFlow = MutableStateFlow<List<LocalHistoryItem>>(emptyList())
        override val historyFlow: StateFlow<List<LocalHistoryItem>> = _historyFlow.asStateFlow()
        private val _availableTagsFlow = MutableStateFlow<List<String>>(emptyList())
        override val availableTagsFlow: StateFlow<List<String>> = _availableTagsFlow.asStateFlow()

        override fun setActiveNode(nodeStorageKey: String?) {
            activeKey = nodeStorageKey
            refresh()
        }

        override fun setFilter(filter: HistoryFilterState) {
            _filterFlow.value = filter
            refresh()
        }

        override fun getItem(nodeStorageKey: String, type: ProfileType, hashUpper: String): LocalHistoryItem? =
            items[Triple(nodeStorageKey, type, hashUpper.uppercase())]

        override fun getAllForNode(nodeStorageKey: String, includeDeleted: Boolean): List<LocalHistoryItem> =
            items.values.filter { it.nodeStorageKey == nodeStorageKey && (includeDeleted || !it.isDeleted) }

        override fun upsertItem(item: LocalHistoryItem) {
            items[Triple(item.nodeStorageKey, item.type, item.hashUpper.uppercase())] = item
            if (item.nodeStorageKey == activeKey) refresh()
        }

        override fun mergeRemoteRecords(
            nodeStorageKey: String,
            records: List<HistoryRecordDto>,
            capabilities: ServerCapabilitiesDto
        ): List<LocalHistoryItem> {
            return records.map { dto ->
                val key = Triple(nodeStorageKey, dto.type, dto.hash.uppercase())
                val merged = HistoryMergePolicy.mergeRemoteRecord(items[key], dto, nodeStorageKey, capabilities)
                items[key] = merged
                merged
            }.also {
                if (nodeStorageKey == activeKey) refresh()
            }
        }

        override fun applyServerUpdate(
            nodeStorageKey: String,
            type: ProfileType,
            hashUpper: String,
            update: HistoryRecordUpdateDto,
            capabilities: ServerCapabilitiesDto
        ): LocalHistoryItem? {
            val key = Triple(nodeStorageKey, type, hashUpper.uppercase())
            val existing = items[key] ?: return null
            val updated = HistoryMergePolicy.applyServerUpdate(existing, update, capabilities)
            items[key] = updated
            if (nodeStorageKey == activeKey) refresh()
            return updated
        }

        override fun updateLocalDataPath(
            nodeStorageKey: String,
            type: ProfileType,
            hashUpper: String,
            localDataPath: String?,
            transferDataHash: String?
        ) {
            val key = Triple(nodeStorageKey, type, hashUpper.uppercase())
            items[key]?.let {
                items[key] = it.copy(localDataPath = localDataPath, transferDataHash = transferDataHash ?: it.transferDataHash)
            }
        }

        override fun clearBackfillFlag(nodeStorageKey: String, type: ProfileType, hashUpper: String) {
            val key = Triple(nodeStorageKey, type, hashUpper.uppercase())
            items[key]?.let { items[key] = it.copy(needsMetadataBackfill = false) }
        }

        override fun itemsNeedingMetadataBackfill(nodeStorageKey: String): List<LocalHistoryItem> =
            items.values.filter { it.nodeStorageKey == nodeStorageKey && it.needsMetadataBackfill && !it.isDeleted }

        override fun clearNodeHistory(nodeStorageKey: String) {
            items.keys.removeIf { it.first == nodeStorageKey }
            if (nodeStorageKey == activeKey) refresh()
        }

        override fun getCursor(nodeStorageKey: String): SyncCursorRecord =
            cursors[nodeStorageKey] ?: SyncCursorRecord(nodeStorageKey)

        override fun saveCursor(cursor: SyncCursorRecord) {
            cursors[cursor.nodeStorageKey] = cursor
        }

        override fun enqueueUpload(item: PendingUploadItem) {
            uploads[Triple(item.nodeStorageKey, item.type, item.hashUpper.uppercase())] = item
        }

        override fun peekDueUploads(nodeStorageKey: String, nowMs: Long, limit: Int): List<PendingUploadItem> =
            uploads.values.filter { it.nodeStorageKey == nodeStorageKey && it.nextAttemptAtMs <= nowMs }.take(limit)

        override fun removeUpload(nodeStorageKey: String, type: ProfileType, hashUpper: String) {
            uploads.remove(Triple(nodeStorageKey, type, hashUpper.uppercase()))
        }

        override fun markUploadRetry(
            nodeStorageKey: String,
            type: ProfileType,
            hashUpper: String,
            retryCount: Int,
            nextAttemptAtMs: Long,
            lastError: String?
        ) {
            val key = Triple(nodeStorageKey, type, hashUpper.uppercase())
            uploads[key]?.let {
                uploads[key] = it.copy(retryCount = retryCount, nextAttemptAtMs = nextAttemptAtMs, lastError = lastError)
            }
        }

        private fun refresh() {
            val k = activeKey
            if (k == null) {
                _historyFlow.value = emptyList()
                _availableTagsFlow.value = emptyList()
                return
            }
            val list = items.values.filter { it.nodeStorageKey == k }
            _historyFlow.value = HistoryMergePolicy.filterAndSort(list, _filterFlow.value)
            _availableTagsFlow.value = list.filter { !it.isDeleted }
                .flatMap { it.tags.orEmpty() }
                .distinct()
                .sorted()
        }
    }

    private fun createStubContext(): Context {
        return object : ContextWrapper(null) {
            override fun getPackageName(): String = "com.scluzlep.syncclipboard"
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? = null
        }
    }

    @Test
    fun syncEngine_guaranteesSingleActiveConnectionAcrossConcurrentSwitches() = runBlocking {
        val nodeA = ServerNode(id = "a", name = "Node A", baseUrl = "https://a.example.com", username = "u1")
        val nodeB = ServerNode(id = "b", name = "Node B", baseUrl = "https://b.example.com", username = "u2")
        val nodeC = ServerNode(id = "c", name = "Node C", baseUrl = "https://c.example.com", username = "u3")

        val configStore = ConfigStore(InMemoryConfigBackingStore()).apply {
            update { it.copy(nodes = listOf(nodeA, nodeB, nodeC)) }
        }
        val historyRepo = InMemoryHistoryRepository()
        val stubCtx = createStubContext()
        val liveTransportCount = AtomicInteger(0)
        val peakTransportCount = AtomicInteger(0)

        val transportFactory = NodeTransportFactory { _ ->
            val current = liveTransportCount.incrementAndGet()
            peakTransportCount.updateAndGet { maxOf(it, current) }
            val fakeClient = object : ClipboardTransportClient {
                override suspend fun testConnection() {}
                override suspend fun initialize() {}
                override suspend fun getProfile(): RemoteProfileSnapshot = RemoteProfileSnapshot(ProfileDto())
                override suspend fun setProfile(profile: ProfileDto) {}
                override suspend fun uploadFile(fileName: String, localFile: File) {}
                override suspend fun downloadFile(fileName: String, destFile: File) {}
                override fun close() {}
            }
            object : NodeSessionTransport {
                override val client: ClipboardTransportClient = fakeClient
                override val officialClient: OfficialClipboardClient? = null
                override val webDavClient: WebDavClipboardClient? = null
                override suspend fun connectRealtime(
                    scope: CoroutineScope,
                    onConnected: () -> Unit,
                    onProfileChanged: (ProfileDto) -> Unit,
                    onHistoryChanged: (HistoryRecordDto) -> Unit,
                    onDisconnected: (Throwable?) -> Unit
                ) {
                    delay(10)
                    onConnected()
                }
                override suspend fun disconnectRealtime() {
                    delay(10)
                }
                override fun close() {
                    liveTransportCount.decrementAndGet()
                }
            }
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = SyncEngine(
            context = stubCtx,
            configStore = configStore,
            historyStore = historyRepo,
            networkMonitor = NetworkMonitor(stubCtx),
            nodeProber = NodeProber(customBackend = object : ProbeBackend {
                override suspend fun probe(node: ServerNode) = NodeProbeResult(node.id, true, 20L)
            }),
            transportFactory = transportFactory,
            engineScope = scope
        )

        val jobs = (0 until 12).map { idx ->
            async {
                val target = listOf(nodeA, nodeB, nodeC)[idx % 3]
                engine.activateNode(target)
                assertTrue(engine.activeConnectionCount <= 1)
            }
        }
        jobs.awaitAll()

        assertEquals(1, engine.activeConnectionCount)
        assertEquals(1, engine.peakConnectionCount)
        assertEquals(1, liveTransportCount.get())
        assertEquals(1, peakTransportCount.get())
        engine.shutdown()
    }

    @Test
    fun nodeStorageKey_isolatesServersAndNormalizesTrailingSlashes() {
        val k1 = NodeStorageKeys.forEndpoint(ServerType.OFFICIAL, "https://sync.home.lan:5033/", "alice")
        val k2 = NodeStorageKeys.forEndpoint(ServerType.OFFICIAL, "https://sync.home.lan:5033", "alice")
        val k3 = NodeStorageKeys.forEndpoint(ServerType.OFFICIAL, "https://sync.home.lan:5033", "bob")
        val k4 = NodeStorageKeys.forEndpoint(ServerType.WEBDAV, "https://sync.home.lan:5033", "alice")

        assertEquals(24, k1.length)
        assertEquals(k1, k2)
        assertNotEquals(k1, k3)
        assertNotEquals(k1, k4)

        val repo = InMemoryHistoryRepository()
        val now = 1_700_000_000_000L
        repo.upsertItem(
            LocalHistoryItem(
                nodeStorageKey = k1,
                type = ProfileType.Text,
                hashUpper = "AAA",
                text = "Node1 Secret",
                createTimeEpochMs = now,
                lastModifiedEpochMs = now,
                lastAccessedEpochMs = now
            )
        )
        repo.upsertItem(
            LocalHistoryItem(
                nodeStorageKey = k3,
                type = ProfileType.Text,
                hashUpper = "AAA",
                text = "Node3 Secret",
                createTimeEpochMs = now,
                lastModifiedEpochMs = now,
                lastAccessedEpochMs = now
            )
        )

        repo.setActiveNode(k1)
        assertEquals(listOf("Node1 Secret"), repo.historyFlow.value.map { it.text })
        repo.setActiveNode(k3)
        assertEquals(listOf("Node3 Secret"), repo.historyFlow.value.map { it.text })
    }

    @Test
    fun nodeSelector_fallbackThresholdAndRecoveryHold() {
        var nowMs = 1_000_000L
        val selector = NodeSelector(nowProvider = { nowMs })
        val primary = ServerNode(id = "p", name = "Primary", baseUrl = "https://p.lan", priority = 1)
        val backup = ServerNode(id = "b", name = "Backup", baseUrl = "https://b.lan", priority = 10)
        val cfg = SelectorConfig(mode = SelectorMode.FALLBACK, failOverThreshold = 3, recoveryHoldSec = 60)
        val nodes = listOf(backup, primary)
        val net = NetworkSnapshot(isConnected = true, transports = setOf(NetworkTransportRule.WIFI))

        var active = selector.selectActiveNode(nodes, cfg, null, emptyMap(), net)
        assertEquals("p", active?.id)

        // 2 failures < threshold(3) -> stay on primary
        selector.recordOutcome("p", false)
        selector.recordOutcome("p", false)
        active = selector.selectActiveNode(nodes, cfg, "p", emptyMap(), net)
        assertEquals("p", active?.id)

        // 3rd failure -> failover to backup
        selector.recordOutcome("p", false)
        active = selector.selectActiveNode(nodes, cfg, "p", emptyMap(), net)
        assertEquals("b", active?.id)

        // Primary recovers in probe, but must hold for 60s
        selector.recordOutcome("p", true)
        val probes = mapOf(
            "p" to NodeProbeResult("p", reachable = true, rttMs = 15L),
            "b" to NodeProbeResult("b", reachable = true, rttMs = 30L)
        )
        active = selector.selectActiveNode(nodes, cfg, "b", probes, net)
        assertEquals("b", active?.id) // t = 0s

        nowMs += 59_000L
        active = selector.selectActiveNode(nodes, cfg, "b", probes, net)
        assertEquals("b", active?.id) // t = 59s

        nowMs += 1_000L
        active = selector.selectActiveNode(nodes, cfg, "b", probes, net)
        assertEquals("p", active?.id) // t = 60s -> switches back
    }

    @Test
    fun nodeSelector_urlTest80msHysteresis() {
        val selector = NodeSelector()
        val n1 = ServerNode(id = "n1", name = "N1", baseUrl = "https://n1.lan", priority = 1)
        val n2 = ServerNode(id = "n2", name = "N2", baseUrl = "https://n2.lan", priority = 2)
        val cfg = SelectorConfig(mode = SelectorMode.URL_TEST, urlTestMinGainMs = 80)
        val net = NetworkSnapshot(isConnected = true, transports = setOf(NetworkTransportRule.WIFI))

        // Gain is 79ms (120 - 41) -> keep current n1
        val smallGainProbes = mapOf(
            "n1" to NodeProbeResult("n1", reachable = true, rttMs = 120L),
            "n2" to NodeProbeResult("n2", reachable = true, rttMs = 41L)
        )
        assertEquals("n1", selector.selectActiveNode(listOf(n1, n2), cfg, "n1", smallGainProbes, net)?.id)

        // Gain is 80ms (120 - 40) -> switch to n2
        val enoughGainProbes = mapOf(
            "n1" to NodeProbeResult("n1", reachable = true, rttMs = 120L),
            "n2" to NodeProbeResult("n2", reachable = true, rttMs = 40L)
        )
        assertEquals("n2", selector.selectActiveNode(listOf(n1, n2), cfg, "n1", enoughGainProbes, net)?.id)
    }

    @Test
    fun nodeSelector_ruleAutoCidrAndIpv6Matching() {
        assertTrue(CidrMatcher.matches("192.168.3.45", "192.168.3.0/24"))
        assertFalse(CidrMatcher.matches("192.168.4.45", "192.168.3.0/24"))
        assertTrue(CidrMatcher.matches("fd12:3456:789a::42", "fd12:3456:789a::/48"))
        assertFalse(CidrMatcher.matches("fd12:3456:789b::42", "fd12:3456:789a::/48"))

        val lanNode = ServerNode(
            id = "lan",
            name = "Home NAS",
            baseUrl = "http://192.168.3.10:5033",
            allowCleartext = true,
            priority = 1,
            networkRule = NodeNetworkRule(
                allowedTransports = setOf(NetworkTransportRule.WIFI),
                cidrAllowlist = listOf("192.168.3.0/24"),
                ssidAllowlist = listOf("Home5G") // skipped if no location permission!
            )
        )
        val wanNode = ServerNode(
            id = "wan",
            name = "Public Relay",
            baseUrl = "https://clip.example.com",
            priority = 10,
            networkRule = NodeNetworkRule()
        )

        val selector = NodeSelector()
        val cfg = SelectorConfig(mode = SelectorMode.RULE_AUTO)

        // Home Wi-Fi without location permission (wifiSsid == null): CIDR matches lanNode!
        val homeWifi = NetworkSnapshot(
            isConnected = true,
            transports = setOf(NetworkTransportRule.WIFI),
            localIpStrings = listOf("192.168.3.88"),
            wifiSsid = null,
            hasLocationPermission = false
        )
        assertEquals("lan", selector.selectActiveNode(listOf(wanNode, lanNode), cfg, null, emptyMap(), homeWifi)?.id)

        // Cellular network: falls back to generic wanNode
        val cellular = NetworkSnapshot(
            isConnected = true,
            transports = setOf(NetworkTransportRule.CELLULAR),
            localIpStrings = listOf("10.42.0.5")
        )
        assertEquals("wan", selector.selectActiveNode(listOf(wanNode, lanNode), cfg, "lan", emptyMap(), cellular)?.id)
    }

    @Test
    fun historyMergePolicy_fiveMinuteShouldUpdateAndTagsNullVsEmpty() {
        val baseTimeMs = 1_700_000_000_000L
        // Within 5 min (4 min diff): version wins (>=)
        assertTrue(HistoryMergePolicy.shouldUpdate(baseTimeMs, 2, baseTimeMs + 240_000L, 2))
        assertFalse(HistoryMergePolicy.shouldUpdate(baseTimeMs, 3, baseTimeMs + 240_000L, 2))
        assertTrue(HistoryMergePolicy.shouldUpdate(baseTimeMs - 240_000L, 2, baseTimeMs, 3))

        // Beyond 5 min (6 min diff): timestamp wins (>=) even if version is lower
        assertTrue(HistoryMergePolicy.shouldUpdate(baseTimeMs, 5, baseTimeMs + 360_000L, 1))
        assertFalse(HistoryMergePolicy.shouldUpdate(baseTimeMs + 360_000L, 1, baseTimeMs, 9))

        val caps = ServerCapabilitiesDto(historySync = true, historyTags = true, historyTitle = true)
        val localWithTags = LocalHistoryItem(
            nodeStorageKey = "k1",
            type = ProfileType.Text,
            hashUpper = "HASH1",
            text = "hello",
            title = "MyTitle",
            tags = listOf("otp", "work"),
            createTimeEpochMs = baseTimeMs,
            lastModifiedEpochMs = baseTimeMs,
            lastAccessedEpochMs = baseTimeMs,
            version = 1
        )

        // Remote DTO has tags = null and title = null -> preserve local & flag needsMetadataBackfill!
        val remoteNullMeta = HistoryRecordDto(
            hash = "HASH1",
            type = ProfileType.Text,
            text = "hello",
            createTime = HistoryIsoTime.formatUtcIso(baseTimeMs),
            lastModified = HistoryIsoTime.formatUtcIso(baseTimeMs + 10_000L),
            lastAccessed = HistoryIsoTime.formatUtcIso(baseTimeMs + 10_000L),
            tags = null,
            title = null,
            version = 2
        )
        val mergedNull = HistoryMergePolicy.mergeRemoteRecord(localWithTags, remoteNullMeta, "k1", caps)
        assertEquals(listOf("otp", "work"), mergedNull.tags)
        assertEquals("MyTitle", mergedNull.title)
        assertTrue(mergedNull.needsMetadataBackfill)

        // Remote DTO explicitly sends tags = [] (emptyList) and title = "" -> explicitly cleared!
        val remoteCleared = remoteNullMeta.copy(tags = emptyList(), title = "", version = 3)
        val mergedCleared = HistoryMergePolicy.mergeRemoteRecord(localWithTags, remoteCleared, "k1", caps)
        assertEquals(emptyList<String>(), mergedCleared.tags)
        assertEquals("", mergedCleared.title)
    }

    @Test
    fun otpExtractor_extractsCnEnCodesAndShares10sSha256Dedup() {
        // Default AppConfig must have otpCaptureEnabled = true & screenOffDisconnectDelaySec = 0
        val defaultCfg = AppConfig()
        assertTrue(defaultCfg.otpCaptureEnabled)
        assertEquals(0, defaultCfg.screenOffDisconnectDelaySec)

        // CN & EN OTP patterns
        assertEquals("271460", OtpExtractor.extract("【云服务】您的验证码是：271460，请在5分钟内输入。"))
        assertEquals("849201", OtpExtractor.extract("G-849201 is your Google verification code."))
        assertEquals("4829", OtpExtractor.extract("Your login passcode is 4829. Do not share."))
        assertEquals("73910428", OtpExtractor.extract("安全动态码 73910428（2026-11-25 有效）"))
        assertNull(OtpExtractor.extract("今天消费¥1280元，祝您生活愉快"))

        // 10s SHA-256 shared dedup cache
        var clockMs = 50_000L
        val dedup = RecentHashDedup(windowMs = 10_000L, nowProvider = { clockMs })
        val code = OtpExtractor.extract("【银行】短信验证码：654321，请勿泄露")!!
        assertTrue(dedup.tryClaimText(code))

        // 3s later user taps "复制验证码" -> same SHA-256 -> blocked!
        clockMs += 3_000L
        assertFalse(dedup.tryClaimText("654321"))

        // 9.9s total -> still blocked
        clockMs += 6_999L
        assertFalse(dedup.tryClaimText("654321"))

        // At 10.0s -> expired, allowed again
        clockMs += 1L
        assertTrue(dedup.tryClaimText("654321"))

        // ConfigStore JSON export/import round-trip with includePasswords = false vs true
        val store = ConfigStore(InMemoryConfigBackingStore())
        store.upsertNode(
            ServerNode(
                id = "n1",
                name = "Test",
                baseUrl = "https://clip.lan/",
                username = "admin",
                password = "secret-password"
            )
        )
        val redactedJson = store.exportConfigJson(includePasswords = false)
        assertFalse(redactedJson.contains("secret-password"))
        val fullJson = store.exportConfigJson(includePasswords = true)
        assertTrue(fullJson.contains("secret-password"))

        val store2 = ConfigStore(InMemoryConfigBackingStore())
        assertTrue(store2.importConfigJson(fullJson).isSuccess)
        assertEquals("secret-password", store2.current.nodes.single().password)
        assertEquals("https://clip.lan", store2.current.nodes.single().baseUrl)
    }
}
