package com.localcall.app.activities

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Patterns
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.localcall.app.R
import com.localcall.app.adapters.PeerAdapter
import com.localcall.app.databinding.ActivityMainBinding
import com.localcall.app.network.PeerInfo
import com.localcall.app.services.CallService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URI

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var callService: CallService? = null
    private var serviceBound = false
    private var selectedPeer: PeerInfo? = null

    private val peers = mutableListOf<PeerInfo>()
    private lateinit var peerAdapter: PeerAdapter

    private val requiredPerms = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.MODIFY_AUDIO_SETTINGS)
        add(Manifest.permission.CHANGE_WIFI_MULTICAST_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
    }.toTypedArray()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, s: IBinder?) {
            callService = (s as CallService.LocalBinder).getService()
            serviceBound = true
            startPeerPolling()
        }

        override fun onServiceDisconnected(n: ComponentName?) {
            callService = null
            serviceBound = false
        }
    }

    private val callReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                CallService.BROADCAST_CALL_STARTED -> {
                    val ip = intent.getStringExtra(CallService.EXTRA_REMOTE_IP) ?: ""
                    openCallScreen(ip)
                }

                CallService.BROADCAST_CALL_ENDED -> {
                    binding.btnConnect.isEnabled = (selectedPeer != null)
                    binding.btnConnect.text = "Подключиться"
                    binding.btnConnectIp.isEnabled = true
                }
                
                CallService.BROADCAST_PEERS_CHANGED -> {
                    // Peers list updated, refresh on next polling cycle
                    // This is a hint to refresh immediately if needed
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        requestPermsIfNeeded()
        setupRecyclerView()
        setupConnectButton()
        registerEventReceivers()
        bindAndStartService()
    }

    override fun onDestroy() {
        scope.cancel()
        try {
            unregisterReceiver(callReceiver)
        } catch (_: Exception) {
        }
        if (serviceBound) unbindService(serviceConnection)
        super.onDestroy()
    }

    private fun setupRecyclerView() {
        peerAdapter = PeerAdapter(peers) { peer ->
            selectedPeer = peer
            peerAdapter.setSelected(peer)
            binding.btnConnect.isEnabled = true
            Toast.makeText(this, "Выбрано: ${peer.name}", Toast.LENGTH_SHORT).show()
        }
        binding.rvPeers.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = peerAdapter
        }
    }

    private fun setupConnectButton() {
        binding.btnConnect.isEnabled = false
        binding.btnConnect.setOnClickListener {
            val peer = selectedPeer ?: return@setOnClickListener
            connectToPeer(peer)
        }

        binding.btnConnectIp.setOnClickListener {
            val host = normalizeHost(binding.etDirectIp.text?.toString().orEmpty())
            if (!isValidHost(host)) {
                Toast.makeText(this, "Укажите корректный IP или хост", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (host.isBlank()) {
                Toast.makeText(this, "Введите IP или хост", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.etDirectIp.setText(host)
            connectToHost(host)
        }
        
        binding.btnScan.setOnClickListener {
            callService?.startManualDiscovery()
            Toast.makeText(this, "Сканирование запущено", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateEmptyState() {
        val empty = peers.isEmpty()
        binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.rvPeers.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun startPeerPolling() {
        scope.launch {
            while (isActive) {
                val svc = callService ?: break
                val current = svc.getKnownPeers()

                val toRemove = peers.filter { p -> current.none { peerKey(it) == peerKey(p) } }
                toRemove.forEach { p ->
                    val i = peers.indexOfFirst { peerKey(it) == peerKey(p) }
                    if (i >= 0) {
                        peers.removeAt(i)
                        peerAdapter.notifyItemRemoved(i)
                    }
                    if (selectedPeer?.let { peerKey(it) } == peerKey(p)) {
                        selectedPeer = null
                        binding.btnConnect.isEnabled = false
                    }
                }

                current.filter { p -> peers.none { peerKey(it) == peerKey(p) } }.forEach { p ->
                    peers.add(p)
                    peerAdapter.notifyItemInserted(peers.size - 1)
                }

                binding.tvLocalIp.text = "Ваш IP: ${svc.getLocalIpAddress() ?: "—"}"

                updateEmptyState()
                delay(2_000)
            }
        }
    }

    private fun connectToPeer(peer: PeerInfo) {
        val svc = callService ?: return
        binding.btnConnect.isEnabled = false
        binding.btnConnect.text = "Подключение..."
        binding.btnConnectIp.isEnabled = false

        scope.launch {
            val target = if (peer.viaServer) peer.id else peer.ip
            val remoteAudioPort = svc.signalingServer.sendCallRequest(
                target = target,
                preferRelay = peer.viaServer
            )
            if (remoteAudioPort != null) {
                val relaySession = svc.signalingServer.takePendingRelaySession(target)
                if (relaySession != null) {
                    svc.applyRelaySessionAndBeginCall(peer, relaySession)
                } else {
                    svc.beginCall(
                        remoteIp = peer.ip,
                        remotePort = remoteAudioPort,
                        remoteLabel = peer.name.ifBlank { peer.ip },
                        peerId = peer.id
                    )
                }
                openCallScreen(peer.name.ifBlank { peer.ip })
            } else {
                Toast.makeText(this@MainActivity, "Устройство не отвечает", Toast.LENGTH_SHORT).show()
                binding.btnConnect.isEnabled = (selectedPeer != null)
                binding.btnConnect.text = "Подключиться"
                binding.btnConnectIp.isEnabled = true
            }
        }
    }

    private fun connectToHost(host: String) {
        val svc = callService ?: return
        binding.btnConnect.isEnabled = false
        binding.btnConnect.text = "Подключение..."
        binding.btnConnectIp.isEnabled = false

        scope.launch {
            val remoteAudioPort = svc.signalingServer.sendCallRequest(host, preferRelay = false)
            if (remoteAudioPort != null) {
                svc.beginCall(
                    remoteIp = host,
                    remotePort = remoteAudioPort,
                    remoteLabel = host
                )
                openCallScreen(host)
            } else {
                Toast.makeText(this@MainActivity, "Устройство не отвечает", Toast.LENGTH_SHORT).show()
                binding.btnConnect.isEnabled = (selectedPeer != null)
                binding.btnConnect.text = "Подключиться"
                binding.btnConnectIp.isEnabled = true
            }
        }
    }

    private fun openCallScreen(remoteLabel: String) {
        runOnUiThread {
            binding.btnConnect.isEnabled = true
            binding.btnConnect.text = "Подключиться"
            binding.btnConnectIp.isEnabled = true
            startActivity(Intent(this, CallActivity::class.java).apply {
                putExtra(CallService.EXTRA_REMOTE_IP, remoteLabel)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }
    }

    private fun peerKey(peer: PeerInfo): String {
        return if (peer.viaServer) "srv:${peer.id}" else "ip:${peer.ip}"
    }

    private fun bindAndStartService() {
        val intent = Intent(this, CallService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun registerEventReceivers() {
        val filter = IntentFilter().apply {
            addAction(CallService.BROADCAST_CALL_STARTED)
            addAction(CallService.BROADCAST_CALL_ENDED)
            addAction(CallService.BROADCAST_PEERS_CHANGED)
        }
        ContextCompat.registerReceiver(
            this,
            callReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun requestPermsIfNeeded() {
        val missing = requiredPerms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun normalizeHost(raw: String): String {
        val input = raw.trim()
        if (input.isEmpty()) return ""
        val normalized = if (input.contains("://")) input else "tcp://$input"
        return try {
            val uri = URI(normalized)
            uri.host?.trim().orEmpty().removePrefix("[").removeSuffix("]")
        } catch (_: Exception) {
            val endpoint = input.substringBefore('/').trim()
            val colonCount = endpoint.count { it == ':' }
            if (colonCount == 1 && endpoint.substringAfter(':').all { it.isDigit() }) {
                endpoint.substringBefore(':').trim()
            } else {
                endpoint.removePrefix("[").removeSuffix("]")
            }
        }
    }

    private fun isValidHost(host: String): Boolean {
        if (host.isBlank()) return false
        if (Patterns.IP_ADDRESS.matcher(host).matches()) return true
        if (host.equals("localhost", ignoreCase = true)) return true
        val domainRegex =
            Regex("^(?=.{1,253}$)(?!-)[A-Za-z0-9-]{1,63}(?<!-)(\\.(?!-)[A-Za-z0-9-]{1,63}(?<!-))*$")
        return domainRegex.matches(host)
    }
}
