package com.scluzlep.syncclipboard.selector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.scluzlep.syncclipboard.model.NetworkTransportRule
import com.scluzlep.syncclipboard.model.NodeNetworkRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

data class NetworkSnapshot(
    val isConnected: Boolean = false,
    val transports: Set<NetworkTransportRule> = emptySet(),
    val localIpStrings: List<String> = emptyList(),
    val wifiSsid: String? = null,
    val hasLocationPermission: Boolean = false
)

object CidrMatcher {
    private val IPV4_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val IPV6_CHARS_REGEX = Regex("""^[0-9a-fA-F:]+(\.\d{1,3}){0,3}$""")

    fun matchesAny(ipStrings: List<String>, cidrAllowlist: List<String>): Boolean {
        if (cidrAllowlist.isEmpty()) return true
        if (ipStrings.isEmpty()) return false
        return ipStrings.any { ip ->
            cidrAllowlist.any { cidr -> matches(ip, cidr) }
        }
    }

    fun matches(ipLiteral: String, cidrExpression: String): Boolean {
        val cleanCidr = cidrExpression.trim()
        if (cleanCidr.isEmpty()) return false

        val parts = cleanCidr.split('/', limit = 2)
        val networkBytes = parseNumericIpBytes(parts[0]) ?: return false
        val ipBytes = parseNumericIpBytes(ipLiteral) ?: return false
        if (networkBytes.size != ipBytes.size) return false

        val maxBits = networkBytes.size * 8
        val prefixLen = if (parts.size == 2) {
            parts[1].trim().toIntOrNull() ?: return false
        } else {
            maxBits
        }
        if (prefixLen < 0 || prefixLen > maxBits) return false

        val fullBytes = prefixLen / 8
        val remainingBits = prefixLen % 8

        for (i in 0 until fullBytes) {
            if (ipBytes[i] != networkBytes[i]) return false
        }
        if (remainingBits > 0) {
            val mask = (0xFF shl (8 - remainingBits)) and 0xFF
            if ((ipBytes[fullBytes].toInt() and mask) != (networkBytes[fullBytes].toInt() and mask)) {
                return false
            }
        }
        return true
    }

    internal fun parseNumericIpBytes(rawIp: String): ByteArray? {
        val cleaned = rawIp.trim().substringBefore('%')
        if (cleaned.isEmpty()) return null
        val looksNumeric = IPV4_REGEX.matches(cleaned) ||
            (cleaned.contains(':') && IPV6_CHARS_REGEX.matches(cleaned))
        if (!looksNumeric) return null
        return runCatching { InetAddress.getByName(cleaned).address }.getOrNull()
    }
}

object NetworkRuleMatcher {
    private val ALL_TRANSPORTS = setOf(
        NetworkTransportRule.WIFI,
        NetworkTransportRule.CELLULAR,
        NetworkTransportRule.VPN,
        NetworkTransportRule.ETHERNET
    )

    fun hasSpecificRule(rule: NodeNetworkRule): Boolean {
        val transportRestricted = rule.allowedTransports.isNotEmpty() &&
            rule.allowedTransports != ALL_TRANSPORTS
        return transportRestricted ||
            rule.cidrAllowlist.isNotEmpty() ||
            rule.ssidAllowlist.isNotEmpty()
    }

    fun matches(snapshot: NetworkSnapshot, rule: NodeNetworkRule): Boolean {
        if (!snapshot.isConnected) return false

        if (rule.allowedTransports.isNotEmpty()) {
            if (snapshot.transports.none { it in rule.allowedTransports }) {
                return false
            }
        }

        if (rule.cidrAllowlist.isNotEmpty()) {
            if (!CidrMatcher.matchesAny(snapshot.localIpStrings, rule.cidrAllowlist)) {
                return false
            }
        }

        // SSID matching is strictly optional: only enforced when SSID was actually resolved
        // (i.e., user granted optional location permission and device is on Wi-Fi).
        if (rule.ssidAllowlist.isNotEmpty()) {
            val currentSsid = snapshot.wifiSsid?.trim()?.takeIf { it.isNotEmpty() }
            if (currentSsid != null && NetworkTransportRule.WIFI in snapshot.transports) {
                if (rule.ssidAllowlist.none { it.trim() == currentSsid }) {
                    return false
                }
            }
        }

        return true
    }
}

/**
 * Monitors active default network via [ConnectivityManager.registerDefaultNetworkCallback]
 * without requiring location permission (CIDR + transport matching work permission-free;
 * optional SSID matching only activates when the user explicitly grants fine location).
 */
class NetworkMonitor(private val context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val lock = Any()
    private var started = false
    private var activeNetwork: Network? = null
    private var lastCapabilities: NetworkCapabilities? = null
    private var lastLinkProperties: LinkProperties? = null

    private val _networkFlow = MutableStateFlow(NetworkSnapshot())
    val networkFlow: StateFlow<NetworkSnapshot> = _networkFlow.asStateFlow()

    val currentSnapshot: NetworkSnapshot
        get() = _networkFlow.value

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(lock) {
                activeNetwork = network
                lastCapabilities = connectivityManager?.getNetworkCapabilities(network)
                lastLinkProperties = connectivityManager?.getLinkProperties(network)
            }
            publishSnapshot()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            synchronized(lock) {
                if (activeNetwork == null || activeNetwork == network) {
                    activeNetwork = network
                    lastCapabilities = networkCapabilities
                }
            }
            publishSnapshot()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            synchronized(lock) {
                if (activeNetwork == null || activeNetwork == network) {
                    activeNetwork = network
                    lastLinkProperties = linkProperties
                }
            }
            publishSnapshot()
        }

        override fun onLost(network: Network) {
            synchronized(lock) {
                if (activeNetwork == network) {
                    activeNetwork = null
                    lastCapabilities = null
                    lastLinkProperties = null
                }
            }
            publishSnapshot()
        }
    }

    fun start() {
        val cm = connectivityManager ?: return
        synchronized(lock) {
            if (started) return
            started = true
            val currentNet = cm.activeNetwork
            activeNetwork = currentNet
            if (currentNet != null) {
                lastCapabilities = cm.getNetworkCapabilities(currentNet)
                lastLinkProperties = cm.getLinkProperties(currentNet)
            }
        }
        publishSnapshot()
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
        }
    }

    fun stop() {
        val cm = connectivityManager ?: return
        synchronized(lock) {
            if (!started) return
            started = false
        }
        runCatching {
            cm.unregisterNetworkCallback(callback)
        }
    }

    fun refreshNow() {
        val cm = connectivityManager ?: return
        synchronized(lock) {
            val currentNet = cm.activeNetwork
            activeNetwork = currentNet
            if (currentNet != null) {
                lastCapabilities = cm.getNetworkCapabilities(currentNet)
                lastLinkProperties = cm.getLinkProperties(currentNet)
            } else {
                lastCapabilities = null
                lastLinkProperties = null
            }
        }
        publishSnapshot()
    }

    private fun publishSnapshot() {
        val snapshot = synchronized(lock) {
            val net = activeNetwork
            val caps = lastCapabilities
            val links = lastLinkProperties
            if (net == null || caps == null) {
                NetworkSnapshot(
                    isConnected = false,
                    hasLocationPermission = hasFineLocationPermission()
                )
            } else {
                val transports = extractTransports(caps)
                val ips = links?.linkAddresses
                    ?.mapNotNull { it.address?.hostAddress?.substringBefore('%') }
                    .orEmpty()
                val hasLoc = hasFineLocationPermission()
                val ssid = if (hasLoc && NetworkTransportRule.WIFI in transports) {
                    extractWifiSsid(caps)
                } else {
                    null
                }
                NetworkSnapshot(
                    isConnected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    transports = transports,
                    localIpStrings = ips,
                    wifiSsid = ssid,
                    hasLocationPermission = hasLoc
                )
            }
        }
        _networkFlow.value = snapshot
    }

    private fun extractTransports(caps: NetworkCapabilities): Set<NetworkTransportRule> {
        return buildSet {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(NetworkTransportRule.WIFI)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(NetworkTransportRule.CELLULAR)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(NetworkTransportRule.VPN)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(NetworkTransportRule.ETHERNET)
        }
    }

    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun extractWifiSsid(caps: NetworkCapabilities): String? {
        val fromCaps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (caps.transportInfo as? WifiInfo)?.ssid
        } else {
            null
        }
        val rawSsid = fromCaps ?: runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull()
        return cleanSsid(rawSsid)
    }

    companion object {
        internal fun cleanSsid(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim().removeSurrounding("\"")
            if (trimmed.isEmpty() || trimmed.equals("<unknown ssid>", ignoreCase = true)) {
                return null
            }
            return trimmed
        }
    }
}
