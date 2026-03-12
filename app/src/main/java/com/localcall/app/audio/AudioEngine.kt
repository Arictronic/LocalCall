package com.localcall.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.*
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.coroutineContext

/**
 * PCM audio over UDP (24 kHz, mono, 16-bit) with enhanced audio quality.
 *
 * Improvements:
 * - Higher sample rate (24 kHz vs 16 kHz) for better voice clarity
 * - Audio effects: Acoustic Echo Cancellation (AEC), Noise Suppression (NS), Automatic Gain Control (AGC)
 * - Jitter buffer for smoother playback
 * - Larger UDP packets for better efficiency
 *
 * Routing matrix:
 * ┌────────────────┬──────────────┬─────────────────────────────────────────┐
 * │ useMicBluetooth│useSpkBluetooth│ Behaviour                               │
 * ├────────────────┼──────────────┼─────────────────────────────────────────┤
 * │ false          │ false         │ System mic + earpiece/speaker           │
 * │ false          │ true          │ System mic + BT A2DP speaker (NO SCO)   │
 * │ true           │ false         │ BT SCO mic + earpiece/speaker           │
 * │ true           │ true          │ BT SCO mic + BT SCO speaker             │
 * └────────────────┴──────────────┴─────────────────────────────────────────┘
 *
 * KEY FIX: when only speaker is BT we do NOT start SCO.
 * Instead AudioTrack uses USAGE_MEDIA so Android routes it through A2DP
 * automatically, leaving the microphone on the device.
 */
class AudioEngine(private val context: Context) {

    companion object {
        private const val TAG  = "AudioEngine"
        const val SAMPLE_RATE  = 24_000  // Increased from 16kHz to 24kHz for better quality
        const val CHANNEL_IN   = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_OUT  = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING     = AudioFormat.ENCODING_PCM_16BIT
        const val CALL_PORT    = 45679
        const val PACKET_SIZE  = 1440    // ≈ 30ms @ 24kHz (increased from 1280)
        const val JITTER_BUFFER_SIZE = 5 // Number of packets to buffer for smooth playback
        private const val RELAY_HELLO_PREFIX = "LCHELLO"
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack:  AudioTrack?  = null
    private var sendSocket:  DatagramSocket? = null
    private var recvSocket:  DatagramSocket? = null

    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null
    private var playJob:    Job? = null
    private val ioLock = Any()

    // Jitter buffer for smooth playback
    private val jitterBuffer = ConcurrentLinkedQueue<ByteArray>()

    // ── runtime state ──────────────────────────────────────────────────
    var isMuted    = false
    var remoteIp   = ""
    var remotePort = CALL_PORT
    var localPort  = CALL_PORT
    var relaySessionId: String? = null
    var relayToken: String? = null
    
    // Connection quality stats
    @Volatile var packetsSent: Long = 0
    @Volatile var packetsReceived: Long = 0
    @Volatile var packetsLost: Long = 0

    // ── settings (set before startCall) ────────────────────────────────
    /** true → use system mic, false → BT SCO mic */
    var useMicBluetooth = false
    var selectedMicBluetoothAddress: String? = null

    /** true → route audio output through BT (A2DP or SCO depending on mic) */
    var useSpkBluetooth = true
    var selectedSpkBluetoothAddress: String? = null

    /** loud speaker (ignored when useSpkBluetooth = true) */
    var useSpeaker = false
    
    /** Audio gain settings (0-100) */
    var micGain = 80
    var speakerGain = 80

    /** Custom noise suppression enabled */
    var customNoiseSuppression = true

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Audio effects
    private var noiseSuppressor: NoiseSuppressor? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var automaticGainControl: AutomaticGainControl? = null

    // ──────────────────────────────────────────────────────────────────
    // Start / Stop
    // ──────────────────────────────────────────────────────────────────

    /**
     * Update audio routing settings during a call.
     * Call this when user changes settings in SettingsActivity.
     */
    fun updateAudioSettings(
        useMicBluetooth: Boolean,
        useSpkBluetooth: Boolean,
        useSpeaker: Boolean,
        micGain: Int? = null,
        speakerGain: Int? = null,
        customNoiseSuppression: Boolean? = null
    ) {
        val routingChanged = this.useMicBluetooth != useMicBluetooth ||
                      this.useSpkBluetooth != useSpkBluetooth ||
                      this.useSpeaker != useSpeaker

        val gainChanged = (micGain != null && this.micGain != micGain) ||
                         (speakerGain != null && this.speakerGain != speakerGain)

        val nsChanged = customNoiseSuppression != null && this.customNoiseSuppression != customNoiseSuppression

        if (!routingChanged && !gainChanged && !nsChanged) {
            Log.d(TAG, "Audio settings unchanged")
            return
        }

        Log.d(TAG, "Updating audio settings: micBT=$useMicBluetooth spkBT=$useSpkBluetooth speaker=$useSpeaker micGain=$micGain spkGain=$speakerGain customNS=$customNoiseSuppression")

        this.useMicBluetooth = useMicBluetooth
        this.useSpkBluetooth = useSpkBluetooth
        this.useSpeaker = useSpeaker

        micGain?.let { this.micGain = it }
        speakerGain?.let { this.speakerGain = it }
        customNoiseSuppression?.let { this.customNoiseSuppression = it }

        // Reconfigure audio session if routing changed
        if (routingChanged) {
            reconfigureRoutingDuringCall()
        }

        // Update audio effects if gain or NS changed
        if (gainChanged || nsChanged) {
            updateAudioEffects()
        }
    }

    /**
     * Update audio effects (gain, noise suppression) during a call
     */
    private fun updateAudioEffects() {
        // Update custom noise suppression
        noiseSuppressor?.let { ns ->
            ns.enabled = customNoiseSuppression
            Log.d(TAG, "Custom noise suppression: ${customNoiseSuppression}")
        }

        // AGC is enabled/disabled based on micGain setting
        // If micGain is low, disable AGC to prevent automatic boost
        automaticGainControl?.let { agc ->
            val agcEnabled = micGain >= 50
            agc.enabled = agcEnabled
            Log.d(TAG, "AGC: enabled=$agcEnabled (micGain=$micGain)")
        }

        // Update speaker gain via AudioManager
        // A2DP output uses media stream, call path uses voice stream.
        try {
            val streamType = if (useSpkBluetooth && !useMicBluetooth) {
                AudioManager.STREAM_MUSIC
            } else {
                AudioManager.STREAM_VOICE_CALL
            }
            val maxVolume = am.getStreamMaxVolume(streamType)
            val targetVolume = (speakerGain * maxVolume / 100).coerceIn(1, maxVolume)
            am.setStreamVolume(streamType, targetVolume, 0)
            Log.d(TAG, "Speaker volume set: stream=$streamType $targetVolume/$maxVolume (speakerGain=$speakerGain)")
        } catch (e: Exception) {
            Log.w(TAG, "Speaker volume set failed: ${e.message}")
        }

        Log.d(TAG, "Audio effects updated: micGain=$micGain, spkGain=$speakerGain, customNS=$customNoiseSuppression")
    }

    fun startCall(
        remoteIp: String,
        remotePort: Int,
        localPort: Int,
        relaySessionId: String? = null,
        relayToken: String? = null
    ) {
        this.remoteIp   = remoteIp
        this.remotePort = remotePort
        this.localPort  = localPort
        this.relaySessionId = relaySessionId
        this.relayToken = relayToken

        packetsSent = 0
        packetsReceived = 0
        packetsLost = 0

        // Clear jitter buffer
        jitterBuffer.clear()

        setupAudioSession()
        initRecord()
        initTrack()

        if (isRelayMode()) {
            val sharedSocket = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 100
                receiveBufferSize = PACKET_SIZE * 8
                sendBufferSize = PACKET_SIZE * 8
                bind(java.net.InetSocketAddress(localPort))
                trafficClass = 0x18
            }
            sendSocket = sharedSocket
            recvSocket = sharedSocket
            sendRelayHelloPackets()
        } else {
            sendSocket = DatagramSocket().apply {
                sendBufferSize = PACKET_SIZE * 4
                receiveBufferSize = PACKET_SIZE * 4
                trafficClass = 0x18  // DSCP AF21 for better QoS
            }

            recvSocket = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout    = 100  // Shorter timeout for smoother playback
                receiveBufferSize = PACKET_SIZE * 8
                sendBufferSize = PACKET_SIZE * 8
                bind(java.net.InetSocketAddress(localPort))
            }
        }

        captureJob = scope.launch { captureAndSend() }
        playJob    = scope.launch { receiveAndPlay() }
        Log.d(
            TAG,
            "Call started -> $remoteIp:$remotePort relay=${isRelayMode()} session=${relaySessionId ?: "-"}"
        )
    }
    fun stopCall() {
        captureJob?.cancel(); playJob?.cancel()

        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
        audioTrack?.stop();  audioTrack?.release();  audioTrack  = null
        sendSocket?.close(); sendSocket = null
        recvSocket?.close(); recvSocket = null
        jitterBuffer.clear()
        relaySessionId = null
        relayToken = null

        releaseAudioEffects()

        restoreSession()
        Log.d(TAG, "Call stopped")
    }

    // ──────────────────────────────────────────────────────────────────
    // Audio session routing
    // ──────────────────────────────────────────────────────────────────

    private fun setupAudioSession() {
        when {
            // ① BT mic → MUST use SCO (also routes speaker through BT SCO)
            useMicBluetooth -> {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                Log.d(TAG, "Session: BT SCO mic (spk follows SCO)")
                am.startBluetoothSco()
                am.isBluetoothScoOn  = true
                am.isSpeakerphoneOn  = false
                selectCommunicationDeviceForCall()
            }

            // ② BT speaker only, system mic → use A2DP, never SCO
            // Do NOT call startBluetoothSco() here.
            // AudioTrack with USAGE_MEDIA will be routed to A2DP automatically.
            // AudioRecord keeps the device mic because SCO is off.
            useSpkBluetooth -> {
                am.mode = AudioManager.MODE_NORMAL
                Log.d(TAG, "Session: A2DP speaker + system mic (no SCO)")
                if (am.isBluetoothScoOn) {
                    am.stopBluetoothSco()
                }
                am.isBluetoothScoOn  = false
                am.isSpeakerphoneOn  = false   // A2DP handles output
                clearCommunicationDeviceForCall()
            }

            // ③ All system
            else -> {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                Log.d(TAG, "Session: system mic + ${if (useSpeaker) "speaker" else "earpiece"}")
                if (am.isBluetoothScoOn) {
                    am.stopBluetoothSco()
                }
                am.isBluetoothScoOn  = false
                am.isSpeakerphoneOn  = useSpeaker
                clearCommunicationDeviceForCall()
            }
        }
        
        Log.d(TAG, "AudioManager state: mode=${am.mode} scoOn=${am.isBluetoothScoOn} speakerOn=${am.isSpeakerphoneOn}")
    }

    private fun restoreSession() {
        if (am.isBluetoothScoOn) {
            am.stopBluetoothSco()
            am.isBluetoothScoOn = false
        }
        clearCommunicationDeviceForCall()
        am.isSpeakerphoneOn = false
        am.mode             = AudioManager.MODE_NORMAL
    }

    // ──────────────────────────────────────────────────────────────────
    // AudioRecord
    // ──────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun initRecord() {
        // When BT mic is selected SCO is active, VOICE_COMMUNICATION picks it up.
        // When system mic is selected we use MIC to avoid SCO even if it happens
        // to be started for some other reason.
        val source = if (useMicBluetooth)
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else
            MediaRecorder.AudioSource.MIC

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        
        // Create AudioRecord with effects enabled
        val audioRecordBuilder = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_IN)
                .setEncoding(ENCODING)
                .build())
            .setBufferSizeInBytes(maxOf(minBuf, PACKET_SIZE * 4))
        
        audioRecord = audioRecordBuilder.build()
        applyPreferredInputDevice()
        
        // Enable audio effects if available
        val sessionId = audioRecord?.audioSessionId ?: 0
        if (sessionId != 0) {
            // Acoustic Echo Cancellation (AEC) - removes echo from speaker output
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    acousticEchoCanceler = AcousticEchoCanceler.create(sessionId).apply {
                        enabled = true
                        Log.d(TAG, "AEC enabled")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "AEC create failed: ${e.message}")
                }
            }

            // Noise Suppressor (NS) - reduces background noise
            if (NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(sessionId).apply {
                        enabled = customNoiseSuppression
                        Log.d(TAG, "NoiseSuppressor enabled, customNS=$customNoiseSuppression")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "NoiseSuppressor create failed: ${e.message}")
                }
            }

            // Automatic Gain Control (AGC) - normalizes volume levels
            if (AutomaticGainControl.isAvailable()) {
                try {
                    automaticGainControl = AutomaticGainControl.create(sessionId).apply {
                        enabled = true
                        Log.d(TAG, "AGC enabled")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "AGC create failed: ${e.message}")
                }
            }
        }

        audioRecord?.startRecording()
        val state = audioRecord?.recordingState ?: -1
        Log.d(TAG, "AudioRecord: source=$source state=${audioRecord?.state} recordingState=$state useMicBluetooth=$useMicBluetooth useSpkBluetooth=$useSpkBluetooth")
    }

    // ──────────────────────────────────────────────────────────────────
    // AudioTrack
    // ──────────────────────────────────────────────────────────────────

    private fun initTrack() {
        // USAGE_VOICE_COMMUNICATION → earpiece / SCO (telephony stack)
        // USAGE_MEDIA               → A2DP BT speaker / loudspeaker (media stack)
        //
        // When user wants BT speaker but system mic we MUST use USAGE_MEDIA
        // so Android routes the playback through A2DP without SCO.
        val usage = if (useSpkBluetooth && !useMicBluetooth)
            AudioAttributes.USAGE_MEDIA
        else
            AudioAttributes.USAGE_VOICE_COMMUNICATION

        val contentType = if (useSpkBluetooth && !useMicBluetooth)
            AudioAttributes.CONTENT_TYPE_MUSIC   // A2DP path
        else
            AudioAttributes.CONTENT_TYPE_SPEECH  // SCO / earpiece path

        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(contentType)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_OUT)
                .setEncoding(ENCODING)
                .build())
            .setBufferSizeInBytes(maxOf(minBuf, PACKET_SIZE * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        applyPreferredOutputDevice()

        audioTrack?.play()
        updateAudioEffects()
        val state = audioTrack?.playState ?: -1
        Log.d(TAG, "AudioTrack: usage=$usage contentType=$contentType playState=$state useMicBluetooth=$useMicBluetooth useSpkBluetooth=$useSpkBluetooth")
    }

    // ──────────────────────────────────────────────────────────────────
    // Send / Receive loops
    // ──────────────────────────────────────────────────────────────────

    private suspend fun captureAndSend() {
        val buf  = ByteArray(PACKET_SIZE)
        val addr = InetAddress.getByName(remoteIp)
        var packetsFailed = 0
        
        while (coroutineContext.isActive) {
            val n = audioRecord?.read(buf, 0, buf.size) ?: break
            
            if (n > 0 && !isMuted) {
                try {
                    sendSocket?.send(DatagramPacket(buf, n, addr, remotePort))
                    packetsSent++
                } catch (e: Exception) {
                    packetsFailed++
                    packetsLost++
                    Log.e(TAG, "Send failed: ${e.message}")
                }
            } else if (n <= 0) {
                // AudioRecord not ready, small delay
                delay(5)
            }
        }
        
        Log.d(TAG, "Capture ended: sent=$packetsSent failed=$packetsFailed")
    }

    private suspend fun receiveAndPlay() {
        val buf = ByteArray(PACKET_SIZE * 2)
        var packetsDropped = 0
        var timeoutStreak = 0
        
        while (coroutineContext.isActive) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                recvSocket?.receive(pkt)
                
                if (pkt.length > 0) {
                    timeoutStreak = 0
                    // Add to jitter buffer
                    val packetData = pkt.data.copyOf(pkt.length)
                    jitterBuffer.add(packetData)
                    packetsReceived++
                    
                    // Maintain jitter buffer size - drop oldest if too full
                    while (jitterBuffer.size > JITTER_BUFFER_SIZE) {
                        jitterBuffer.poll()
                        packetsDropped++
                    }
                    
                    // Play from buffer if we have enough packets
                    if (jitterBuffer.size >= 2) {
                        val playData = jitterBuffer.poll()
                        if (playData != null) {
                            audioTrack?.write(playData, 0, playData.size)
                        }
                    }
                }
            } catch (e: SocketTimeoutException) {
                // Expected on quiet links; avoid inflating packet loss statistics.
                timeoutStreak++
                if (timeoutStreak >= 4) {
                    packetsLost++
                    timeoutStreak = 0
                }
            } catch (e: Exception) {
                if (coroutineContext.isActive) {
                    Log.e(TAG, "Recv error: ${e.message}")
                    packetsLost++
                }
            }
        }
        
        Log.d(TAG, "Receive ended: received=$packetsReceived dropped=$packetsDropped lost=$packetsLost")
    }
    private fun isRelayMode(): Boolean {
        return !relaySessionId.isNullOrBlank() && !relayToken.isNullOrBlank()
    }

    private fun sendRelayHelloPackets() {
        val sessionId = relaySessionId ?: return
        val token = relayToken ?: return
        val payload = "$RELAY_HELLO_PREFIX|$sessionId|$token".toByteArray(Charsets.UTF_8)
        val addr = try {
            InetAddress.getByName(remoteIp)
        } catch (e: Exception) {
            Log.w(TAG, "Relay hello resolve failed: ${e.message}")
            return
        }

        repeat(3) {
            try {
                sendSocket?.send(DatagramPacket(payload, payload.size, addr, remotePort))
            } catch (e: Exception) {
                Log.w(TAG, "Relay hello send failed: ${e.message}")
            }
        }
    }
    /**
     * Get connection quality based on packet loss ratio.
     * @return 0 = poor, 1 = fair, 2 = good, 3 = excellent
     */
    fun getConnectionQuality(): Int {
        val total = packetsReceived + packetsLost
        if (total == 0L) return 3  // No data yet, assume excellent
        
        val lossRatio = packetsLost.toFloat() / total.toFloat()
        return when {
            lossRatio < 0.02f -> 3  // < 2% loss = excellent
            lossRatio < 0.05f -> 2  // < 5% loss = good
            lossRatio < 0.15f -> 1  // < 15% loss = fair
            else -> 0               // >= 15% loss = poor
        }
    }
    
    fun getConnectionStats(): String {
        val total = packetsReceived + packetsLost
        val lossRatio = if (total > 0L) (packetsLost * 100 / total) else 0L
        return "Sent: $packetsSent | Recv: $packetsReceived | Lost: $packetsLost ($lossRatio%)"
    }

    private fun reconfigureRoutingDuringCall() {
        synchronized(ioLock) {
            val callRunning = captureJob?.isActive == true || playJob?.isActive == true
            if (!callRunning) {
                setupAudioSession()
                return
            }

            try {
                captureJob?.cancel()
                playJob?.cancel()

                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null

                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null

                setupAudioSession()
                initRecord()
                initTrack()

                captureJob = scope.launch { captureAndSend() }
                playJob = scope.launch { receiveAndPlay() }
                Log.d(TAG, "Audio routing reapplied during active call")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reconfigure routing: ${e.message}")
            }
        }
    }

    private fun releaseAudioEffects() {
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        try { acousticEchoCanceler?.release() } catch (_: Exception) {}
        try { automaticGainControl?.release() } catch (_: Exception) {}
        noiseSuppressor = null
        acousticEchoCanceler = null
        automaticGainControl = null
    }

    @SuppressLint("NewApi")
    private fun selectCommunicationDeviceForCall() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val preferredAddress = selectedMicBluetoothAddress ?: selectedSpkBluetoothAddress
        val device = am.availableCommunicationDevices.firstOrNull { info ->
            isBluetoothCommunicationDevice(info) &&
                (preferredAddress.isNullOrBlank() ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                        info.address.equals(preferredAddress, ignoreCase = true)))
        }

        if (device != null) {
            val applied = am.setCommunicationDevice(device)
            Log.d(TAG, "setCommunicationDevice(${device.type}) = $applied")
        } else {
            Log.d(TAG, "No matching communication device for SCO route")
        }
    }

    @SuppressLint("NewApi")
    private fun clearCommunicationDeviceForCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.clearCommunicationDevice()
        }
    }

    private fun isBluetoothCommunicationDevice(info: AudioDeviceInfo): Boolean {
        return when (info.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
            AudioDeviceInfo.TYPE_BLE_HEADSET -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            else -> false
        }
    }

    private fun applyPreferredInputDevice() {
        if (!useMicBluetooth) return
        val preferredAddress = selectedMicBluetoothAddress
        val target = findBluetoothAudioDevice(
            address = preferredAddress,
            forInput = true
        )
        if (target != null) {
            val applied = audioRecord?.setPreferredDevice(target)
            Log.d(TAG, "Preferred input device set: ${target.type} applied=$applied")
        }
    }

    private fun applyPreferredOutputDevice() {
        if (!useSpkBluetooth) return
        val preferredAddress = selectedSpkBluetoothAddress
        val target = findBluetoothAudioDevice(
            address = preferredAddress,
            forInput = false
        )
        if (target != null) {
            val applied = audioTrack?.setPreferredDevice(target)
            Log.d(TAG, "Preferred output device set: ${target.type} applied=$applied")
        }
    }

    private fun findBluetoothAudioDevice(address: String?, forInput: Boolean): AudioDeviceInfo? {
        val flag = if (forInput) AudioManager.GET_DEVICES_INPUTS else AudioManager.GET_DEVICES_OUTPUTS
        val devices = am.getDevices(flag).filter { device ->
            if (forInput) {
                when (device.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
                    AudioDeviceInfo.TYPE_BLE_HEADSET -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    else -> false
                }
            } else {
                // For output without BT mic selected prefer media profiles and avoid SCO/hands-free.
                if (!useMicBluetooth) {
                    when (device.type) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> true
                        AudioDeviceInfo.TYPE_HEARING_AID -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        AudioDeviceInfo.TYPE_BLE_SPEAKER -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        else -> false
                    }
                } else {
                    when (device.type) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> true
                        AudioDeviceInfo.TYPE_HEARING_AID -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_BLE_SPEAKER -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        else -> false
                    }
                }
            }
        }

        if (devices.isEmpty()) return null
        if (address.isNullOrBlank()) return devices.firstOrNull()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return devices.firstOrNull()

        return devices.firstOrNull { it.address.equals(address, ignoreCase = true) }
            ?: devices.firstOrNull()
    }
}
