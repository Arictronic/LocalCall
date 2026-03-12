package com.localcall.app.network

import android.content.Context
import android.util.Log
import com.localcall.app.audio.AudioEngine
import com.localcall.app.services.CallService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.coroutines.coroutineContext

data class RelaySessionInfo(
    val targetId: String,
    val sessionId: String,
    val relayHost: String,
    val relayPort: Int,
    val relayToken: String
)

/**
 * TCP signaling for call setup.
 *
 * Direct mode (LAN):
 *   CALL <audioPort>, ACCEPT <audioPort>, BUSY, BYE
 *
 * Relay mode (server):
 *   CALL2|<clientId>|<targetPeerId> -> SESSION|<sessionId>|<relayHost>|<relayPort>|<token>
 *   REJECT2|<clientId>|<sessionId>|<reason>
 *   BYE2|<clientId>|<sessionId>|<reason>
 */
class SignalingServer(
    private val context: Context,
    private val callService: CallService
) {
    companion object {
        const val TAG = "SignalingServer"
        const val SIGNALING_PORT = 45680
        private const val RELAY_SOCKET_TIMEOUT_MS = 5_000
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    @Volatile
    var relayServerHost: String = ""
        private set
    @Volatile
    var relayServerPort: Int = ServerPeerDiscovery.DEFAULT_SERVER_PORT
        private set
    @Volatile
    var relayClientId: String = ""
        private set

    private val relayLock = Any()
    private var pendingRelaySession: RelaySessionInfo? = null

    fun configureRelay(host: String, port: Int, clientId: String) {
        relayServerHost = host.trim()
        relayServerPort = port
        relayClientId = clientId.trim()
    }

    fun isRelayAvailable(): Boolean {
        return relayServerHost.isNotBlank() &&
            relayServerPort in 1..65535 &&
            relayClientId.isNotBlank()
    }

    fun start() {
        serverJob = scope.launch { acceptLoop() }
    }

    fun stop() {
        serverJob?.cancel()
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    fun takePendingRelaySession(targetId: String): RelaySessionInfo? {
        synchronized(relayLock) {
            val current = pendingRelaySession ?: return null
            if (current.targetId != targetId) return null
            pendingRelaySession = null
            return current
        }
    }

    private suspend fun acceptLoop() {
        try {
            serverSocket = ServerSocket(SIGNALING_PORT)
            Log.d(TAG, "Signaling server on :$SIGNALING_PORT")
            while (coroutineContext.isActive) {
                try {
                    val client = serverSocket!!.accept()
                    scope.launch { handleClient(client) }
                } catch (e: SocketException) {
                    if (coroutineContext.isActive) Log.e(TAG, "Accept: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server: ${e.message}")
        }
    }

    private suspend fun handleClient(socket: Socket) {
        val remoteIp = socket.inetAddress.hostAddress ?: ""
        try {
            socket.soTimeout = RELAY_SOCKET_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)
            val line = reader.readLine()?.trim() ?: return
            Log.d(TAG, "← $remoteIp: $line")

            when {
                line.startsWith("CALL") -> {
                    val remoteAudioPort = line.split(" ").getOrNull(1)?.toIntOrNull()
                        ?: AudioEngine.CALL_PORT
                    writer.println("ACCEPT ${AudioEngine.CALL_PORT}")
                    Log.d(TAG, "→ $remoteIp: ACCEPT ${AudioEngine.CALL_PORT}")
                    callService.notifyIncomingCall(remoteIp, remoteAudioPort)
                }
                line.startsWith("BYE") -> {
                    Log.d(TAG, "BYE from $remoteIp")
                    callService.endCall(sendSignalToRemote = false)
                }
                line.startsWith("BUSY") -> {
                    Log.d(TAG, "BUSY from $remoteIp")
                    callService.endCall(sendSignalToRemote = false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleClient $remoteIp: ${e.message}")
        } finally {
            socket.close()
        }
    }

    /** Returns remote audio port on success, null on failure / BUSY */
    suspend fun sendCallRequest(target: String, preferRelay: Boolean = false): Int? {
        if (preferRelay && isRelayAvailable()) {
            return sendRelayCallRequest(target)
        }
        return sendDirectCallRequest(target)
    }

    private suspend fun sendDirectCallRequest(remoteIp: String): Int? = withContext(Dispatchers.IO) {
        try {
            Socket(remoteIp, SIGNALING_PORT).use { socket ->
                socket.soTimeout = RELAY_SOCKET_TIMEOUT_MS
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                writer.println("CALL ${AudioEngine.CALL_PORT}")
                Log.d(TAG, "→ $remoteIp: CALL ${AudioEngine.CALL_PORT}")
                val resp = reader.readLine()?.trim()
                Log.d(TAG, "← $remoteIp: $resp")
                if (resp != null && resp.startsWith("ACCEPT")) {
                    resp.split(" ").getOrNull(1)?.toIntOrNull()
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendCallRequest to $remoteIp: ${e.message}")
            null
        }
    }

    private suspend fun sendRelayCallRequest(targetPeerId: String): Int? = withContext(Dispatchers.IO) {
        val host = relayServerHost
        val port = relayServerPort
        val clientId = relayClientId
        if (host.isBlank() || clientId.isBlank()) return@withContext null

        try {
            Socket(host, port).use { socket ->
                socket.soTimeout = RELAY_SOCKET_TIMEOUT_MS
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                writer.println("CALL2|$clientId|$targetPeerId")
                val first = reader.readLine()?.trim().orEmpty()
                Log.d(TAG, "Relay CALL2 response: $first")

                val parts = first.split('|')
                if (parts.size >= 5 && parts[0] == "SESSION") {
                    val sessionId = parts[1]
                    val relayHost = parts[2].ifBlank { host }
                    val relayPort = parts[3].toIntOrNull() ?: return@use null
                    val relayToken = parts[4]
                    synchronized(relayLock) {
                        pendingRelaySession = RelaySessionInfo(
                            targetId = targetPeerId,
                            sessionId = sessionId,
                            relayHost = relayHost,
                            relayPort = relayPort,
                            relayToken = relayToken
                        )
                    }
                    return@use relayPort
                }

                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendRelayCallRequest to $host:$port for $targetPeerId: ${e.message}")
            null
        }
    }

    suspend fun sendBye(remoteIp: String) = withContext(Dispatchers.IO) {
        try {
            Socket(remoteIp, SIGNALING_PORT).use { socket ->
                socket.soTimeout = 3_000
                PrintWriter(socket.getOutputStream(), true).println("BYE")
                Log.d(TAG, "→ $remoteIp: BYE")
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendBye to $remoteIp: ${e.message}")
        }
    }

    suspend fun sendBusy(remoteIp: String) = withContext(Dispatchers.IO) {
        try {
            Socket(remoteIp, SIGNALING_PORT).use { socket ->
                socket.soTimeout = 3_000
                PrintWriter(socket.getOutputStream(), true).println("BUSY")
            }
        } catch (_: Exception) {
            // best-effort
        }
    }

    suspend fun sendRelayReject(sessionId: String, reason: String = "REJECTED"): Boolean =
        sendRelaySessionCommand("REJECT2", sessionId, reason)

    suspend fun sendRelayBye(sessionId: String, reason: String = "ENDED"): Boolean =
        sendRelaySessionCommand("BYE2", sessionId, reason)

    private suspend fun sendRelaySessionCommand(
        command: String,
        sessionId: String,
        reason: String
    ): Boolean = withContext(Dispatchers.IO) {
        val host = relayServerHost
        val port = relayServerPort
        val clientId = relayClientId
        if (host.isBlank() || clientId.isBlank() || sessionId.isBlank()) return@withContext false

        try {
            Socket(host, port).use { socket ->
                socket.soTimeout = RELAY_SOCKET_TIMEOUT_MS
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                writer.println("$command|$clientId|$sessionId|$reason")
                val first = reader.readLine()?.trim().orEmpty()
                first == "OK"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Relay $command failed for session $sessionId: ${e.message}")
            false
        }
    }
}
