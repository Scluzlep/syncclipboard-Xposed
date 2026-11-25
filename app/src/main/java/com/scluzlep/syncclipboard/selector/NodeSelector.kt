package com.scluzlep.syncclipboard.selector

import com.scluzlep.syncclipboard.model.NodeProbeResult
import com.scluzlep.syncclipboard.model.SelectorConfig
import com.scluzlep.syncclipboard.model.SelectorMode
import com.scluzlep.syncclipboard.model.ServerNode
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure single-active multi-node selector supporting:
 * - [SelectorMode.MANUAL]: locks onto `manualNodeId`
 * - [SelectorMode.FALLBACK]: ascending priority failover (`failOverThreshold`) + `recoveryHoldSec` stabilization
 * - [SelectorMode.URL_TEST]: lowest-RTT selection with `urlTestMinGainMs` hysteresis
 * - [SelectorMode.RULE_AUTO]: network transport + IPv4/IPv6 CIDR (+ optional SSID) rule matching with priority tie-breaker
 */
class NodeSelector(
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private val consecutiveFailures = ConcurrentHashMap<String, Int>()
    private var recoveryCandidateId: String? = null
    private var recoveryCandidateHealthySinceMs: Long = 0L

    fun recordOutcome(nodeId: String, success: Boolean) {
        synchronized(lock) {
            if (success) {
                consecutiveFailures[nodeId] = 0
            } else {
                val next = (consecutiveFailures[nodeId] ?: 0) + 1
                consecutiveFailures[nodeId] = next
                if (recoveryCandidateId == nodeId) {
                    recoveryCandidateId = null
                    recoveryCandidateHealthySinceMs = 0L
                }
            }
        }
    }

    fun failureCount(nodeId: String): Int = consecutiveFailures[nodeId] ?: 0

    fun resetState() {
        synchronized(lock) {
            consecutiveFailures.clear()
            recoveryCandidateId = null
            recoveryCandidateHealthySinceMs = 0L
        }
    }

    fun selectActiveNode(
        nodes: List<ServerNode>,
        selectorConfig: SelectorConfig,
        currentActiveNodeId: String?,
        probeResults: Map<String, NodeProbeResult>,
        networkSnapshot: NetworkSnapshot
    ): ServerNode? {
        if (nodes.isEmpty()) return null
        val enabledNodes = nodes.filter { it.enabled }
        if (enabledNodes.isEmpty()) return null

        return when (selectorConfig.mode) {
            SelectorMode.MANUAL -> selectManual(nodes, enabledNodes, selectorConfig, currentActiveNodeId)
            SelectorMode.FALLBACK -> selectFallback(enabledNodes, selectorConfig, currentActiveNodeId, probeResults)
            SelectorMode.URL_TEST -> selectUrlTest(enabledNodes, selectorConfig, currentActiveNodeId, probeResults)
            SelectorMode.RULE_AUTO -> selectRuleAuto(enabledNodes, selectorConfig, currentActiveNodeId, probeResults, networkSnapshot)
        }
    }

    private fun selectManual(
        allNodes: List<ServerNode>,
        enabledNodes: List<ServerNode>,
        selectorConfig: SelectorConfig,
        currentActiveNodeId: String?
    ): ServerNode? {
        val targetId = selectorConfig.manualNodeId ?: currentActiveNodeId
        if (targetId != null) {
            enabledNodes.firstOrNull { it.id == targetId }?.let { return it }
            // Keep explicit manual selection if present in allNodes & enabled wasn't violated; otherwise pick first enabled.
            allNodes.firstOrNull { it.id == targetId && it.enabled }?.let { return it }
        }
        return enabledNodes.firstOrNull()
    }

    private fun selectFallback(
        enabledNodes: List<ServerNode>,
        selectorConfig: SelectorConfig,
        currentActiveNodeId: String?,
        probeResults: Map<String, NodeProbeResult>
    ): ServerNode? = synchronized(lock) {
        val sorted = enabledNodes.sortedBy { it.priority }
        val threshold = selectorConfig.failOverThreshold.coerceAtLeast(1)
        val holdMs = selectorConfig.recoveryHoldSec.coerceAtLeast(0) * 1000L
        val now = nowProvider()

        val current = currentActiveNodeId?.let { id -> sorted.firstOrNull { it.id == id } }
        if (current == null) {
            recoveryCandidateId = null
            recoveryCandidateHealthySinceMs = 0L
            return sorted.firstOrNull { isNodeHealthy(it.id, threshold, probeResults) } ?: sorted.firstOrNull()
        }

        val currentHealthy = isNodeHealthy(current.id, threshold, probeResults)
        val currentIdx = sorted.indexOfFirst { it.id == current.id }

        if (!currentHealthy) {
            recoveryCandidateId = null
            recoveryCandidateHealthySinceMs = 0L
            val healthyReplacement = sorted.firstOrNull {
                it.id != current.id && isNodeHealthy(it.id, threshold, probeResults)
            }
            return healthyReplacement ?: current
        }

        // Current node is healthy; check if a strictly higher-priority node has recovered and held stable.
        val higherPriorityHealthy = sorted
            .take(currentIdx)
            .firstOrNull { candidate ->
                val probe = probeResults[candidate.id]
                val probeOk = probe?.reachable == true
                val zeroFailures = (consecutiveFailures[candidate.id] ?: 0) == 0
                probeOk && zeroFailures
            }

        if (higherPriorityHealthy == null) {
            recoveryCandidateId = null
            recoveryCandidateHealthySinceMs = 0L
            return current
        }

        if (holdMs == 0L) {
            recoveryCandidateId = null
            recoveryCandidateHealthySinceMs = 0L
            return higherPriorityHealthy
        }

        if (recoveryCandidateId != higherPriorityHealthy.id) {
            recoveryCandidateId = higherPriorityHealthy.id
            recoveryCandidateHealthySinceMs = now
            return current
        }

        return if (now - recoveryCandidateHealthySinceMs >= holdMs) {
            recoveryCandidateId = null
            recoveryCandidateHealthySinceMs = 0L
            higherPriorityHealthy
        } else {
            current
        }
    }

    private fun selectUrlTest(
        enabledNodes: List<ServerNode>,
        selectorConfig: SelectorConfig,
        currentActiveNodeId: String?,
        probeResults: Map<String, NodeProbeResult>
    ): ServerNode? {
        val reachableNodes = enabledNodes.mapNotNull { node ->
            val probe = probeResults[node.id]
            val rtt = probe?.rttMs
            if (probe != null && probe.reachable && rtt != null) {
                node to rtt
            } else {
                null
            }
        }

        if (reachableNodes.isEmpty()) {
            return enabledNodes.firstOrNull { it.id == currentActiveNodeId }
                ?: enabledNodes.minByOrNull { it.priority }
        }

        val bestPair = reachableNodes.minWith(
            compareBy<Pair<ServerNode, Long>> { it.second }.thenBy { it.first.priority }
        )
        val currentNode = currentActiveNodeId?.let { id -> enabledNodes.firstOrNull { it.id == id } }
        val currentProbe = currentNode?.let { probeResults[it.id] }
        val currentRtt = currentProbe?.rttMs

        if (currentNode != null && currentProbe != null && currentProbe.reachable && currentRtt != null) {
            if (bestPair.first.id == currentNode.id) return currentNode
            val gainMs = currentRtt - bestPair.second
            return if (gainMs >= selectorConfig.urlTestMinGainMs) {
                bestPair.first
            } else {
                currentNode
            }
        }

        return bestPair.first
    }

    private fun selectRuleAuto(
        enabledNodes: List<ServerNode>,
        selectorConfig: SelectorConfig,
        currentActiveNodeId: String?,
        probeResults: Map<String, NodeProbeResult>,
        networkSnapshot: NetworkSnapshot
    ): ServerNode? {
        if (!networkSnapshot.isConnected) {
            return enabledNodes.firstOrNull { it.id == currentActiveNodeId }
                ?: enabledNodes.minByOrNull { it.priority }
        }

        val matched = enabledNodes.filter { NetworkRuleMatcher.matches(networkSnapshot, it.networkRule) }
        val specificMatched = matched.filter { NetworkRuleMatcher.hasSpecificRule(it.networkRule) }
        val pool = when {
            specificMatched.isNotEmpty() -> specificMatched
            matched.isNotEmpty() -> matched
            else -> {
                val genericFallback = enabledNodes.filter { !NetworkRuleMatcher.hasSpecificRule(it.networkRule) }
                genericFallback.ifEmpty { enabledNodes }
            }
        }

        val currentInPool = currentActiveNodeId?.let { id -> pool.firstOrNull { it.id == id } }
        return selectFallback(
            enabledNodes = pool,
            selectorConfig = selectorConfig,
            currentActiveNodeId = currentInPool?.id,
            probeResults = probeResults
        )
    }

    private fun isNodeHealthy(
        nodeId: String,
        failOverThreshold: Int,
        probeResults: Map<String, NodeProbeResult>
    ): Boolean {
        val failures = consecutiveFailures[nodeId] ?: 0
        if (failures >= failOverThreshold) return false
        val probe = probeResults[nodeId]
        if (probe != null && !probe.reachable && failures >= 1) return false
        return true
    }
}
