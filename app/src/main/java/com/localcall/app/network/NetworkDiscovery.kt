package com.localcall.app.network

import android.content.Context
import android.util.Log
import com.localcall.app.services.CallService
import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * UDP-based device discovery using Ping/Pong protocol.
 *
 * Protocol:
 * - PING <deviceName> <callPort>     - Broadcast to find devices
 * - PONG <deviceName> <callPort>     - Response to ping
 *
 * Features:
 * - Multi-threaded: separate threads for sending pings and listening for pongs
 * - Works with both broadcast (local) and unicast (server-assisted)
 * - Automatic device cache with TTL
 */
class NetworkDiscovery(
    private val context: Context,
    private val onDeviceFound: (PeerInfo) -> Unit,
    private val onDeviceLost: (String) -> Unit
) {
    companion object {
        private const val TAG = "NetworkDiscovery"
        
        const val DISCOVERY_PORT = 45677  // Separate port for discovery
        const val PING_INTERVAL_MS = 2000L  // Send ping every 2 seconds
        const val DEVICE_TIMEOUT_MS = 6000L  // Device considered lost after 6 seconds
        const val SOCKET_TIMEOUT_MS = 500  // Socket receive timeout
        
        // Ping/Pong protocol
        const val PING_PREFIX = "PING"
        const val PONG_PREFIX = "PONG"
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var pingSocket: DatagramSocket? = null
    private var pongSocket: DatagramSocket? = null
    
    private var pingJob: Job? = null
    private var listenJob: Job? = null
    
    private var deviceName = "Unknown"
    private var callPort = CallService.AUDIO_PORT
    
    // Device cache with last seen timestamp
    private val deviceCache = ConcurrentHashMap<String, Pair<PeerInfo, Long>>()
    
    // Current network info
    private var currentBroadcastAddress: String? = null
    private var currentIp: String? = null
    
    /**
     * Start discovery service
     */
    fun start(name: String, callPort: Int) {
        if (pingJob != null) {
            deviceName = name
            this.callPort = callPort
            triggerImmediatePing()
            Log.d(TAG, "Already started - updated config and triggered immediate ping")
            return
        }
        
        deviceName = name
        this.callPort = callPort
        
        Log.d(TAG, "Starting NetworkDiscovery: name=$deviceName, callPort=$callPort")
        
        try {
            // Create ping socket (for sending)
            pingSocket = DatagramSocket().apply {
                broadcast = true
                soTimeout = SOCKET_TIMEOUT_MS
            }
            
            // Create pong socket (for listening)
            pongSocket = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = SOCKET_TIMEOUT_MS
                // Bind to discovery port on all interfaces
                val addr = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)), DISCOVERY_PORT)
                bind(addr)
            }
            
            Log.d(TAG, "Sockets created: ping=${pingSocket?.localPort}, pong=${pongSocket?.localPort}")
            
            // Start listening for incoming pings/pongs
            listenJob = scope.launch { listenLoop() }
            
            // Start sending periodic pings
            pingJob = scope.launch { pingLoop() }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start: ${e.message}")
        }
    }

    fun isRunning(): Boolean {
        return pingJob?.isActive == true && listenJob?.isActive == true
    }

    fun triggerImmediatePing() {
        if (pingJob == null) return
        scope.launch {
            sendBroadcastPing()
            cleanupOldDevices()
        }
    }
    
    /**
     * Stop discovery service
     */
    fun stop() {
        Log.d(TAG, "Stopping NetworkDiscovery")
        
        pingJob?.cancel()
        listenJob?.cancel()
        
        pingSocket?.close()
        pongSocket?.close()
        
        pingSocket = null
        pongSocket = null
        pingJob = null
        listenJob = null
        
        deviceCache.clear()
    }
    
    /**
     * Update broadcast address when network changes
     */
    fun updateNetworkInfo(broadcastAddress: String?, localIp: String?) {
        currentBroadcastAddress = broadcastAddress
        currentIp = localIp
        Log.d(TAG, "Network updated: broadcast=$broadcastAddress, ip=$localIp")
    }
    
    /**
     * Send ping to broadcast address
     */
    private suspend fun sendBroadcastPing() {
        val broadcastAddr = currentBroadcastAddress
        if (broadcastAddr == null) {
            Log.w(TAG, "No broadcast address, skipping ping")
            return
        }
        
        try {
            val message = "$PING_PREFIX $deviceName $callPort"
            val packet = DatagramPacket(
                message.toByteArray(),
                message.length,
                InetAddress.getByName(broadcastAddr),
                DISCOVERY_PORT
            )
            
            pingSocket?.send(packet)
            Log.d(TAG, "Broadcast ping sent to $broadcastAddr: $message")
            
            // Also send to our own IP to discover ourselves (for testing)
            currentIp?.let { ip ->
                val selfPacket = DatagramPacket(
                    message.toByteArray(),
                    message.length,
                    InetAddress.getByName(ip),
                    DISCOVERY_PORT
                )
                pingSocket?.send(selfPacket)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send broadcast ping: ${e.message}")
        }
    }
    
    /**
     * Send ping to specific IP (unicast)
     */
    fun sendUnicastPing(targetIp: String) {
        scope.launch {
            try {
                val message = "$PING_PREFIX $deviceName $callPort"
                val packet = DatagramPacket(
                    message.toByteArray(),
                    message.length,
                    InetAddress.getByName(targetIp),
                    DISCOVERY_PORT
                )
                
                pingSocket?.send(packet)
                Log.d(TAG, "Unicast ping sent to $targetIp")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send unicast ping to $targetIp: ${e.message}")
            }
        }
    }
    
    /**
     * Send pong response
     */
    private fun sendPongResponse(targetAddr: InetAddress, targetPort: Int) {
        try {
            val message = "$PONG_PREFIX $deviceName $callPort"
            val packet = DatagramPacket(
                message.toByteArray(),
                message.length,
                targetAddr,
                targetPort
            )
            
            pingSocket?.send(packet)
            Log.d(TAG, "Pong sent to ${targetAddr.hostAddress}:$targetPort")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send pong: ${e.message}")
        }
    }
    
    /**
     * Main listening loop - receives both PING and PONG
     */
    private suspend fun listenLoop() {
        val buffer = ByteArray(1024)

        while (coroutineContext.isActive) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                pongSocket?.receive(packet)

                val message = String(packet.data, 0, packet.length).trim()
                val fromIp = packet.address.hostAddress
                val fromPort = packet.port
                val fromAddr = packet.address

                Log.d(TAG, "Received from $fromIp:$fromPort: $message")

                when {
                    message.startsWith(PING_PREFIX) -> {
                        handlePing(message, fromIp, fromPort, fromAddr)
                    }
                    message.startsWith(PONG_PREFIX) -> {
                        handlePong(message, fromIp)
                    }
                }

            } catch (e: SocketTimeoutException) {
                // Normal - continue listening
            } catch (e: SocketException) {
                if (coroutineContext.isActive) {
                    Log.e(TAG, "Socket error: ${e.message}")
                }
                break
            } catch (e: Exception) {
                if (coroutineContext.isActive) {
                    Log.e(TAG, "Listen error: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Handle incoming PING - respond with PONG and notify about device
     */
    private fun handlePing(message: String, fromIp: String, fromPort: Int, fromAddr: InetAddress) {
        val parts = message.split(" ")
        if (parts.size < 3) {
            Log.w(TAG, "Invalid PING format: $message")
            return
        }
        
        val remoteName = parts[1]
        val remoteCallPort = parts.getOrNull(2)?.toIntOrNull() ?: CallService.AUDIO_PORT
        
        // Don't respond to ourselves
        if (fromIp == currentIp) {
            Log.d(TAG, "Ignoring ping from self")
            return
        }
        
        Log.d(TAG, "PING from $remoteName @ $fromIp:$fromPort (callPort=$remoteCallPort)")
        
        // Send PONG response
        sendPongResponse(fromAddr, fromPort)
        
        // Add to cache and notify
        addDevice(remoteName, fromIp, remoteCallPort)
    }
    
    /**
     * Handle incoming PONG - add device to cache
     */
    private fun handlePong(message: String, fromIp: String) {
        val parts = message.split(" ")
        if (parts.size < 3) {
            Log.w(TAG, "Invalid PONG format: $message")
            return
        }
        
        val remoteName = parts[1]
        val remoteCallPort = parts.getOrNull(2)?.toIntOrNull() ?: CallService.AUDIO_PORT
        
        // Don't add ourselves
        if (fromIp == currentIp) {
            Log.d(TAG, "Ignoring pong from self")
            return
        }
        
        Log.d(TAG, "PONG from $remoteName @ $fromIp (callPort=$remoteCallPort)")
        
        addDevice(remoteName, fromIp, remoteCallPort)
    }
    
    private fun addDevice(name: String, ip: String, callPort: Int) {
        val peer = PeerInfo(name, ip, callPort)
        val now = System.currentTimeMillis()
        
        val isNew = !deviceCache.containsKey(ip)
        deviceCache[ip] = peer to now
        
        if (isNew) {
            Log.i(TAG, "New device found: $peer")
            onDeviceFound(peer)
        } else {
            // Update existing device timestamp
            Log.d(TAG, "Device refreshed: $ip")
        }
    }
    
    /**
     * Periodic ping loop
     */
    private suspend fun pingLoop() {
        while (coroutineContext.isActive) {
            sendBroadcastPing()
            cleanupOldDevices()
            delay(PING_INTERVAL_MS)
        }
    }
    
    /**
     * Remove devices that haven't been seen recently
     */
    private fun cleanupOldDevices() {
        val now = System.currentTimeMillis()
        val toRemove = mutableListOf<String>()
        
        deviceCache.forEach { (ip, pair) ->
            val (_, lastSeen) = pair
            if (now - lastSeen > DEVICE_TIMEOUT_MS) {
                toRemove.add(ip)
            }
        }
        
        toRemove.forEach { ip ->
            deviceCache.remove(ip)
            onDeviceLost(ip)
            Log.i(TAG, "Device lost: $ip")
        }
    }
    
    /**
     * Get all known devices
     */
    fun getKnownPeers(): List<PeerInfo> {
        cleanupOldDevices()
        return deviceCache.values.map { it.first }
    }
}
