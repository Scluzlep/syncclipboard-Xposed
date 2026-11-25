package com.scluzlep.syncclipboard

import com.scluzlep.syncclipboard.model.HistoryQueryParams
import com.scluzlep.syncclipboard.model.HistoryRecordDto
import com.scluzlep.syncclipboard.model.HistoryRecordUpdateDto
import com.scluzlep.syncclipboard.model.ProfileDto
import com.scluzlep.syncclipboard.model.ProfileType
import com.scluzlep.syncclipboard.model.ServerNode
import com.scluzlep.syncclipboard.model.ServerType
import com.scluzlep.syncclipboard.protocol.GroupArchiveCodec
import com.scluzlep.syncclipboard.protocol.OfficialClipboardClient
import com.scluzlep.syncclipboard.protocol.ProfileHashUtils
import com.scluzlep.syncclipboard.protocol.RemoteConflictException
import com.scluzlep.syncclipboard.protocol.SignalRClient
import com.scluzlep.syncclipboard.protocol.WebDavClipboardClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProtocolAndHashTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun textProfileThresholdAndNoBomUtf8() {
        val shortText = "Hello SyncClipboard 你好"
        val shortParts = ProfileHashUtils.prepareTextProfile(shortText)
        assertFalse(shortParts.hasTransferData)
        assertEquals(shortText, shortParts.inlineText)
        assertEquals(shortText.length.toLong(), shortParts.utf16Size)
        assertEquals(64, shortParts.fullHashUpper.length)
        assertEquals(shortParts.fullHashUpper, shortParts.fullHashUpper.uppercase())

        val longText = "A".repeat(10240) + "\uD83D\uDE00tail" // > 10240 UTF-16 code units
        val longParts = ProfileHashUtils.prepareTextProfile(longText)
        assertTrue(longParts.hasTransferData)
        assertEquals(10240, longParts.inlineText.length)
        assertEquals(longText.length.toLong(), longParts.utf16Size)
        assertEquals(ProfileHashUtils.sha256Hex(longText), longParts.fullHashUpper)
        assertTrue(longParts.transferDataName!!.endsWith(".txt"))

        val txtFile = tempFolder.newFile("long.txt")
        ProfileHashUtils.writeUtf8NoBom(txtFile, longText)
        val rawBytes = txtFile.readBytes()
        // Verify no UTF-8 BOM (EF BB BF)
        assertFalse(rawBytes.size >= 3 && rawBytes[0] == 0xEF.toByte() && rawBytes[1] == 0xBB.toByte() && rawBytes[2] == 0xBF.toByte())
        assertEquals(longText, ProfileHashUtils.readUtf8NoBom(txtFile))
        assertEquals(longParts.fullHashUpper, ProfileHashUtils.sha256FileHex(txtFile))
    }

    @Test
    fun fileAndImageCombineHashAndPromotion() {
        val bytes = "PNG-mock-content".toByteArray()
        val contentSha = ProfileHashUtils.sha256Hex(bytes)
        val combined = ProfileHashUtils.combineHash("photo.PNG", contentSha)
        assertEquals(ProfileHashUtils.sha256Hex("photo.PNG|$contentSha"), combined)

        assertTrue(ProfileHashUtils.isImageFileName("a.jpg"))
        assertTrue(ProfileHashUtils.isImageFileName("a.JPEG"))
        assertTrue(ProfileHashUtils.isImageFileName("a.gif"))
        assertTrue(ProfileHashUtils.isImageFileName("a.bmp"))
        assertTrue(ProfileHashUtils.isImageFileName("a.png"))
        assertFalse(ProfileHashUtils.isImageFileName("a.pdf"))

        assertEquals(ProfileType.Image, ProfileHashUtils.resolveEffectiveProfileType(ProfileType.File, "clip.png"))
        assertEquals(ProfileType.File, ProfileHashUtils.resolveEffectiveProfileType(ProfileType.File, "doc.pdf"))
        assertEquals("_CON.txt", ProfileHashUtils.safeFileName("CON.txt"))
        assertEquals("a_b_c", ProfileHashUtils.safeFileName("a<b>c"))
    }

    @Test
    fun titleAndTagsNormalization() {
        val decomposed = "e\u0301" // NFD
        val normalizedTitle = ProfileHashUtils.normalizeTitle("  $decomposed title  ")
        assertEquals("\u00e9 title", normalizedTitle)

        try {
            ProfileHashUtils.normalizeTitle("a".repeat(257))
            fail("Expected title > 256 runes to throw")
        } catch (_: IllegalArgumentException) {
        }

        val tags = ProfileHashUtils.normalizeTags(listOf(" b ", "e\u0301", "a", "\u00e9"))
        assertEquals(listOf("a", "b", "\u00e9"), tags)

        try {
            ProfileHashUtils.normalizeTags((1..33).map { "tag$it" })
            fail("Expected > 32 tags to throw")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun groupArchiveUnsignedUtf8SortAndPackUnpack() {
        // Unsigned UTF-8 byte order puts 'z.txt' (0x7A) BEFORE '中.txt' (0xE4 0xB8 0xAD),
        // whereas signed byte comparison would get this reversed.
        val root = tempFolder.newFolder("group_src")
        val sub = File(root, "sub").apply { mkdirs() }
        val fileAscii = File(root, "z.txt").apply { writeText("ascii") }
        val fileUtf8 = File(root, "中.txt").apply { writeText("utf8") }
        val fileNested = File(sub, "nested.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        val zipOut = File(tempFolder.root, "bundle.zip")
        val packRes = GroupArchiveCodec.packGroup(listOf(fileUtf8, fileAscii, sub), zipOut)

        val hAscii = ProfileHashUtils.sha256FileHex(fileAscii)
        val hUtf8 = ProfileHashUtils.sha256FileHex(fileUtf8)
        val hNested = ProfileHashUtils.sha256FileHex(fileNested)
        val expectedConcat =
            "D|sub/\u0000" +
                "F|sub/nested.bin|4|$hNested\u0000" +
                "F|z.txt|5|$hAscii\u0000" +
                "F|中.txt|4|$hUtf8\u0000"
        assertEquals(ProfileHashUtils.sha256Hex(expectedConcat), packRes.profileHashUpper)

        val unpackDir = tempFolder.newFolder("group_out")
        val unpackRes = GroupArchiveCodec.unpackGroup(zipOut, unpackDir, expectedProfileHash = packRes.profileHashUpper)
        assertEquals(packRes.profileHashUpper, unpackRes.profileHashUpper)
        assertEquals("ascii", File(unpackDir, "z.txt").readText())
        assertEquals("utf8", File(unpackDir, "中.txt").readText())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(unpackDir, "sub/nested.bin").readBytes())
    }

    @Test
    fun groupArchiveRejectsZipSlip() {
        val maliciousZip = tempFolder.newFile("evil.zip")
        ZipOutputStream(FileOutputStream(maliciousZip)).use { zos ->
            zos.putNextEntry(ZipEntry("../escape.txt"))
            zos.write("pwn".toByteArray())
            zos.closeEntry()
        }

        val outDir = tempFolder.newFolder("safe_out")
        try {
            GroupArchiveCodec.unpackGroup(maliciousZip, outDir)
            fail("Expected SecurityException on Zip-Slip entry")
        } catch (_: SecurityException) {
        }
        assertFalse(File(tempFolder.root, "escape.txt").exists())
    }

    @Test
    fun officialClientCapabilities404Fallback() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("\"3.2.0\""))

            val node = ServerNode(
                id = "n1",
                name = "Test",
                serverType = ServerType.OFFICIAL,
                baseUrl = server.url("/").toString(),
                allowCleartext = true
            )
            OfficialClipboardClient(node).use { client ->
                val caps = client.getCapabilities()
                assertTrue(caps.historySync)
                assertFalse(caps.historyTags)
                assertFalse(caps.historyTitle)
            }
            assertEquals("/api/capabilities", server.takeRequest().path)
            assertEquals("/api/version", server.takeRequest().path)
        }
    }

    @Test
    fun officialClientPostHistoryDataLastPartAndTransferHashHeader() = runBlocking {
        MockWebServer().use { server ->
            val payloadFile = tempFolder.newFile("sample.bin").apply {
                writeBytes("binary-transfer-payload".toByteArray())
            }
            val expectedDataSha = ProfileHashUtils.sha256FileHex(payloadFile)
            val recordHash = ProfileHashUtils.combineHash(payloadFile.name, expectedDataSha)

            server.enqueue(
                MockResponse().setBody(
                    JSONObject()
                        .put("hash", recordHash)
                        .put("type", "File")
                        .put("text", payloadFile.name)
                        .put("createTime", "2026-11-25T00:00:00Z")
                        .put("lastModified", "2026-11-25T00:00:00Z")
                        .put("lastAccessed", "2026-11-25T00:00:00Z")
                        .put("size", payloadFile.length())
                        .put("hasData", true)
                        .put("version", 1)
                        .toString()
                )
            )

            val node = ServerNode(
                id = "n1",
                name = "Test",
                serverType = ServerType.OFFICIAL,
                baseUrl = server.url("/").toString(),
                username = "u",
                password = "p",
                allowCleartext = true
            )

            OfficialClipboardClient(node).use { client ->
                val dto = HistoryRecordDto(
                    hash = recordHash,
                    type = ProfileType.File,
                    text = payloadFile.name,
                    createTime = "2026-11-25T00:00:00Z",
                    lastModified = "2026-11-25T00:00:00Z",
                    lastAccessed = "2026-11-25T00:00:00Z",
                    tags = listOf("work"),
                    title = "Sample",
                    size = payloadFile.length(),
                    hasData = true
                )
                val returned = client.uploadHistory(dto, payloadFile, expectedDataSha)
                assertEquals(recordHash, returned.hash)
            }

            val req = server.takeRequest()
            assertEquals("/api/history", req.path)
            assertEquals(expectedDataSha, req.getHeader(OfficialClipboardClient.HEADER_TRANSFER_DATA_HASH))
            val bodyStr = req.body.readUtf8()
            val dataIdx = bodyStr.indexOf("name=\"data\"")
            val hashIdx = bodyStr.indexOf("name=\"hash\"")
            val tagsIdx = bodyStr.indexOf("name=\"tags\"")
            assertTrue(hashIdx >= 0 && tagsIdx >= 0 && dataIdx > hashIdx && dataIdx > tagsIdx)
            val afterData = bodyStr.substring(dataIdx)
            assertFalse("data must be the final multipart part", afterData.contains("name=\"title\""))
        }
    }

    @Test
    fun officialClientPatchTagsNullVsEmptyAndConflict() = runBlocking {
        MockWebServer().use { server ->
            // 1. tags = null -> omitted from JSON
            server.enqueue(MockResponse().setBody("""{"version":2}"""))
            // 2. tags = emptyList() -> explicit "tags": []
            server.enqueue(MockResponse().setBody("""{"tags":[],"version":3}"""))
            // 3. 409 Conflict -> RemoteConflictException with serverUpdate
            server.enqueue(
                MockResponse().setResponseCode(409)
                    .setBody("""{"tags":["srv"],"title":"ServerTitle","starred":true,"version":5}""")
            )

            val node = ServerNode(
                id = "n1",
                name = "Test",
                serverType = ServerType.OFFICIAL,
                baseUrl = server.url("/").toString(),
                allowCleartext = true
            )

            OfficialClipboardClient(node).use { client ->
                client.patchHistory(
                    ProfileType.Text,
                    "A".repeat(64),
                    HistoryRecordUpdateDto(tags = null, starred = true, version = 2)
                )
                val req1 = server.takeRequest()
                val json1 = JSONObject(req1.body.readUtf8())
                assertFalse("tags=null must omit 'tags' key", json1.has("tags"))
                assertTrue(json1.getBoolean("starred"))

                client.patchHistory(
                    ProfileType.Text,
                    "A".repeat(64),
                    HistoryRecordUpdateDto(tags = emptyList(), version = 3)
                )
                val req2 = server.takeRequest()
                val json2 = JSONObject(req2.body.readUtf8())
                assertTrue("tags=emptyList() must include 'tags' key", json2.has("tags"))
                assertEquals(0, json2.getJSONArray("tags").length())

                try {
                    client.patchHistory(
                        ProfileType.Text,
                        "A".repeat(64),
                        HistoryRecordUpdateDto(starred = false, version = 4)
                    )
                    fail("Expected RemoteConflictException on 409")
                } catch (ex: RemoteConflictException) {
                    assertNotNull(ex.serverUpdate)
                    assertEquals(listOf("srv"), ex.serverUpdate?.tags)
                    assertEquals("ServerTitle", ex.serverUpdate?.title)
                    assertEquals(5, ex.serverUpdate?.version)
                }
            }
        }
    }

    @Test
    fun officialClientDownloadHistoryDataVerifiesSha256() = runBlocking {
        MockWebServer().use { server ->
            val content = "verified-history-binary".toByteArray()
            val goodSha = ProfileHashUtils.sha256Hex(content)
            server.enqueue(
                MockResponse()
                    .addHeader(OfficialClipboardClient.HEADER_TRANSFER_DATA_HASH, goodSha)
                    .setBody(okio.Buffer().write(content))
            )
            server.enqueue(
                MockResponse()
                    .addHeader(OfficialClipboardClient.HEADER_TRANSFER_DATA_HASH, "0".repeat(64))
                    .setBody(okio.Buffer().write(content))
            )

            val node = ServerNode(
                id = "n1",
                name = "Test",
                serverType = ServerType.OFFICIAL,
                baseUrl = server.url("/").toString(),
                allowCleartext = true
            )
            OfficialClipboardClient(node).use { client ->
                val okDest = File(tempFolder.root, "ok.bin")
                val actualSha = client.downloadHistoryData(ProfileType.File, "B".repeat(64), okDest)
                assertEquals(goodSha, actualSha)
                assertArrayEquals(content, okDest.readBytes())

                val badDest = File(tempFolder.root, "bad.bin")
                try {
                    client.downloadHistoryData(ProfileType.File, "B".repeat(64), badDest)
                    fail("Expected SHA-256 mismatch IOException")
                } catch (_: Exception) {
                }
                assertFalse(badDest.exists())
            }
        }
    }

    @Test
    fun officialQueryAndClearAndWebDavClient() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"hash":"${"C".repeat(64)}","type":"Text","text":"hi","createTime":"2026-11-25T00:00:00Z","lastModified":"2026-11-25T00:00:00Z","lastAccessed":"2026-11-25T00:00:00Z","tags":[],"title":"T","starred":false,"pinned":false,"size":2,"hasData":false,"version":1,"isDeleted":false}]"""))
            server.enqueue(MockResponse().setBody("""{"deleted":7}"""))

            val node = ServerNode(
                id = "n1",
                name = "Test",
                serverType = ServerType.OFFICIAL,
                baseUrl = server.url("/").toString(),
                allowCleartext = true
            )
            OfficialClipboardClient(node).use { client ->
                val list = client.queryHistory(HistoryQueryParams(page = 1, searchText = "hi", searchTitles = true))
                assertEquals(1, list.size)
                assertEquals(emptyList<String>(), list[0].tags)
                assertEquals("T", list[0].title)
                assertEquals(7, client.clearAllHistory())
            }

            server.enqueue(MockResponse().setResponseCode(207))
            server.enqueue(MockResponse().setResponseCode(200).addHeader("ETag", "\"etag-1\"").setBody("""{"type":"Text","hash":"${"D".repeat(64)}","text":"dav","hasData":false}"""))
            server.enqueue(MockResponse().setResponseCode(412))

            val davNode = node.copy(serverType = ServerType.WEBDAV)
            WebDavClipboardClient(davNode).use { dav ->
                dav.testConnection()
                val snap = dav.getProfile()
                assertEquals("\"etag-1\"", snap.etag)
                assertEquals("dav", snap.profile.text)
                assertFalse(dav.putProfileIfMatch(ProfileDto(text = "new"), "\"etag-1\""))
            }
        }
    }

    @Test
    fun signalRClientHandshakeAndEventDispatch() = runBlocking {
        MockWebServer().use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val httpClient = OkHttpClient()
            try {
                server.enqueue(MockResponse().setBody("""{"connectionToken":"tok123","negotiateVersion":1}"""))
                server.enqueue(
                    MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (text.contains("\"protocol\":\"json\"")) {
                                webSocket.send("{}\u001e")
                                webSocket.send(
                                    """{"type":1,"target":"RemoteProfileChanged","arguments":[{"type":"Text","hash":"${"E".repeat(64)}","text":"push","hasData":false}]}""" + "\u001e" +
                                        """{"type":1,"target":"RemoteHistoryChanged","arguments":[{"hash":"${"E".repeat(64)}","type":"Text","text":"push","createTime":"2026-11-25T00:00:00Z","lastModified":"2026-11-25T00:00:00Z","lastAccessed":"2026-11-25T00:00:00Z","version":1}]}""" + "\u001e"
                                )
                            }
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }
                    })
                )

                val connected = CompletableDeferred<Unit>()
                val profileEvent = CompletableDeferred<ProfileDto>()
                val historyEvent = CompletableDeferred<HistoryRecordDto>()

                val node = ServerNode(
                    id = "sr",
                    name = "SignalR",
                    serverType = ServerType.OFFICIAL,
                    baseUrl = server.url("/").toString(),
                    allowCleartext = true
                )
                val client = SignalRClient(
                    node = node,
                    httpClient = httpClient,
                    scope = scope,
                    listener = object : SignalRClient.Listener {
                        override fun onConnected() {
                            connected.complete(Unit)
                        }

                        override fun onProfileChanged(profile: ProfileDto) {
                            profileEvent.complete(profile)
                        }

                        override fun onHistoryChanged(record: HistoryRecordDto) {
                            historyEvent.complete(record)
                        }

                        override fun onDisconnected(error: Throwable?) {}
                    }
                )
                client.connect()
                withTimeout(5000) {
                    connected.await()
                    assertEquals("push", profileEvent.await().text)
                    assertEquals("E".repeat(64), historyEvent.await().hash)
                }
                client.disconnect()
            } finally {
                httpClient.dispatcher.executorService.shutdown()
                httpClient.connectionPool.evictAll()
                scope.cancel()
            }
        }
    }
}
