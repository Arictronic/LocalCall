package com.localcall.app.services

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.localcall.app.activities.CallActivity
import com.localcall.app.activities.IncomingCallActivity
import com.localcall.app.activities.SettingsActivity
import com.localcall.app.audio.AudioEngine
import com.localcall.app.network.NetworkDiscovery
import com.localcall.app.network.NetworkMonitor
import com.localcall.app.network.PeerDiscovery
import com.localcall.app.network.PeerInfo
import com.localcall.app.network.RelaySessionInfo
import com.localcall.app.network.ServerIncomingCallEvent
import com.localcall.app.network.ServerPeerDiscovery
import com.localcall.app.network.ServerSessionEvent
import com.localcall.app.network.SignalingServer
import com.localcall.app.network.UpnpPortMapper
import com.localcall.app.receivers.RestartServiceReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

class CallService : Service() {

    companion object {
        const val TAG = "CallService"
        const val NOTIF_ID = 101
        const val CHANNEL_ID = "call_channel"

        const val BG_NOTIF_ID = 102
        const val BG_CHANNEL_ID = "background_service_channel"

        const val AUDIO_PORT = 45679

        const val ACTION_START_CALL = "action_start_call"
        const val ACTION_STOP_CALL = "action_stop_call"
        const val ACTION_ACCEPT_CALL = "action_accept_call"
        const val ACTION_REJECT_CALL = "action_reject_call"

        const val ACTION_START_BACKGROUND_SERVICE = "action_start_background_service"
        const val ACTION_STOP_BACKGROUND_SERVICE = "action_stop_background_service"

        const val EXTRA_REMOTE_IP = "remote_ip"
        const val EXTRA_REMOTE_PORT = "remote_port"
        const val EXTRA_IS_INCOMING = "is_incoming"

        const val BROADCAST_CALL_ENDED = "com.localcall.app.CALL_ENDED"
        const val BROADCAST_INCOMING_CALL = "com.localcall.app.INCOMING_CALL"
        const val BROADCAST_CALL_STARTED = "com.localcall.app.CALL_STARTED"
        const val BROADCAST_PEERS_CHANGED = "com.localcall.app.PEERS_CHANGED"
    }

    inner class LocalBinder : Binder() {
        fun getService() = this@CallService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs by lazy {
        getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    val audioEngine by lazy { AudioEngine(applicationContext) }

    val peerDiscovery by lazy {
        PeerDiscovery(
            context = applicationContext,
            onPeerFound = { peer -> Log.d(TAG, "Peer found (legacy): ${peer.name} @ ${peer.ip}") },
            onPeerLost = { ip -> Log.d(TAG, "Peer lost (legacy): $ip") }
        )
    }

    val serverPeerDiscovery by lazy {
        ServerPeerDiscovery(
            context = applicationContext,
            onPeerFound = { peer -> Log.d(TAG, "Peer found via server: ${peer.name} @ ${peer.id}") },
            onPeerLost = { key -> Log.d(TAG, "Peer lost via server: $key") }
        ).apply {
            onIncomingCallEvent = { event ->
                handleServerIncomingCall(event)
            }
            onSessionEvent = { event ->
                handleServerSessionEvent(event)
            }
        }
    }

    val networkMonitor by lazy { NetworkMonitor(applicationContext) }
    val networkDiscovery by lazy {
        NetworkDiscovery(
            context = applicationContext,
            onDeviceFound = { notifyPeersChanged() },
            onDeviceLost = { notifyPeersChanged() }
        )
    }

    val signalingServer by lazy { SignalingServer(applicationContext, this) }
    private val upnpPortMapper by lazy { UpnpPortMapper(applicationContext) }
    private var useServerDiscovery = false
    private var localClientId = ""

    var isCallActive = false
    var isMuted = false
    var callStartTime = 0L

    var currentRemoteIp = ""
    var currentRemotePort = AudioEngine.CALL_PORT
    private var currentRemoteLabel = ""
    private var currentRelaySessionId = ""
    private var currentRelayToken = ""
    private var currentRelayHost = ""
    private var currentPeerId = ""

    var pendingRemoteIp = ""
    var pendingRemotePort = AudioEngine.CALL_PORT
    private var pendingRemoteLabel = ""
    private var pendingRelaySessionId = ""
    private var pendingRelayToken = ""
    private var pendingRelayHost = ""
    private var pendingPeerId = ""

    private var isBackgroundServiceRunning = false
    private var allowSelfRestart = true
    private val btAudioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            // no-op: adding devices must not drop active call
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            handleBluetoothDeviceStateChanged("removed")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createBackgroundNotificationChannel()
        allowSelfRestart = isBackgroundServiceEnabled()
        audioManager.registerAudioDeviceCallback(btAudioCallback, null)
        applyAudioSettingsFromPrefs()
        setupNetworkMonitoring()
        applyDiscoverySettings()
        signalingServer.start()
        applyBackgroundServiceSettings()
        Log.d(TAG, "CallService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CALL -> {
                val ip = intent.getStringExtra(EXTRA_REMOTE_IP) ?: return START_STICKY
                val port = intent.getIntExtra(EXTRA_REMOTE_PORT, AUDIO_PORT)
                beginCall(ip, port)
            }
            ACTION_STOP_CALL -> endCall()
            ACTION_ACCEPT_CALL -> acceptIncomingCall()
            ACTION_REJECT_CALL -> rejectIncomingCall()
            ACTION_START_BACKGROUND_SERVICE -> {
                allowSelfRestart = true
                startBackgroundService()
            }
            ACTION_STOP_BACKGROUND_SERVICE -> {
                allowSelfRestart = false
                stopBackgroundService()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (shouldKeepAlive()) {
            scheduleServiceRestart()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        audioManager.unregisterAudioDeviceCallback(btAudioCallback)
        upnpPortMapper.release()
        networkMonitor.stopMonitoring()
        networkDiscovery.stop()
        serverPeerDiscovery.stop()
        peerDiscovery.stop()
        signalingServer.stop()
        if (isCallActive) audioEngine.stopCall()
        if (isBackgroundServiceRunning) stopBackgroundService()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (allowSelfRestart && shouldKeepAlive()) {
            scheduleServiceRestart()
        }
        super.onDestroy()
    }

    private fun setupNetworkMonitoring() {
        networkMonitor.onWifiConnected = {
            updateLanDiscoveryState()
            refreshUpnpPortMappings()
        }
        networkMonitor.onWifiDisconnected = {
            updateLanDiscoveryState()
            upnpPortMapper.stopMappings()
        }
        networkMonitor.onIpChanged = {
            updateLanDiscoveryState()
            refreshUpnpPortMappings()
        }
        networkMonitor.startMonitoring()
    }

    private fun isAutoDiscoveryEnabled(): Boolean {
        return prefs.getBoolean(SettingsActivity.KEY_AUTO_DISCOVERY, true)
    }

    private fun isBackgroundServiceEnabled(): Boolean {
        return prefs.getBoolean(SettingsActivity.KEY_BACKGROUND_SERVICE, true)
    }

    private fun isAutoAcceptEnabled(): Boolean {
        if (!prefs.getBoolean(SettingsActivity.KEY_AUTO_ACCEPT_CALLS, false)) {
            return false
        }
        if (isBluetoothSpeakerSelected() && !hasConnectedBluetoothOutput()) {
            Log.w(TAG, "Auto-accept disabled: selected Bluetooth speaker is not connected")
            return false
        }
        return true
    }

    private fun isUpnpEnabled(): Boolean {
        return prefs.getBoolean(SettingsActivity.KEY_UPNP_ENABLED, true)
    }

    private fun isBluetoothSpeakerSelected(): Boolean {
        val output = prefs.getString(SettingsActivity.KEY_SPK_OUTPUT, "earpiece")
        return output == "bt"
    }

    private fun handleBluetoothDeviceStateChanged(reason: String) {
        if (!isCallActive) return
        if (!isBluetoothSpeakerSelected()) return
        if (!hasBluetoothConnectPermission()) return
        if (hasConnectedBluetoothOutput()) return

        Log.w(TAG, "Bluetooth speaker disconnected during call ($reason), ending call")
        endCall(sendSignalToRemote = true)
    }

    private fun hasConnectedBluetoothOutput(): Boolean {
        if (!hasBluetoothConnectPermission()) {
            return false
        }
        val selectedAddress = prefs.getString(SettingsActivity.KEY_SPK_BT_ADDR, null)
            ?.trim()
            .orEmpty()
        val btOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { isBluetoothOutputType(it.type) }

        if (btOutputs.isEmpty()) return false
        if (selectedAddress.isBlank()) return true

        val exactMatch = btOutputs.any {
            makeDeviceKey(it).equals(selectedAddress, ignoreCase = true)
        }
        if (exactMatch) return true

        // Device IDs can be unstable on older Android versions; any connected BT output is enough.
        return selectedAddress.startsWith("id:") && btOutputs.isNotEmpty()
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun isBluetoothOutputType(type: Int): Boolean {
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && type == AudioDeviceInfo.TYPE_HEARING_AID) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return type == AudioDeviceInfo.TYPE_BLE_SPEAKER || type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        return false
    }

    private fun makeDeviceKey(device: AudioDeviceInfo): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val address = device.address?.trim().orEmpty()
            if (address.isNotEmpty()) return address
        }
        return "id:${device.id}:${device.type}"
    }

    private fun shouldKeepAlive(): Boolean {
        return isBackgroundServiceEnabled()
    }

    private fun scheduleServiceRestart(delayMs: Long = 1500L) {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val restartIntent = Intent(this, RestartServiceReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            this,
            901,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
        } else {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
        }
        Log.d(TAG, "Scheduled CallService restart in ${delayMs}ms")
    }

    fun applyBackgroundServiceSettings() {
        if (isBackgroundServiceEnabled()) startBackgroundService() else stopBackgroundService()
    }

    private fun shouldRunLanDiscovery(): Boolean {
        return isAutoDiscoveryEnabled() && networkMonitor.isConnected
    }

    private fun updateLanDiscoveryState() {
        if (shouldRunLanDiscovery()) {
            startNetworkDiscovery()
            peerDiscovery.start()
        } else {
            stopNetworkDiscovery()
            peerDiscovery.stop()
        }
    }

    private fun startBackgroundService() {
        if (isBackgroundServiceRunning) return
        if (!isCallActive) {
            startForeground(BG_NOTIF_ID, buildBackgroundNotification())
        }
        isBackgroundServiceRunning = true
        updateLanDiscoveryState()
    }

    private fun stopBackgroundService() {
        if (!isBackgroundServiceRunning) return
        stopNetworkDiscovery()
        peerDiscovery.stop()
        if (!isCallActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        isBackgroundServiceRunning = false
    }

    private fun startNetworkDiscovery() {
        if (!networkMonitor.isConnected) {
            return
        }
        val deviceName = prefs.getString(SettingsActivity.KEY_DEVICE_NAME, Build.MODEL) ?: Build.MODEL
        networkDiscovery.start(deviceName, AUDIO_PORT)
        networkDiscovery.updateNetworkInfo(networkMonitor.broadcastAddress, networkMonitor.currentIp)
        networkDiscovery.triggerImmediatePing()
    }

    private fun stopNetworkDiscovery() {
        networkDiscovery.stop()
    }

    fun startManualDiscovery() {
        var triggered = false
        if (networkMonitor.isConnected) {
            startNetworkDiscovery()
            val localIp = networkMonitor.currentIp
            networkMonitor.getSubnetIps()
                .asSequence()
                .filter { it != localIp }
                .take(64)
                .forEach { ip -> networkDiscovery.sendUnicastPing(ip) }
            triggered = true
        }
        if (useServerDiscovery) {
            serverPeerDiscovery.requestRefresh()
            triggered = true
        }
        if (!triggered) {
            Log.w(TAG, "Manual discovery skipped: no network available")
        }
    }

    fun applyDiscoverySettings() {
        val configuredServerIp = prefs.getString(SettingsActivity.KEY_SERVER_IP, "")
            ?.trim()
            .orEmpty()
        val configuredServerPort = prefs.getInt(
            SettingsActivity.KEY_SERVER_PORT,
            ServerPeerDiscovery.DEFAULT_SERVER_PORT
        )
        val deviceName = prefs.getString(SettingsActivity.KEY_DEVICE_NAME, Build.MODEL) ?: Build.MODEL

        localClientId = ensureClientId()

        peerDiscovery.deviceName = deviceName
        serverPeerDiscovery.deviceName = deviceName
        serverPeerDiscovery.callPort = AudioEngine.CALL_PORT
        serverPeerDiscovery.serverIp = configuredServerIp
        serverPeerDiscovery.serverPort = configuredServerPort
        serverPeerDiscovery.clientId = localClientId
        signalingServer.configureRelay(configuredServerIp, configuredServerPort, localClientId)

        useServerDiscovery = configuredServerIp.isNotBlank() && configuredServerPort in 1..65535

        if (useServerDiscovery) {
            serverPeerDiscovery.start()
            Log.d(TAG, "Discovery mode: server $configuredServerIp:$configuredServerPort")
        } else {
            serverPeerDiscovery.stop()
            Log.d(TAG, "Discovery mode: local network only")
        }

        updateLanDiscoveryState()
        refreshUpnpPortMappings()
        notifyPeersChanged()
    }

    private fun refreshUpnpPortMappings() {
        if (!isUpnpEnabled() || !networkMonitor.isConnected) {
            upnpPortMapper.stopMappings()
            return
        }

        upnpPortMapper.enabled = true
        upnpPortMapper.descriptionPrefix = "LocalCallClient"
        upnpPortMapper.refreshMappings(
            tcpPort = SignalingServer.SIGNALING_PORT,
            udpPort = AudioEngine.CALL_PORT,
            localIpHint = networkMonitor.currentIp,
            mapTcp = true,
            mapUdp = true
        )
    }

    private fun ensureClientId(): String {
        val existing = prefs.getString(SettingsActivity.KEY_CLIENT_ID, "").orEmpty().trim()
        if (existing.isNotBlank()) return existing
        val generated = "android-${UUID.randomUUID()}"
        prefs.edit().putString(SettingsActivity.KEY_CLIENT_ID, generated).apply()
        return generated
    }

    fun getKnownPeers(): List<PeerInfo> {
        val peers = mutableMapOf<String, PeerInfo>()

        networkDiscovery.getKnownPeers().forEach { peer ->
            peers[peerKey(peer)] = peer
        }

        if (useServerDiscovery) {
            serverPeerDiscovery.getKnownPeers().forEach { peer ->
                peers[peerKey(peer)] = peer
            }
        }

        peerDiscovery.getKnownPeers().forEach { peer ->
            val key = peerKey(peer)
            if (!peers.containsKey(key)) {
                peers[key] = peer
            }
        }

        return peers.values.toList()
    }

    private fun peerKey(peer: PeerInfo): String {
        return if (peer.viaServer) "srv:${peer.id}" else "ip:${peer.ip}"
    }

    fun getLocalIpAddress(): String? {
        return networkMonitor.currentIp
            ?: if (useServerDiscovery) {
                serverPeerDiscovery.getLocalIpAddress() ?: peerDiscovery.getLocalIpAddress()
            } else {
                peerDiscovery.getLocalIpAddress()
            }
    }

    private fun notifyPeersChanged() {
        sendBroadcast(Intent(BROADCAST_PEERS_CHANGED).apply {
            `package` = packageName
        })
    }

    fun beginCall(
        remoteIp: String,
        remotePort: Int,
        remoteLabel: String = remoteIp,
        relaySessionId: String? = null,
        relayToken: String? = null,
        peerId: String? = null
    ) {
        if (isCallActive) {
            Log.w(TAG, "Already in call")
            return
        }
        applyAudioSettingsFromPrefs()

        isCallActive = true
        isMuted = false
        audioEngine.isMuted = false
        currentRemoteIp = remoteIp
        currentRemotePort = remotePort
        currentRemoteLabel = remoteLabel
        currentRelaySessionId = relaySessionId.orEmpty()
        currentRelayToken = relayToken.orEmpty()
        currentRelayHost = if (relaySessionId != null) remoteIp else ""
        currentPeerId = peerId.orEmpty()
        callStartTime = System.currentTimeMillis()

        audioEngine.startCall(
            remoteIp = remoteIp,
            remotePort = remotePort,
            localPort = AudioEngine.CALL_PORT,
            relaySessionId = relaySessionId,
            relayToken = relayToken
        )

        startForeground(NOTIF_ID, buildActiveCallNotif(remoteLabel))
        sendBroadcast(Intent(BROADCAST_CALL_STARTED).apply {
            putExtra(EXTRA_REMOTE_IP, remoteLabel)
            `package` = packageName
        })
    }

    fun notifyIncomingCall(remoteIp: String, remotePort: Int) {
        if (isCallActive) {
            scope.launch { signalingServer.sendBusy(remoteIp) }
            return
        }
        if (isAutoAcceptEnabled()) {
            beginCall(remoteIp, remotePort, remoteLabel = remoteIp)
            return
        }
        pendingRemoteIp = remoteIp
        pendingRemotePort = remotePort
        pendingRemoteLabel = remoteIp
        pendingRelaySessionId = ""
        pendingRelayToken = ""
        pendingRelayHost = ""
        pendingPeerId = ""
        showIncomingCallUi(remoteIp, remotePort)
    }

    private fun handleServerIncomingCall(event: ServerIncomingCallEvent) {
        if (isCallActive) {
            scope.launch { signalingServer.sendRelayReject(event.sessionId, "BUSY") }
            return
        }

        val label = event.callerName.ifBlank { event.callerId }
        if (isAutoAcceptEnabled()) {
            beginCall(
                remoteIp = event.relayHost,
                remotePort = event.relayPort,
                remoteLabel = label,
                relaySessionId = event.sessionId,
                relayToken = event.relayToken,
                peerId = event.callerId
            )
            return
        }

        pendingRemoteIp = event.relayHost
        pendingRemotePort = event.relayPort
        pendingRemoteLabel = label
        pendingRelaySessionId = event.sessionId
        pendingRelayToken = event.relayToken
        pendingRelayHost = event.relayHost
        pendingPeerId = event.callerId
        showIncomingCallUi(label, event.relayPort)
    }

    private fun handleServerSessionEvent(event: ServerSessionEvent) {
        if (event.sessionId == pendingRelaySessionId) {
            pendingRemoteIp = ""
            pendingRemotePort = AudioEngine.CALL_PORT
            pendingRemoteLabel = ""
            pendingRelaySessionId = ""
            pendingRelayToken = ""
            pendingRelayHost = ""
            pendingPeerId = ""

            sendBroadcast(Intent(BROADCAST_CALL_ENDED).apply { `package` = packageName })
            if (isBackgroundServiceEnabled()) {
                startForeground(BG_NOTIF_ID, buildBackgroundNotification())
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            return
        }

        if (event.sessionId == currentRelaySessionId && isCallActive) {
            endCall(sendSignalToRemote = false)
        }
    }

    private fun showIncomingCallUi(remoteLabel: String, remotePort: Int) {
        val actIntent = Intent(this, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_REMOTE_IP, remoteLabel)
        }
        startActivity(actIntent)
        startForeground(NOTIF_ID, buildIncomingCallNotif(remoteLabel))
        sendBroadcast(Intent(BROADCAST_INCOMING_CALL).apply {
            putExtra(EXTRA_REMOTE_IP, remoteLabel)
            putExtra(EXTRA_REMOTE_PORT, remotePort)
            `package` = packageName
        })
    }

    fun acceptIncomingCall() {
        if (pendingRemoteIp.isBlank()) return
        beginCall(
            remoteIp = pendingRemoteIp,
            remotePort = pendingRemotePort,
            remoteLabel = pendingRemoteLabel.ifBlank { pendingRemoteIp },
            relaySessionId = pendingRelaySessionId.ifBlank { null },
            relayToken = pendingRelayToken.ifBlank { null },
            peerId = pendingPeerId.ifBlank { null }
        )
        pendingRemoteIp = ""
        pendingRemotePort = AudioEngine.CALL_PORT
        pendingRemoteLabel = ""
        pendingRelaySessionId = ""
        pendingRelayToken = ""
        pendingRelayHost = ""
        pendingPeerId = ""
    }

    fun rejectIncomingCall() {
        val ip = pendingRemoteIp
        val relaySession = pendingRelaySessionId

        pendingRemoteIp = ""
        pendingRemotePort = AudioEngine.CALL_PORT
        pendingRemoteLabel = ""
        pendingRelaySessionId = ""
        pendingRelayToken = ""
        pendingRelayHost = ""
        pendingPeerId = ""

        if (relaySession.isNotBlank()) {
            scope.launch { signalingServer.sendRelayReject(relaySession, "REJECTED") }
        } else if (ip.isNotBlank()) {
            scope.launch { signalingServer.sendBusy(ip) }
        }

        if (isBackgroundServiceEnabled()) {
            startForeground(BG_NOTIF_ID, buildBackgroundNotification())
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    fun endCall(sendSignalToRemote: Boolean = true) {
        if (!isCallActive && pendingRemoteIp.isEmpty()) return

        val remoteIp = currentRemoteIp
        val relaySession = currentRelaySessionId

        isCallActive = false
        isMuted = false
        audioEngine.isMuted = false
        pendingRemoteIp = ""
        pendingRemotePort = AudioEngine.CALL_PORT
        pendingRemoteLabel = ""
        pendingRelaySessionId = ""
        pendingRelayToken = ""
        pendingRelayHost = ""
        pendingPeerId = ""

        currentRemoteIp = ""
        currentRemotePort = AudioEngine.CALL_PORT
        currentRemoteLabel = ""
        currentRelaySessionId = ""
        currentRelayToken = ""
        currentRelayHost = ""
        currentPeerId = ""

        if (sendSignalToRemote) {
            if (relaySession.isNotBlank()) {
                scope.launch { signalingServer.sendRelayBye(relaySession, "ENDED") }
            } else if (remoteIp.isNotBlank()) {
                scope.launch { signalingServer.sendBye(remoteIp) }
            }
        }

        audioEngine.stopCall()
        if (isBackgroundServiceEnabled()) {
            startForeground(BG_NOTIF_ID, buildBackgroundNotification())
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }

        sendBroadcast(Intent(BROADCAST_CALL_ENDED).apply { `package` = packageName })
    }

    fun toggleMute(): Boolean {
        isMuted = !isMuted
        audioEngine.isMuted = isMuted
        return isMuted
    }

    private fun applyAudioSettingsFromPrefs() {
        val micSrc = prefs.getString(SettingsActivity.KEY_MIC_SOURCE, "system")
        val spkOut = prefs.getString(SettingsActivity.KEY_SPK_OUTPUT, "earpiece")
        val micBtAddr = prefs.getString(SettingsActivity.KEY_MIC_BT_ADDR, null)
        val spkBtAddr = prefs.getString(SettingsActivity.KEY_SPK_BT_ADDR, null)

        val useMicBT = micSrc == "bt" && !micBtAddr.isNullOrBlank()
        val useSpkBT = spkOut == "bt" && !spkBtAddr.isNullOrBlank()
        val useSpeaker = spkOut == "speaker"

        audioEngine.selectedMicBluetoothAddress = micBtAddr
        audioEngine.selectedSpkBluetoothAddress = spkBtAddr
        audioEngine.updateAudioSettings(
            useMicBluetooth = useMicBT,
            useSpkBluetooth = useSpkBT,
            useSpeaker = useSpeaker,
            micGain = prefs.getInt(SettingsActivity.KEY_MIC_GAIN, 80),
            speakerGain = prefs.getInt(SettingsActivity.KEY_SPK_GAIN, 80),
            customNoiseSuppression = prefs.getBoolean(SettingsActivity.KEY_CUSTOM_NS_ENABLED, true)
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "LocalCall calls"
                setSound(null, null)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun createBackgroundNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BG_CHANNEL_ID,
                "Background",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "LocalCall background discovery"
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildBackgroundNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            10,
            Intent(this, CallService::class.java).apply {
                action = ACTION_STOP_BACKGROUND_SERVICE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, BG_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("LocalCall - Discovery")
            .setContentText("Scanning devices")
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildActiveCallNotif(remoteLabel: String): Notification {
        val openPi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, CallActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endPi = PendingIntent.getService(
            this,
            1,
            Intent(this, CallService::class.java).apply { action = ACTION_STOP_CALL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("LocalCall")
            .setContentText("In call with $remoteLabel")
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End", endPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun buildIncomingCallNotif(remoteLabel: String): Notification {
        val acceptPi = PendingIntent.getService(
            this,
            2,
            Intent(this, CallService::class.java).apply { action = ACTION_ACCEPT_CALL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rejectPi = PendingIntent.getService(
            this,
            3,
            Intent(this, CallService::class.java).apply { action = ACTION_REJECT_CALL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Incoming call")
            .setContentText("From $remoteLabel")
            .addAction(android.R.drawable.ic_menu_call, "Accept", acceptPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Reject", rejectPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(
                PendingIntent.getActivity(
                    this,
                    4,
                    Intent(this, IncomingCallActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra(EXTRA_REMOTE_IP, remoteLabel)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ),
                true
            )
            .build()
    }

    fun applyRelaySessionAndBeginCall(peer: PeerInfo, relay: RelaySessionInfo) {
        beginCall(
            remoteIp = relay.relayHost,
            remotePort = relay.relayPort,
            remoteLabel = peer.name.ifBlank { peer.ip },
            relaySessionId = relay.sessionId,
            relayToken = relay.relayToken,
            peerId = peer.id
        )
    }
}
