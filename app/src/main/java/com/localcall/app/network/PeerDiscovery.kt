package com.localcall.app.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import kotlin.coroutines.coroutineContext

/**
 * UDP-based peer discovery.
 *
 * Fixes for Pixel / Android 10+:
 *  1. Acquires WifiManager.MulticastLock so the WiFi chipset passes
 *     broadcast/multicast packets up to the app (Pixel drops them without it).
 *  2. Calculates the real subnet-broadcast address (e.g. 192.168.1.255)
 *     instead of 255.255.255.255, which is filtered by many Android builds.
 *  3. Also sends to the limited broadcast 255.255.255.255 as a fallback.
 *  4. Binds the listen socket to INADDR_ANY so it receives from all
 *     interfaces, not just the one we guessed.
 */
class PeerDiscovery(
    private val context: Context,
    private val onPeerFound: (PeerInfo) -> Unit,
    private val onPeerLost:  (String)   -> Unit
) {
    companion object {
        const val DISCOVERY_PORT        = 45678
        const val BROADCAST_INTERVAL_MS = 2_000L
        const val PEER_TIMEOUT_MS       = 8_000L
        const val MAGIC                 = "LOCALCALL_PEER"
        const val BUFFER_SIZE           = 256
        private const val TAG           = "PeerDiscovery"
    }

    private val scope           = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var broadcastJob: Job? = null
    private var listenJob:    Job? = null
    private var cleanupJob:   Job? = null

    private val knownPeers      = mutableMapOf<String, PeerInfo>()
    private val peerTimestamps  = mutableMapOf<String, Long>()

    // Acquired once; released on stop()
    private var multicastLock: WifiManager.MulticastLock? = null

    var deviceName: String = android.os.Build.MODEL
    var callPort:   Int    = 45679

    // ------------------------------------------------------------------
    // Start / Stop
    // ------------------------------------------------------------------

    fun start() {
        stop()
        acquireMulticastLock()
        listenJob    = scope.launch { listenLoop()    }
        broadcastJob = scope.launch { broadcastLoop() }
        cleanupJob   = scope.launch { cleanupLoop()   }
        Log.d(TAG, "PeerDiscovery started. Local IP: ${getLocalIpAddress()}")
    }

    fun stop() {
        broadcastJob?.cancel(); broadcastJob = null
        listenJob?.cancel();    listenJob    = null
        cleanupJob?.cancel();   cleanupJob   = null
        knownPeers.clear()
        peerTimestamps.clear()
        releaseMulticastLock()
    }

    // ------------------------------------------------------------------
    // MulticastLock — REQUIRED on Pixel / most modern Android
    // ------------------------------------------------------------------

    private fun acquireMulticastLock() {
        try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("localcall_mdns").also {
                it.setReferenceCounted(true)
                it.acquire()
                Log.d(TAG, "MulticastLock acquired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "MulticastLock failed", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
            multicastLock = null
        } catch (e: Exception) { /* ignore */ }
    }

    // ------------------------------------------------------------------
    // Broadcast loop — sends to BOTH subnet broadcast and 255.255.255.255
    // ------------------------------------------------------------------

    private suspend fun broadcastLoop() {
        val socket = DatagramSocket().apply {
            broadcast  = true
            soTimeout  = 1_000
        }
        try {
            while (coroutineContext.isActive) {
                val localIp   = getLocalIpAddress()
                val message   = "$MAGIC|$deviceName|${localIp ?: ""}|$callPort"
                val data      = message.toByteArray(Charsets.UTF_8)

                // 1) Subnet-directed broadcast  (e.g. 192.168.1.255)
                val subnetBcast = getSubnetBroadcastAddress()
                if (subnetBcast != null) {
                    trySend(socket, data, subnetBcast)
                }

                // 2) Limited broadcast
                trySend(socket, data, InetAddress.getByName("255.255.255.255"))

                Log.v(TAG, "Broadcast sent. subnet=$subnetBcast local=$localIp")
                delay(BROADCAST_INTERVAL_MS)
            }
        } finally {
            socket.close()
        }
    }

    private fun trySend(socket: DatagramSocket, data: ByteArray, addr: InetAddress) {
        try {
            socket.send(DatagramPacket(data, data.size, addr, DISCOVERY_PORT))
        } catch (e: Exception) {
            Log.w(TAG, "Send to $addr failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Listen loop
    // ------------------------------------------------------------------

    private suspend fun listenLoop() {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            soTimeout    = 500
            bind(InetSocketAddress(DISCOVERY_PORT))   // binds to 0.0.0.0
        }
        try {
            val buf = ByteArray(BUFFER_SIZE)
            while (coroutineContext.isActive) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    parseMessage(msg, packet.address.hostAddress ?: "")
                } catch (e: SocketTimeoutException) {
                    // normal
                } catch (e: Exception) {
                    if (coroutineContext.isActive) Log.e(TAG, "Listen error", e)
                }
            }
        } finally {
            socket.close()
        }
    }

    // ------------------------------------------------------------------
    // Parse incoming broadcast
    // ------------------------------------------------------------------

    private fun parseMessage(msg: String, senderIp: String) {
        if (!msg.startsWith(MAGIC)) return
        val parts = msg.split("|")
        if (parts.size < 4) return

        val name       = parts[1]
        val advertisedIp = parts[2].takeIf { it.isNotBlank() } ?: senderIp
        val port       = parts[3].toIntOrNull() ?: callPort

        // Ignore our own broadcasts (compare by IP)
        val localIp = getLocalIpAddress()
        if (advertisedIp == localIp || senderIp == localIp) return

        val ip  = advertisedIp           // trust advertised; fall back to sender
        val now = System.currentTimeMillis()
        peerTimestamps[ip] = now

        if (!knownPeers.containsKey(ip)) {
            val peer = PeerInfo(name, ip, port)
            knownPeers[ip] = peer
            Log.d(TAG, "★ Peer found: $peer")
            onPeerFound(peer)
        } else {
            // refresh name/port
            knownPeers[ip] = PeerInfo(name, ip, port)
        }
    }

    // ------------------------------------------------------------------
    // Cleanup stale peers
    // ------------------------------------------------------------------

    private suspend fun cleanupLoop() {
        while (coroutineContext.isActive) {
            delay(PEER_TIMEOUT_MS)
            val now     = System.currentTimeMillis()
            val expired = peerTimestamps.filter { now - it.value > PEER_TIMEOUT_MS }.keys.toList()
            for (ip in expired) {
                peerTimestamps.remove(ip)
                knownPeers.remove(ip)
                Log.d(TAG, "Peer timed out: $ip")
                onPeerLost(ip)
            }
        }
    }

    // ------------------------------------------------------------------
    // Network helpers
    // ------------------------------------------------------------------

    fun getLocalIpAddress(): String? {
        // Primary: use WifiManager (most reliable on Android)
        try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wm.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ip).array()
                return InetAddress.getByAddress(bytes).hostAddress
            }
        } catch (e: Exception) { /* fall through */ }

        // Fallback: enumerate NetworkInterfaces
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { iface -> !iface.isLoopback && iface.isUp && !iface.isVirtual }
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { addr ->
                    addr is Inet4Address &&
                    !addr.isLoopbackAddress &&
                    addr.hostAddress?.startsWith("169.") == false   // exclude APIPA
                }
                ?.hostAddress
        } catch (e: Exception) { null }
    }

    /** Calculate subnet broadcast, e.g. 192.168.1.255 from IP + netmask */
    private fun getSubnetBroadcastAddress(): InetAddress? {
        try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val dhcp = wm.dhcpInfo ?: return null

            @Suppress("DEPRECATION")
            val ipInt   = dhcp.ipAddress
            @Suppress("DEPRECATION")
            val maskInt = dhcp.netmask

            if (ipInt == 0) return null

            val bcastInt = (ipInt and maskInt) or maskInt.inv()
            val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bcastInt).array()
            return InetAddress.getByAddress(bytes)
        } catch (e: Exception) { return null }
    }

    fun getKnownPeers(): List<PeerInfo> = knownPeers.values.toList()
}

data class PeerInfo(
    val name: String,
    val ip: String,
    val port: Int,
    val id: String = ip,
    val viaServer: Boolean = false
)
