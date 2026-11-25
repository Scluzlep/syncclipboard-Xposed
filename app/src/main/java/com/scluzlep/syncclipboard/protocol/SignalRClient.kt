package com.scluzlep.syncclipboard.protocol

import com.scluzlep.syncclipboard.model.HistoryRecordDto
import com.scluzlep.syncclipboard.model.ProfileDto
import com.scluzlep.syncclipboard.model.ServerNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SignalRClient(
    private val node: ServerNode,
    private val httpClient: OkHttpClient = TlsHttpFactory.createClient(node),
    private val scope: CoroutineScope,
    private val listener: Listener
) : AutoCloseable {

    interface Listener {
        fun onConnected()
        fun onProfileChanged(profile: ProfileDto)
        fun onHistoryChanged(record: HistoryRecordDto)
        fun onDisconnected(error: Throwable?)
    }

    companion object {
        const val RECORD_SEPARATOR = "\u001e"
        const val HUB_SEGMENT = "SyncClipboardHub"
        private const val PING_INTERVAL_MS = 15_000L
        private const val HANDSHAKE_JSON = """{"protocol":"json","version":1}"""
        private const val PING_JSON = """{"type":6}"""
    }

    private val baseUrl: HttpUrl = TlsHttpFactory.normalizeBaseUrl(node.baseUrl)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var pingJob: Job? = null

    @Volatile
    private var handshakeCompleted = false

    suspend fun connect() {
        if (closed.get()) return
        val wsUrl = negotiateOrFallbackUrl()
        val req = Request.Builder().url(wsUrl).build()

        webSocket = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handshakeCompleted = false
                webSocket.send(HANDSHAKE_JSON + RECORD_SEPARATOR)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingFrames(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopPing()
                if (!closed.get()) {
                    listener.onDisconnected(null)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                stopPing()
                if (!closed.get()) {
                    listener.onDisconnected(t)
                }
            }
        })
    }

    private suspend fun negotiateOrFallbackUrl(): String {
        val negotiateUrl = baseUrl.newBuilder()
            .addPathSegment(HUB_SEGMENT)
            .addPathSegment("negotiate")
            .addQueryParameter("negotiateVersion", "1")
            .build()

        val req = Request.Builder()
            .url(negotiateUrl)
            .post(ByteArray(0).toRequestBody(null))
            .build()

        val tokenOrId = runCatching {
            execute(req).use { resp ->
                if (!resp.isSuccessful) return@use null
                val json = JSONObject(resp.body?.string() ?: "{}")
                json.optString("connectionToken").takeIf { it.isNotBlank() }
                    ?: json.optString("connectionId").takeIf { it.isNotBlank() }
            }
        }.getOrNull()

        val hubBuilder = baseUrl.newBuilder().addPathSegment(HUB_SEGMENT)
        if (!tokenOrId.isNullOrBlank()) {
            hubBuilder.addQueryParameter("id", tokenOrId)
        }
        val httpTarget = hubBuilder.build().toString()
        return when {
            httpTarget.startsWith("https://", ignoreCase = true) -> "wss://" + httpTarget.substring(8)
            httpTarget.startsWith("http://", ignoreCase = true) -> "ws://" + httpTarget.substring(7)
            else -> httpTarget
        }
    }

    private fun handleIncomingFrames(ws: WebSocket, rawText: String) {
        val frames = rawText.split(RECORD_SEPARATOR)
        for (frame in frames) {
            if (frame.isBlank()) continue
            val json = runCatching { JSONObject(frame) }.getOrNull() ?: continue

            if (!handshakeCompleted) {
                val error = json.optString("error", "")
                if (error.isNotEmpty()) {
                    ws.close(1002, "Handshake error: $error")
                    listener.onDisconnected(RemoteProtocolException("SignalR handshake failed: $error"))
                    return
                }
                handshakeCompleted = true
                startPing(ws)
                listener.onConnected()
                continue
            }

            when (json.optInt("type", -1)) {
                1 -> {
                    val target = json.optString("target", "")
                    val args = json.optJSONArray("arguments") ?: continue
                    if (args.length() == 0) continue
                    val argObj = args.optJSONObject(0) ?: continue
                    when (target) {
                        "RemoteProfileChanged" -> listener.onProfileChanged(parseProfileDto(argObj))
                        "RemoteHistoryChanged" -> listener.onHistoryChanged(parseHistoryRecordDto(argObj))
                    }
                }
                7 -> {
                    val errMsg = json.optString("error", "Server closed SignalR connection")
                    ws.close(1000, "Close frame received")
                    listener.onDisconnected(RemoteProtocolException(errMsg))
                }
            }
        }
    }

    private fun startPing(ws: WebSocket) {
        stopPing()
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive && !closed.get()) {
                delay(PING_INTERVAL_MS)
                if (!ws.send(PING_JSON + RECORD_SEPARATOR)) {
                    break
                }
            }
        }
    }

    private fun stopPing() {
        pingJob?.cancel()
        pingJob = null
    }

    fun disconnect() {
        if (closed.compareAndSet(false, true)) {
            stopPing()
            webSocket?.close(1000, "Client disconnect")
            webSocket = null
        }
    }

    override fun close() {
        disconnect()
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
}
