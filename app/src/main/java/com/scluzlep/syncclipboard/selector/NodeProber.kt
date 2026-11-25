package com.scluzlep.syncclipboard.selector

import com.scluzlep.syncclipboard.model.NodeProbeResult
import com.scluzlep.syncclipboard.model.ServerCapabilitiesDto
import com.scluzlep.syncclipboard.model.ServerNode
import com.scluzlep.syncclipboard.model.ServerType
import com.scluzlep.syncclipboard.protocol.OfficialClipboardClient
import com.scluzlep.syncclipboard.protocol.RemoteNotFoundException
import com.scluzlep.syncclipboard.protocol.TlsHttpFactory
import com.scluzlep.syncclipboard.protocol.WebDavClipboardClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

interface ProbeBackend {
    suspend fun probe(node: ServerNode): NodeProbeResult
}

/**
 * Concurrently probes configured `v3.3.4` Official (`/api/capabilities` -> `/api/version` -> `/api/time`)
 * and WebDAV (`PROPFIND /`) nodes with a strict 3-second timeout.
 */
class NodeProber(
    private val timeoutSec: Long = PROBE_TIMEOUT_SEC,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val customBackend: ProbeBackend? = null
) {
    suspend fun probeNode(node: ServerNode): NodeProbeResult {
        customBackend?.let { return it.probe(node) }

        return withContext(Dispatchers.IO) {
            val startNs = System.nanoTime()
            runCatching {
                val httpClient = TlsHttpFactory.createClient(
                    node = node,
                    connectTimeoutSec = timeoutSec,
                    readTimeoutSec = timeoutSec,
                    writeTimeoutSec = timeoutSec,
                    callTimeoutSec = timeoutSec
                )
                when (node.serverType) {
                    ServerType.OFFICIAL -> {
                        OfficialClipboardClient(node, httpClient, ownsClient = true).use { client ->
                            var version: String? = null
                            val caps: ServerCapabilitiesDto? = try {
                                client.getCapabilities()
                            } catch (_: RemoteNotFoundException) {
                                // Fallback to /api/time if neither /api/capabilities nor /api/version exists
                                client.getServerTime()
                                null
                            }
                            if (caps != null && !caps.historyTags && !caps.historyTitle) {
                                // Version may have already been queried during 404 fallback; ignore errors
                                version = runCatching { client.getVersion() }.getOrNull()
                            }
                            val rttMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                            NodeProbeResult(
                                nodeId = node.id,
                                reachable = true,
                                rttMs = rttMs,
                                serverVersion = version,
                                capabilities = caps,
                                error = null,
                                probedAtMs = nowProvider()
                            )
                        }
                    }

                    ServerType.WEBDAV -> {
                        WebDavClipboardClient(node, httpClient, ownsClient = true).use { client ->
                            client.testConnection()
                            val rttMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                            NodeProbeResult(
                                nodeId = node.id,
                                reachable = true,
                                rttMs = rttMs,
                                serverVersion = null,
                                capabilities = null,
                                error = null,
                                probedAtMs = nowProvider()
                            )
                        }
                    }
                }
            }.getOrElse { err ->
                NodeProbeResult(
                    nodeId = node.id,
                    reachable = false,
                    rttMs = null,
                    serverVersion = null,
                    capabilities = null,
                    error = err.message ?: err.javaClass.simpleName,
                    probedAtMs = nowProvider()
                )
            }
        }
    }

    suspend fun probeAll(nodes: List<ServerNode>): Map<String, NodeProbeResult> = coroutineScope {
        if (nodes.isEmpty()) return@coroutineScope emptyMap()
        nodes.map { node ->
            async(Dispatchers.IO) {
                probeNode(node)
            }
        }.awaitAll().associateBy { it.nodeId }
    }

    companion object {
        const val PROBE_TIMEOUT_SEC = 3L
    }
}
