package com.scluzlep.syncclipboard.protocol

import com.scluzlep.syncclipboard.model.HistoryQueryParams
import com.scluzlep.syncclipboard.model.HistoryRecordDto
import com.scluzlep.syncclipboard.model.HistoryRecordUpdateDto
import com.scluzlep.syncclipboard.model.HistoryStatisticsDto
import com.scluzlep.syncclipboard.model.ProfileDto
import com.scluzlep.syncclipboard.model.ProfileType
import com.scluzlep.syncclipboard.model.ServerCapabilitiesDto
import com.scluzlep.syncclipboard.model.ServerNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OfficialClipboardClient(
    val node: ServerNode,
    private val httpClient: OkHttpClient = TlsHttpFactory.createClient(node),
    private val ownsClient: Boolean = true
) : ClipboardTransportClient {

    companion object {
        const val HEADER_TRANSFER_DATA_HASH = "X-SyncClipboard-Transfer-Data-Hash"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val OCTET_MEDIA = "application/octet-stream".toMediaType()
    }

    private val baseUrl: HttpUrl = TlsHttpFactory.normalizeBaseUrl(node.baseUrl)

    private fun resolvePath(vararg segments: String): HttpUrl {
        val builder = baseUrl.newBuilder()
        for (segment in segments) {
            if (segment.isNotEmpty()) {
                builder.addPathSegment(segment)
            }
        }
        return builder.build()
    }

    override suspend fun testConnection() {
        getServerTime()
        getCapabilities()
    }

    override suspend fun initialize() {
        val req = Request.Builder()
            .url(resolvePath("file"))
            .method("MKCOL", ByteArray(0).toRequestBody(null))
            .build()
        execute(req).use { resp ->
            if (!resp.isSuccessful && resp.code != 405) {
                throw RemoteProtocolException("Failed to ensure remote file folder: HTTP ${resp.code}")
            }
        }
    }

    suspend fun getServerTime(): String {
        val req = Request.Builder().url(resolvePath("api", "time")).get().build()
        execute(req).use { resp ->
            ensureSuccess(resp)
            return resp.body?.string()?.trim()?.trim('"') ?: ""
        }
    }

    suspend fun getVersion(): String {
        val req = Request.Builder().url(resolvePath("api", "version")).get().build()
        execute(req).use { resp ->
            ensureSuccess(resp)
            return resp.body?.string()?.trim()?.trim('"') ?: ""
        }
    }

    suspend fun getCapabilities(): ServerCapabilitiesDto {
        val req = Request.Builder().url(resolvePath("api", "capabilities")).get().build()
        execute(req).use { resp ->
            if (resp.code == 404) {
                val version = getVersion()
                if (!ProfileHashUtils.isVersionAtLeast(version, ProfileHashUtils.MIN_COMPATIBLE_SERVER_VERSION)) {
                    throw RemoteProtocolException(
                        "Server version ($version) is lower than required (${ProfileHashUtils.MIN_COMPATIBLE_SERVER_VERSION})."
                    )
                }
                return ServerCapabilitiesDto(historySync = true, historyTags = false, historyTitle = false)
            }
            ensureSuccess(resp)
            val body = resp.body?.string() ?: "{}"
            val json = JSONObject(body)
            return ServerCapabilitiesDto(
                historySync = json.optBoolean("historySync", false),
                historyTags = json.optBoolean("historyTags", false),
                historyTitle = json.optBoolean("historyTitle", false)
            )
        }
    }

    override suspend fun getProfile(): RemoteProfileSnapshot {
        val req = Request.Builder().url(resolvePath("SyncClipboard.json")).get().build()
        execute(req).use { resp ->
            ensureSuccess(resp)
            val body = resp.body?.string() ?: "{}"
            val etag = resp.header("ETag")
            return RemoteProfileSnapshot(parseProfileDto(JSONObject(body)), etag)
        }
    }

    override suspend fun setProfile(profile: ProfileDto) {
        val json = serializeProfileDto(profile).toString()
        val req = Request.Builder()
            .url(resolvePath("SyncClipboard.json"))
            .put(json.toRequestBody(JSON_MEDIA))
            .build()
        execute(req).use { resp ->
            ensureSuccess(resp)
        }
    }

    override suspend fun uploadFile(fileName: String, localFile: File) {
        val req = Request.Builder()
            .url(resolvePath("file", fileName))
            .put(localFile.asRequestBody(OCTET_MEDIA))
            .build()
        execute(req).use { resp ->
            ensureSuccess(resp)
        }
    }

    override suspend fun downloadFile(fileName: String, destFile: File) {
        val req = Request.Builder()
            .url(resolvePath("file", fileName))
            .get()
            .build()
        streamDownload(req, destFile, expectedSha256 = null)
    }

    suspend fun queryHistory(params: HistoryQueryParams): List<HistoryRecordDto> {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("Page", params.page.coerceAtLeast(1).toString())
            .addFormDataPart("Before", params.before ?: "")
            .addFormDataPart("After", params.after ?: "")
            .addFormDataPart("ModifiedAfter", params.modifiedAfter ?: "")
            .addFormDataPart("Types", params.types.toString())
            .addFormDataPart("SearchText", params.searchText ?: "")
            .addFormDataPart("SearchTitles", params.searchTitles.toString())
            .addFormDataPart("Starred", params.starred?.toString() ?: "")
            .addFormDataPart("SortByLastAccessed", params.sortByLastAccessed.toString())
            .addFormDataPart("OnlyUntagged", params.onlyUntagged.toString())

        if (params.tags != null) {
            multipart.addFormDataPart("Tags", JSONArray(params.tags).toString())
        }

        val req = Request.Builder()
            .url(resolvePath("api", "history", "query"))
            .post(multipart.build())
            .build()

        execute(req).use { resp ->
            ensureSuccess(resp)
            val body = resp.body?.string() ?: "[]"
            val arr = JSONArray(body)
            return buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    add(parseHistoryRecordDto(arr.getJSONObject(i)))
                }
            }
        }
    }

    suspend fun getHistoryRecord(type: ProfileType, hash: String): HistoryRecordDto? {
        val profileId = "${type.name}-${hash.uppercase()}"
        val req = Request.Builder()
            .url(resolvePath("api", "history", profileId))
            .get()
            .build()
        execute(req).use { resp ->
            if (resp.code == 404) return null
            ensureSuccess(resp)
            val body = resp.body?.string() ?: return null
            val dto = parseHistoryRecordDto(JSONObject(body))
            return if (dto.isDeleted) null else dto
        }
    }

    suspend fun uploadHistory(
        record: HistoryRecordDto,
        dataFile: File? = null,
        transferDataHash: String? = null
    ): HistoryRecordDto {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("hash", record.hash.uppercase())
            .addFormDataPart("type", record.type.name)
            .addFormDataPart("createTime", record.createTime)
            .addFormDataPart("lastModified", record.lastModified)
            .addFormDataPart("lastAccessed", record.lastAccessed)
            .addFormDataPart("starred", record.starred.toString())
            .addFormDataPart("pinned", record.pinned.toString())
            .addFormDataPart("version", record.version.toString())
            .addFormDataPart("isDeleted", record.isDeleted.toString())
            .addFormDataPart("text", record.text)
            .addFormDataPart("size", record.size.toString())
            .addFormDataPart("hasData", (record.hasData || dataFile != null).toString())

        if (record.title != null) {
            multipart.addFormDataPart("title", record.title)
        }
        if (record.tags != null) {
            multipart.addFormDataPart("tags", JSONArray(record.tags).toString())
        }

        val reqBuilder = Request.Builder()
            .url(resolvePath("api", "history"))

        // Data file MUST be added as the final multipart section per HistoryController.cs
        if (dataFile != null) {
            val dataSha = ProfileHashUtils.normalizeSha256(transferDataHash)
                ?: ProfileHashUtils.sha256FileHex(dataFile)
            multipart.addFormDataPart(
                "data",
                dataFile.name,
                dataFile.asRequestBody(OCTET_MEDIA)
            )
            reqBuilder.header(HEADER_TRANSFER_DATA_HASH, dataSha)
        }

        val req = reqBuilder.post(multipart.build()).build()
        execute(req).use { resp ->
            if (resp.code == 409) {
                val body = resp.body?.string()
                val serverUpdate = body?.takeIf { it.isNotBlank() }?.let {
                    runCatching { parseHistoryRecordUpdateDto(JSONObject(it)) }.getOrNull()
                }
                throw RemoteConflictException(
                    serverUpdate,
                    "History record conflict for ${record.type}/${record.hash}"
                )
            }
            ensureSuccess(resp)
            val body = resp.body?.string()
                ?: throw RemoteProtocolException("Empty response from POST /api/history")
            return parseHistoryRecordDto(JSONObject(body))
        }
    }

    suspend fun patchHistory(
        type: ProfileType,
        hash: String,
        update: HistoryRecordUpdateDto
    ): HistoryRecordUpdateDto? {
        val json = JSONObject()
        // Explicit distinction: null omits key; emptyList() serializes "tags": []
        if (update.tags != null) {
            json.put("tags", JSONArray(update.tags))
        }
        if (update.title != null) {
            json.put("title", update.title)
        }
        if (update.starred != null) {
            json.put("starred", update.starred)
        }
        if (update.pinned != null) {
            json.put("pinned", update.pinned)
        }
        if (update.isDelete != null) {
            json.put("isDelete", update.isDelete)
        }
        if (update.version != null) {
            json.put("version", update.version)
        }
        if (update.lastModified != null) {
            json.put("lastModified", update.lastModified)
        }
        if (update.lastAccessed != null) {
            json.put("lastAccessed", update.lastAccessed)
        }

        val req = Request.Builder()
            .url(resolvePath("api", "history", type.name, hash.uppercase()))
            .patch(json.toString().toRequestBody(JSON_MEDIA))
            .build()

        execute(req).use { resp ->
            if (resp.code == 409) {
                val body = resp.body?.string()
                val serverUpdate = body?.takeIf { it.isNotBlank() }?.let {
                    runCatching { parseHistoryRecordUpdateDto(JSONObject(it)) }.getOrNull()
                }
                throw RemoteConflictException(serverUpdate, "History patch conflict for $type/$hash")
            }
            if (resp.code == 404) {
                throw RemoteNotFoundException("History record not found: $type/$hash")
            }
            ensureSuccess(resp)
            val body = resp.body?.string()?.trim()
            if (body.isNullOrEmpty()) return null
            return parseHistoryRecordUpdateDto(JSONObject(body))
        }
    }

    suspend fun downloadHistoryData(
        type: ProfileType,
        hash: String,
        destFile: File,
        expectedTransferDataHash: String? = null
    ): String {
        val profileId = "${type.name}-${hash.uppercase()}"
        val req = Request.Builder()
            .url(resolvePath("api", "history", profileId, "data"))
            .get()
            .build()
        return streamDownload(req, destFile, expectedTransferDataHash)
    }

    suspend fun getHistoryStatistics(): HistoryStatisticsDto {
        val req = Request.Builder().url(resolvePath("api", "history", "statistics")).get().build()
        execute(req).use { resp ->
            ensureSuccess(resp)
            val json = JSONObject(resp.body?.string() ?: "{}")
            return HistoryStatisticsDto(
                activeCount = json.optInt("activeCount", 0),
                starredCount = json.optInt("starredCount", 0),
                deletedCount = json.optInt("deletedCount", 0),
                totalCount = json.optInt("totalCount", 0),
                totalFileSizeMB = json.optDouble("totalFileSizeMB", 0.0)
            )
        }
    }

    suspend fun clearAllHistory(): Int {
        val req = Request.Builder().url(resolvePath("api", "history", "clear")).delete().build()
        execute(req).use { resp ->
            ensureSuccess(resp)
            val body = resp.body?.string()?.trim()
            if (body.isNullOrEmpty()) return 0
            return JSONObject(body).optInt("deleted", 0)
        }
    }

    private suspend fun streamDownload(
        request: Request,
        destFile: File,
        expectedSha256: String?
    ): String = withContext(Dispatchers.IO) {
        execute(request).use { resp ->
            ensureSuccess(resp)
            val body = resp.body ?: throw RemoteProtocolException("Empty download response body")
            val headerHash = resp.header(HEADER_TRANSFER_DATA_HASH)?.takeIf { it.isNotBlank() }?.let {
                ProfileHashUtils.normalizeSha256(it)
            }
            val expected = headerHash ?: expectedSha256?.let { ProfileHashUtils.normalizeSha256(it) }

            destFile.parentFile?.mkdirs()
            val tempFile = File(destFile.parentFile, "${destFile.name}.${UUID.randomUUID()}.tmp")
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(81920)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                        }
                    }
                }
                val actualSha = ProfileHashUtils.bytesToUpperHex(digest.digest())
                if (expected != null && !ProfileHashUtils.sha256Same(actualSha, expected)) {
                    throw IOException("Downloaded transfer data SHA-256 mismatch. Expected $expected, actual $actualSha")
                }
                if (destFile.exists()) destFile.delete()
                if (!tempFile.renameTo(destFile)) {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }
                actualSha
            } catch (t: Throwable) {
                tempFile.delete()
                throw t
            }
        }
    }

    private suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val call = httpClient.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            })
        }
    }

    private fun ensureSuccess(resp: Response) {
        if (resp.isSuccessful) return
        val body = runCatching { resp.body?.string() }.getOrNull().orEmpty()
        when (resp.code) {
            404 -> throw RemoteNotFoundException("HTTP 404: ${resp.request.url}")
            400, 422 -> throw RemoteRejectedException(resp.code, "HTTP ${resp.code}: $body")
            else -> throw RemoteProtocolException("HTTP ${resp.code} ${resp.message}: $body")
        }
    }

    override fun close() {
        if (ownsClient) {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        }
    }
}

internal fun parseProfileDto(json: JSONObject): ProfileDto {
    return ProfileDto(
        type = ProfileType.fromWire(json.optString("type", "Text")),
        hash = json.optString("hash", ""),
        text = json.optString("text", ""),
        hasData = json.optBoolean("hasData", false),
        dataName = json.optNullableString("dataName"),
        transferDataHash = json.optNullableString("transferDataHash"),
        size = if (json.has("size") && !json.isNull("size")) json.optLong("size") else null,
        syncedAt = json.optNullableString("syncedAt")
    )
}

internal fun serializeProfileDto(dto: ProfileDto): JSONObject {
    return JSONObject().apply {
        put("type", dto.type.name)
        put("hash", dto.hash)
        put("text", dto.text)
        put("hasData", dto.hasData)
        put("dataName", dto.dataName ?: JSONObject.NULL)
        if (dto.transferDataHash != null) put("transferDataHash", dto.transferDataHash)
        if (dto.size != null) put("size", dto.size)
        if (dto.syncedAt != null) put("syncedAt", dto.syncedAt)
    }
}

internal fun parseHistoryRecordDto(json: JSONObject): HistoryRecordDto {
    val tags = if (json.has("tags") && !json.isNull("tags")) {
        val arr = json.optJSONArray("tags")
        if (arr != null) List(arr.length()) { idx -> arr.optString(idx) } else null
    } else {
        null
    }
    return HistoryRecordDto(
        hash = json.optString("hash", "").uppercase(),
        type = ProfileType.fromWire(json.optString("type", "None")),
        text = json.optString("text", ""),
        createTime = json.optString("createTime", ""),
        lastModified = json.optString("lastModified", ""),
        lastAccessed = json.optString("lastAccessed", ""),
        tags = tags,
        title = json.optNullableString("title"),
        starred = json.optBoolean("starred", false),
        pinned = json.optBoolean("pinned", false),
        size = json.optLong("size", 0L),
        hasData = json.optBoolean("hasData", false),
        version = json.optInt("version", 0),
        isDeleted = json.optBoolean("isDeleted", false)
    )
}

internal fun parseHistoryRecordUpdateDto(json: JSONObject): HistoryRecordUpdateDto {
    val tags = if (json.has("tags") && !json.isNull("tags")) {
        val arr = json.optJSONArray("tags")
        if (arr != null) List(arr.length()) { idx -> arr.optString(idx) } else null
    } else {
        null
    }
    val isDelete = when {
        json.has("isDelete") && !json.isNull("isDelete") -> json.optBoolean("isDelete")
        json.has("isDeleted") && !json.isNull("isDeleted") -> json.optBoolean("isDeleted")
        else -> null
    }
    return HistoryRecordUpdateDto(
        tags = tags,
        title = json.optNullableString("title"),
        starred = if (json.has("starred") && !json.isNull("starred")) json.optBoolean("starred") else null,
        pinned = if (json.has("pinned") && !json.isNull("pinned")) json.optBoolean("pinned") else null,
        isDelete = isDelete,
        version = if (json.has("version") && !json.isNull("version")) json.optInt("version") else null,
        lastModified = json.optNullableString("lastModified"),
        lastAccessed = json.optNullableString("lastAccessed")
    )
}

private fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key)
}
