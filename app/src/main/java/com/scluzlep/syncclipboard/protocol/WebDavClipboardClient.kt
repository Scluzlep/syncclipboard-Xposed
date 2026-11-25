package com.scluzlep.syncclipboard.protocol

import com.scluzlep.syncclipboard.model.ProfileDto
import com.scluzlep.syncclipboard.model.ServerNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebDavClipboardClient(
    val node: ServerNode,
    private val httpClient: OkHttpClient = TlsHttpFactory.createClient(node),
    private val ownsClient: Boolean = true
) : ClipboardTransportClient {

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val OCTET_MEDIA = "application/octet-stream".toMediaType()
        private const val PROFILE_FILE = "SyncClipboard.json"
        private const val FILE_FOLDER = "file"
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
        val req = Request.Builder()
            .url(baseUrl)
            .method("PROPFIND", ByteArray(0).toRequestBody(null))
            .header("Depth", "1")
            .build()
        execute(req).use { resp ->
            if (!resp.isSuccessful && resp.code != 207) {
                throw RemoteProtocolException("WebDAV PROPFIND failed: HTTP ${resp.code}")
            }
        }
    }

    override suspend fun initialize() {
        if (directoryExists(FILE_FOLDER)) return
        val req = Request.Builder()
            .url(resolvePath(FILE_FOLDER))
            .method("MKCOL", ByteArray(0).toRequestBody(null))
            .build()
        execute(req).use { resp ->
            if (!resp.isSuccessful && resp.code != 405 && resp.code != 301) {
                throw RemoteProtocolException("WebDAV MKCOL /$FILE_FOLDER failed: HTTP ${resp.code}")
            }
        }
    }

    private suspend fun directoryExists(folder: String): Boolean {
        val url = resolvePath(folder).newBuilder().addPathSegment("").build()
        val req = Request.Builder()
            .url(url)
            .method("PROPFIND", ByteArray(0).toRequestBody(null))
            .header("Depth", "0")
            .build()
        return execute(req).use { resp ->
            resp.isSuccessful || resp.code == 207
        }
    }

    override suspend fun getProfile(): RemoteProfileSnapshot {
        val req = Request.Builder()
            .url(resolvePath(PROFILE_FILE))
            .get()
            .build()
        execute(req).use { resp ->
            if (resp.code == 404) {
                return RemoteProfileSnapshot(ProfileDto(), null)
            }
            if (!resp.isSuccessful) {
                throw RemoteProtocolException("WebDAV GET $PROFILE_FILE failed: HTTP ${resp.code}")
            }
            val body = resp.body?.string() ?: "{}"
            val etag = resp.header("ETag")
            val dto = if (body.isBlank()) ProfileDto() else parseProfileDto(JSONObject(body))
            return RemoteProfileSnapshot(dto, etag)
        }
    }

    override suspend fun setProfile(profile: ProfileDto) {
        val body = serializeProfileDto(profile).toString()
        val req = Request.Builder()
            .url(resolvePath(PROFILE_FILE))
            .put(body.toRequestBody(JSON_MEDIA))
            .build()
        execute(req).use { resp ->
            if (!resp.isSuccessful) {
                throw RemoteProtocolException("WebDAV PUT $PROFILE_FILE failed: HTTP ${resp.code}")
            }
        }
    }

    suspend fun putProfileIfMatch(profile: ProfileDto, expectedEtag: String): Boolean {
        val body = serializeProfileDto(profile).toString()
        val req = Request.Builder()
            .url(resolvePath(PROFILE_FILE))
            .header("If-Match", expectedEtag)
            .put(body.toRequestBody(JSON_MEDIA))
            .build()
        execute(req).use { resp ->
            if (resp.code == 409 || resp.code == 412) {
                return false
            }
            if (!resp.isSuccessful) {
                throw RemoteProtocolException("WebDAV conditional PUT failed: HTTP ${resp.code}")
            }
            return true
        }
    }

    override suspend fun uploadFile(fileName: String, localFile: File) {
        val req = Request.Builder()
            .url(resolvePath(FILE_FOLDER, fileName))
            .put(localFile.asRequestBody(OCTET_MEDIA))
            .build()
        execute(req).use { resp ->
            if (!resp.isSuccessful) {
                throw RemoteProtocolException("WebDAV PUT file/$fileName failed: HTTP ${resp.code}")
            }
        }
    }

    override suspend fun downloadFile(fileName: String, destFile: File) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(resolvePath(FILE_FOLDER, fileName))
            .get()
            .build()
        execute(req).use { resp ->
            if (resp.code == 404) {
                throw RemoteNotFoundException("WebDAV file not found: $fileName")
            }
            if (!resp.isSuccessful) {
                throw RemoteProtocolException("WebDAV GET file/$fileName failed: HTTP ${resp.code}")
            }
            val body = resp.body ?: throw RemoteProtocolException("Empty WebDAV response body")
            destFile.parentFile?.mkdirs()
            val tempFile = File(destFile.parentFile, "${destFile.name}.${UUID.randomUUID()}.tmp")
            try {
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output, bufferSize = 81920)
                    }
                }
                if (destFile.exists()) destFile.delete()
                if (!tempFile.renameTo(destFile)) {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }
            } catch (t: Throwable) {
                tempFile.delete()
                throw t
            }
        }
        Unit
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

    override fun close() {
        if (ownsClient) {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        }
    }
}
