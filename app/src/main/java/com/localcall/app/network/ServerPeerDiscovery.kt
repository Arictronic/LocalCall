package com.localcall.app.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

data class ServerIncomingCallEvent(
    val sessionId: String,
    val callerId: String,
    val callerName: String,
    val relayHost: String,
    val relayPort: Int,
    val relayToken: String
)

data class ServerSessionEvent(
    val type: String,
    val sessionId: String,
    val reason: String
)

class ServerPeerDiscovery(
    private val context: Context,
    private val onPeerFound: (PeerInfo) -> Unit,
    private val onPeerLost: (String) -> Unit
) {
    companion object {
        const val DEFAULT_SERVER_PORT = 45700
        private const val TAG = "ServerPeerDiscovery"
        private const val POLL_INTERVAL_MS = 2_000L
        private const val SOCKET_TIMEOUT_MS = 3_000
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null

    private val knownPeers = mutableMapOf<String, PeerInfo>()

    var deviceName: String = Build.MODEL
    var callPort: Int = 45679
    var serverIp: String = ""
    var serverPort: Int = DEFAULT_SERVER_PORT
    var clientId: String = ""
    var onIncomingCallEvent: ((ServerIncomingCallEvent) -> Unit)? = null
    var onSessionEvent: ((ServerSessionEvent) -> Unit)? = null

    fun start() {
        stop()
        if (!isConfigured()) {
            Log.d(TAG, "Server mode is not configured")
            return
        }

        pollJob = scope.launch {
            while (coroutineContext.isActive) {
                val peers = fetchPeers()
                if (peers != null) {
                    updatePeers(peers)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        clearPeers()
    }

    fun getKnownPeers(): List<PeerInfo> = synchronized(knownPeers) {
        knownPeers.values.toList()
    }

    fun requestRefresh() {
        if (!isConfigured()) return
        scope.launch {
            val peers = fetchPeers()
            if (peers != null) {
                updatePeers(peers)
            }
        }
    }

    fun getLocalIpAddress(): String? {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wm.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ip).array()
                return java.net.InetAddress.getByAddress(bytes).hostAddress
            }
        } catch (_: Exception) {
        }

        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { iface -> !iface.isLoopback && iface.isUp && !iface.isVirtual }
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { addr ->
                    addr is Inet4Address &&
                        !addr.isLoopbackAddress &&
                        addr.hostAddress?.startsWith("169.") == false
                }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    private fun isConfigured(): Boolean {
        return serverIp.isNotBlank() &&
            serverPort in 1..65535 &&
            clientId.isNotBlank()
    }

    private fun fetchPeers(): List<PeerInfo>? {
        if (!isConfigured()) {
            clearPeers()
            return emptyList()
        }

        val safeName = sanitize(deviceName)
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(serverIp, serverPort), SOCKET_TIMEOUT_MS)
                socket.soTimeout = SOCKET_TIMEOUT_MS

                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                writer.println("REGISTER2|$clientId|$safeName|$callPort")

                val peers = mutableListOf<PeerInfo>()
                var hasPeerV2 = false
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line == "END") break

                    when {
                        line.startsWith("PEER2|") -> {
                            parsePeerV2(line)?.let {
                                hasPeerV2 = true
                                peers.add(it)
                            }
                        }
                        line.startsWith("PEER|") -> {
                            // Compatibility with old server format.
                            if (!hasPeerV2) {
                                parsePeerLegacy(line)?.let(peers::add)
                            }
                        }
                        line.startsWith("EVENT|") -> {
                            handleEventLine(line)
                        }
                    }
                }
                peers
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to poll server ${serverIp}:${serverPort}: ${e.message}")
            null
        }
    }

    private fun parsePeerV2(line: String): PeerInfo? {
        val parts = line.split('|')
        if (parts.size < 5) return null
        val peerId = parts[1]
        val name = parts[2]
        val ip = parts[3]
        val port = parts[4].toIntOrNull() ?: callPort
        if (peerId.isBlank() || ip.isBlank() || peerId == clientId) return null
        return PeerInfo(name = name, ip = ip, port = port, id = peerId, viaServer = true)
    }

    private fun parsePeerLegacy(line: String): PeerInfo? {
        val parts = line.split('|')
        if (parts.size < 4) return null
        val name = parts[1]
        val ip = parts[2]
        val port = parts[3].toIntOrNull() ?: callPort
        if (ip.isBlank()) return null
        return PeerInfo(name = name, ip = ip, port = port, id = ip, viaServer = true)
    }

    private fun handleEventLine(line: String) {
        val parts = line.split('|')
        if (parts.size < 3) return

        when (parts[1]) {
            "INCOMING" -> {
                if (parts.size < 8) return
                val sessionId = parts[2]
                val callerId = parts[3]
                val callerName = parts[4]
                val relayHost = parts[5]
                val relayPort = parts[6].toIntOrNull() ?: return
                val relayToken = parts[7]
                if (callerId.isBlank() || sessionId.isBlank() || relayToken.isBlank()) return
                onIncomingCallEvent?.invoke(
                    ServerIncomingCallEvent(
                        sessionId = sessionId,
                        callerId = callerId,
                        callerName = callerName.ifBlank { callerId },
                        relayHost = relayHost.ifBlank { serverIp },
                        relayPort = relayPort,
                        relayToken = relayToken
                    )
                )
            }
            "REJECTED", "ENDED" -> {
                val sessionId = parts[2]
                val reason = parts.getOrNull(3).orEmpty()
                if (sessionId.isBlank()) return
                onSessionEvent?.invoke(
                    ServerSessionEvent(
                        type = parts[1],
                        sessionId = sessionId,
                        reason = reason
                    )
                )
            }
        }
    }

    private fun updatePeers(freshPeers: List<PeerInfo>) {
        val removed = mutableListOf<String>()
        val added = mutableListOf<PeerInfo>()
        val freshById = freshPeers.associateBy { peerKey(it) }

        synchronized(knownPeers) {
            val removedKeys = knownPeers.keys.filter { it !in freshById.keys }
            removedKeys.forEach { key ->
                val peer = knownPeers.remove(key)
                if (peer != null) {
                    removed.add(key)
                }
            }

            freshPeers.forEach { peer ->
                val key = peerKey(peer)
                val existed = knownPeers.containsKey(key)
                knownPeers[key] = peer
                if (!existed) added.add(peer)
            }
        }

        removed.forEach(onPeerLost)
        added.forEach(onPeerFound)
    }

    private fun clearPeers() {
        val removedKeys = synchronized(knownPeers) {
            val keys = knownPeers.keys.toList()
            knownPeers.clear()
            keys
        }
        removedKeys.forEach(onPeerLost)
    }

    private fun peerKey(peer: PeerInfo): String {
        return if (peer.viaServer) "srv:${peer.id}" else "ip:${peer.ip}"
    }

    private fun sanitize(raw: String): String {
        return raw.replace("|", " ").replace("\n", " ").trim().ifEmpty { Build.MODEL }
    }
}
