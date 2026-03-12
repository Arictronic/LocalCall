package com.localcall.app.activities

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Patterns
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.localcall.app.databinding.ActivitySettingsBinding
import com.localcall.app.network.ServerPeerDiscovery
import com.localcall.app.services.CallService
import java.net.URI

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var callService: CallService? = null
    private var serviceBound = false

    private data class ConnectedBtAudioDevice(
        val name: String,
        val address: String
    )

    private data class ServerEndpoint(
        val host: String,
        val port: Int?
    )

    private val micBtDevices = mutableListOf<ConnectedBtAudioDevice>()
    private val spkBtDevices = mutableListOf<ConnectedBtAudioDevice>()

    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    companion object {
        const val PREFS_NAME = "localcall_prefs"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_MIC_SOURCE = "mic_source"   // "system" | "bt"
        const val KEY_MIC_BT_ADDR = "mic_bt_addr"
        const val KEY_SPK_OUTPUT = "spk_output"   // "earpiece" | "speaker" | "bt"
        const val KEY_SPK_BT_ADDR = "spk_bt_addr"
        const val KEY_SERVER_IP = "server_ip"
        const val KEY_SERVER_PORT = "server_port"
        const val KEY_CLIENT_ID = "client_id"
        
        // Audio quality settings
        const val KEY_AUDIO_QUALITY = "audio_quality"  // "standard" | "high"
        const val KEY_AEC_ENABLED = "aec_enabled"
        const val KEY_NS_ENABLED = "ns_enabled"
        const val KEY_AGC_ENABLED = "agc_enabled"
        
        // Audio gain settings
        const val KEY_MIC_GAIN = "mic_gain"          // 0-100 (percentage)
        const val KEY_SPK_GAIN = "spk_gain"          // 0-100 (percentage)
        const val KEY_CUSTOM_NS_ENABLED = "custom_ns_enabled"  // Custom noise suppression
        
        // Network discovery settings
        const val KEY_AUTO_DISCOVERY = "auto_discovery"  // true | false
        
        // Background service settings
        const val KEY_BACKGROUND_SERVICE = "background_service"  // true | false
        const val KEY_AUTO_ACCEPT_CALLS = "auto_accept_calls"  // true | false
        const val KEY_UPNP_ENABLED = "upnp_enabled"  // true | false
    }

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val btAudioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshBtSpinners()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshBtSpinners()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, s: IBinder?) {
            callService = (s as CallService.LocalBinder).getService()
            serviceBound = true
            applyToEngine()
        }

        override fun onServiceDisconnected(n: ComponentName?) {
            callService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.apply {
            title = "Настройки"
            setDisplayHomeAsUpEnabled(true)
        }

        buildMicSpinners()
        buildSpkSpinners()
        refreshBtSpinners()
        loadPrefs()
        setupSliderListeners()
        audioManager.registerAudioDeviceCallback(btAudioCallback, null)

        binding.btnSave.setOnClickListener { savePrefs() }
        bindService(Intent(this, CallService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        audioManager.unregisterAudioDeviceCallback(btAudioCallback)
        if (serviceBound) unbindService(serviceConnection)
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refreshBtSpinners() {
        val selectedMic = micBtDevices.getOrNull(binding.spinnerMicBt.selectedItemPosition)?.address
            ?: prefs.getString(KEY_MIC_BT_ADDR, null)
        val selectedSpk = spkBtDevices.getOrNull(binding.spinnerSpkBt.selectedItemPosition)?.address
            ?: prefs.getString(KEY_SPK_BT_ADDR, null)

        loadConnectedSpeakerDevices()
        if (binding.spinnerMicType.selectedItemPosition == 1) {
            loadConnectedMicDevices()
        } else {
            micBtDevices.clear()
        }
        buildBtSpinner(binding.spinnerMicBt, micBtDevices, "Нет активных Bluetooth-микрофонов")
        buildBtSpinner(binding.spinnerSpkBt, spkBtDevices, "Нет активных Bluetooth-устройств вывода")
        selectBtAddress(binding.spinnerMicBt, micBtDevices, selectedMic)
        selectBtAddress(binding.spinnerSpkBt, spkBtDevices, selectedSpk)
    }

    private fun loadConnectedSpeakerDevices() {
        spkBtDevices.clear()
        if (!hasBluetoothConnectPermission()) return

        val unique = linkedMapOf<String, ConnectedBtAudioDevice>()
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { isBluetoothOutputType(it.type) }
            .forEach { device ->
                val key = makeDeviceKey(device)
                unique[key] = ConnectedBtAudioDevice(
                    name = device.productName?.toString()?.trim().orEmpty().ifBlank { "Bluetooth audio" },
                    address = key
                )
            }
        spkBtDevices += unique.values
    }

    private fun loadConnectedMicDevices() {
        micBtDevices.clear()
        if (!hasBluetoothConnectPermission()) return

        val unique = linkedMapOf<String, ConnectedBtAudioDevice>()
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { isBluetoothMicType(it.type) }
            .forEach { device ->
                val key = makeDeviceKey(device)
                unique[key] = ConnectedBtAudioDevice(
                    name = device.productName?.toString()?.trim().orEmpty().ifBlank { "Bluetooth mic" },
                    address = key
                )
            }
        micBtDevices += unique.values
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

    private fun isBluetoothMicType(type: Int): Boolean {
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return type == AudioDeviceInfo.TYPE_BLE_HEADSET
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

    private fun buildMicSpinners() {
        val micTypes = arrayOf("Системный микрофон", "Bluetooth-гарнитура (микрофон)")
        binding.spinnerMicType.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, micTypes)

        binding.spinnerMicType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                val useBtMic = pos == 1
                binding.rowMicBt.visibility = if (useBtMic) View.VISIBLE else View.GONE
                if (useBtMic) {
                    refreshBtSpinners()
                }
            }

            override fun onNothingSelected(p: AdapterView<*>) = Unit
        }

        buildBtSpinner(binding.spinnerMicBt, micBtDevices, "Нет активных Bluetooth-микрофонов")
    }

    private fun buildSpkSpinners() {
        val spkTypes = arrayOf(
            "Наушник (у уха)",
            "Динамик (громкая связь)",
            "Bluetooth-гарнитура (динамик)"
        )
        binding.spinnerSpkType.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, spkTypes)

        binding.spinnerSpkType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                binding.rowSpkBt.visibility = if (pos == 2) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(p: AdapterView<*>) = Unit
        }

        buildBtSpinner(binding.spinnerSpkBt, spkBtDevices, "Нет активных Bluetooth-устройств вывода")
    }

    private fun buildBtSpinner(
        spinner: Spinner,
        devices: List<ConnectedBtAudioDevice>,
        emptyText: String
    ) {
        if (devices.isEmpty()) {
            spinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf(emptyText)
            )
            spinner.isEnabled = false
        } else {
            val names = devices.map { it.name }
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            spinner.isEnabled = true
        }
    }

    private fun selectBtAddress(
        spinner: Spinner,
        devices: List<ConnectedBtAudioDevice>,
        address: String?
    ) {
        if (address.isNullOrBlank()) return
        val idx = devices.indexOfFirst { it.address == address }
        if (idx >= 0) {
            spinner.setSelection(idx)
        }
    }

    private fun loadPrefs() {
        binding.etDeviceName.setText(prefs.getString(KEY_DEVICE_NAME, Build.MODEL))
        binding.etServerIp.setText(prefs.getString(KEY_SERVER_IP, ""))
        val savedServerPort = prefs.getInt(KEY_SERVER_PORT, ServerPeerDiscovery.DEFAULT_SERVER_PORT)
        binding.etServerPort.setText(savedServerPort.toString())

        // Load auto discovery setting
        val autoDiscovery = prefs.getBoolean(KEY_AUTO_DISCOVERY, true)
        binding.switchAutoDiscovery.isChecked = autoDiscovery
        
        // Load background service setting
        val backgroundService = prefs.getBoolean(KEY_BACKGROUND_SERVICE, true)
        binding.switchBackgroundService.isChecked = backgroundService
        binding.switchAutoAcceptCalls.isChecked = prefs.getBoolean(KEY_AUTO_ACCEPT_CALLS, false)

        // Load audio gain settings
        val micGain = prefs.getInt(KEY_MIC_GAIN, 80)
        binding.sliderMicGain.value = micGain.toFloat()
        binding.tvMicGainValue.text = "$micGain%"
        
        val spkGain = prefs.getInt(KEY_SPK_GAIN, 80)
        binding.sliderSpkGain.value = spkGain.toFloat()
        binding.tvSpkGainValue.text = "$spkGain%"
        
        // Load custom noise suppression
        val customNS = prefs.getBoolean(KEY_CUSTOM_NS_ENABLED, true)
        binding.switchCustomNS.isChecked = customNS

        val micSrc = prefs.getString(KEY_MIC_SOURCE, "system")
        binding.spinnerMicType.setSelection(if (micSrc == "bt") 1 else 0)
        binding.rowMicBt.visibility = if (micSrc == "bt") View.VISIBLE else View.GONE
        prefs.getString(KEY_MIC_BT_ADDR, null)?.let { addr ->
            val idx = micBtDevices.indexOfFirst { it.address == addr }
            if (idx >= 0) binding.spinnerMicBt.setSelection(idx)
        }

        val spkOut = prefs.getString(KEY_SPK_OUTPUT, "earpiece")
        binding.spinnerSpkType.setSelection(
            when (spkOut) {
                "speaker" -> 1
                "bt" -> 2
                else -> 0
            }
        )
        binding.rowSpkBt.visibility = if (spkOut == "bt") View.VISIBLE else View.GONE
        prefs.getString(KEY_SPK_BT_ADDR, null)?.let { addr ->
            val idx = spkBtDevices.indexOfFirst { it.address == addr }
            if (idx >= 0) binding.spinnerSpkBt.setSelection(idx)
        }
    }
    
    private fun setupSliderListeners() {
        binding.sliderMicGain.addOnChangeListener { slider, value, fromUser ->
            binding.tvMicGainValue.text = "${value.toInt()}%"
        }
        
        binding.sliderSpkGain.addOnChangeListener { slider, value, fromUser ->
            binding.tvSpkGainValue.text = "${value.toInt()}%"
        }
    }

    private fun savePrefs() {
        val name = binding.etDeviceName.text.toString().trim().ifEmpty { Build.MODEL }
        val micSrc = if (binding.spinnerMicType.selectedItemPosition == 1) "bt" else "system"
        val spkOut = when (binding.spinnerSpkType.selectedItemPosition) {
            1 -> "speaker"
            2 -> "bt"
            else -> "earpiece"
        }

        val endpoint = parseServerEndpoint(binding.etServerIp.text.toString())
        val serverHost = endpoint.host
        val rawServerPort = binding.etServerPort.text.toString().trim()
        val serverPort = if (rawServerPort.isEmpty()) {
            endpoint.port ?: ServerPeerDiscovery.DEFAULT_SERVER_PORT
        } else {
            rawServerPort.toIntOrNull()
        }

        if (!isValidServerHost(serverHost)) {
            Toast.makeText(
                this,
                "Укажите корректный IP или домен сервера",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (serverPort == null || serverPort !in 1..65535) {
            Toast.makeText(this, "Укажите корректный порт (1..65535)", Toast.LENGTH_SHORT).show()
            return
        }

        if (micSrc == "bt" && micBtDevices.isEmpty()) {
            Toast.makeText(this, "Нет активного Bluetooth-микрофона", Toast.LENGTH_SHORT).show()
            return
        }
        if (spkOut == "bt" && spkBtDevices.isEmpty()) {
            Toast.makeText(this, "Нет активного Bluetooth-устройства вывода", Toast.LENGTH_SHORT)
                .show()
            return
        }

        // Get auto discovery setting
        val autoDiscovery = binding.switchAutoDiscovery.isChecked
        
        // Get background service setting
        val backgroundService = binding.switchBackgroundService.isChecked
        val autoAcceptCalls = binding.switchAutoAcceptCalls.isChecked

        // Get audio gain settings
        val micGain = binding.sliderMicGain.value.toInt()
        val spkGain = binding.sliderSpkGain.value.toInt()
        val customNS = binding.switchCustomNS.isChecked

        val micBtAddr = if (micSrc == "bt") {
            micBtDevices.getOrNull(binding.spinnerMicBt.selectedItemPosition)?.address
        } else {
            null
        }
        val spkBtAddr = if (spkOut == "bt") {
            spkBtDevices.getOrNull(binding.spinnerSpkBt.selectedItemPosition)?.address
        } else {
            null
        }

        if (micSrc == "bt" && micBtAddr == null) {
            Toast.makeText(this, "Выберите Bluetooth-микрофон", Toast.LENGTH_SHORT).show()
            return
        }
        if (spkOut == "bt" && spkBtAddr == null) {
            Toast.makeText(this, "Выберите Bluetooth-устройство вывода", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.edit().apply {
            putString(KEY_DEVICE_NAME, name)
            putString(KEY_MIC_SOURCE, micSrc)
            putString(KEY_MIC_BT_ADDR, micBtAddr)
            putString(KEY_SPK_OUTPUT, spkOut)
            putString(KEY_SPK_BT_ADDR, spkBtAddr)
            putString(KEY_SERVER_IP, serverHost)
            putInt(KEY_SERVER_PORT, serverPort)
            putBoolean(KEY_AUTO_DISCOVERY, autoDiscovery)
            putBoolean(KEY_BACKGROUND_SERVICE, backgroundService)
            putBoolean(KEY_AUTO_ACCEPT_CALLS, autoAcceptCalls)
            putInt(KEY_MIC_GAIN, micGain)
            putInt(KEY_SPK_GAIN, spkGain)
            putBoolean(KEY_CUSTOM_NS_ENABLED, customNS)
            apply()
        }

        binding.etServerIp.setText(serverHost)
        if (rawServerPort.isEmpty() && endpoint.port != null) {
            binding.etServerPort.setText(endpoint.port.toString())
        }

        applyToEngine()
        Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
        
        // If we're in a call, the service will apply the new settings
        // If not, they'll be applied on next call
    }

    private fun applyToEngine() {
        val service = callService ?: return
        val engine = service.audioEngine

        val micSrc = prefs.getString(KEY_MIC_SOURCE, "system")
        val spkOut = prefs.getString(KEY_SPK_OUTPUT, "earpiece")
        val micBtAddr = prefs.getString(KEY_MIC_BT_ADDR, null)
        val spkBtAddr = prefs.getString(KEY_SPK_BT_ADDR, null)

        val useMicBT = (micSrc == "bt" && micBtAddr != null)
        val useSpkBT = (spkOut == "bt" && spkBtAddr != null)
        val useSpeaker = (spkOut == "speaker")

        // Get audio gain and noise suppression settings
        val micGain = prefs.getInt(KEY_MIC_GAIN, 80)
        val spkGain = prefs.getInt(KEY_SPK_GAIN, 80)
        val customNS = prefs.getBoolean(KEY_CUSTOM_NS_ENABLED, true)

        engine.selectedMicBluetoothAddress = micBtAddr
        engine.selectedSpkBluetoothAddress = spkBtAddr

        // Apply settings to engine (works during call too)
        engine.updateAudioSettings(
            useMicBluetooth = useMicBT,
            useSpkBluetooth = useSpkBT,
            useSpeaker = useSpeaker,
            micGain = micGain,
            speakerGain = spkGain,
            customNoiseSuppression = customNS
        )

        service.applyDiscoverySettings()
        service.applyBackgroundServiceSettings()
    }

    private fun parseServerEndpoint(rawInput: String): ServerEndpoint {
        val raw = rawInput.trim()
        if (raw.isEmpty()) return ServerEndpoint("", null)

        val normalized = if (raw.contains("://")) raw else "tcp://$raw"
        return try {
            val uri = URI(normalized)
            val host = uri.host?.trim().orEmpty().removePrefix("[").removeSuffix("]")
            val port = if (uri.port in 1..65535) uri.port else null
            ServerEndpoint(host = host, port = port)
        } catch (_: Exception) {
            val fallback = raw.substringBefore('/').trim()
            if (fallback.isEmpty()) return ServerEndpoint("", null)
            val colonCount = fallback.count { it == ':' }
            return if (colonCount == 1 && fallback.substringAfter(':').all { it.isDigit() }) {
                ServerEndpoint(
                    host = fallback.substringBefore(':').trim(),
                    port = fallback.substringAfter(':').toIntOrNull()
                )
            } else {
                ServerEndpoint(
                    host = fallback.removePrefix("[").removeSuffix("]"),
                    port = null
                )
            }
        }
    }

    private fun isValidServerHost(host: String): Boolean {
        if (host.isBlank()) return true
        if (Patterns.IP_ADDRESS.matcher(host).matches()) return true
        if (host.equals("localhost", ignoreCase = true)) return true
        val domainRegex =
            Regex("^(?=.{1,253}$)(?!-)[A-Za-z0-9-]{1,63}(?<!-)(\\.(?!-)[A-Za-z0-9-]{1,63}(?<!-))*$")
        return domainRegex.matches(host)
    }
}
