package com.localcall.app.activities

import android.content.*
import android.os.*
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.localcall.app.databinding.ActivityCallBinding
import com.localcall.app.services.CallService
import kotlinx.coroutines.*

class CallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallBinding
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var callService: CallService? = null
    private var serviceBound = false
    private var remoteIp = ""
    private var timerJob: Job? = null
    private var qualityJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, s: IBinder?) {
            callService  = (s as CallService.LocalBinder).getService()
            serviceBound = true
            startTimer()
            startQualityMonitor()
        }
        override fun onServiceDisconnected(n: ComponentName?) {
            callService  = null
            serviceBound = false
        }
    }

    private val callEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == CallService.BROADCAST_CALL_ENDED) {
                timerJob?.cancel()
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding  = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        remoteIp = intent.getStringExtra(CallService.EXTRA_REMOTE_IP) ?: ""
        binding.tvRemoteIp.text = remoteIp

        // Settings button - opens SettingsActivity during call
        binding.btnSettings.setOnClickListener {
            val intent = Intent(this@CallActivity, SettingsActivity::class.java)
            startActivity(intent)
        }

        binding.btnMute.setOnClickListener {
            val muted = callService?.toggleMute() ?: return@setOnClickListener
            binding.btnMute.text = if (muted) "🔇 Включить микрофон" else "🎤 Выкл. микрофон"
            binding.btnMute.alpha = if (muted) 0.6f else 1.0f
        }

        binding.btnEndCall.setOnClickListener {
            startService(Intent(this, CallService::class.java).apply {
                action = CallService.ACTION_STOP_CALL
            })
            callService?.endCall()
            finish()
        }

        ContextCompat.registerReceiver(this, callEndedReceiver,
            IntentFilter(CallService.BROADCAST_CALL_ENDED),
            ContextCompat.RECEIVER_NOT_EXPORTED)

        bindService(Intent(this, CallService::class.java),
            serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        timerJob?.cancel()
        qualityJob?.cancel()
        scope.cancel()
        try { unregisterReceiver(callEndedReceiver) } catch (_: Exception) {}
        if (serviceBound) unbindService(serviceConnection)
        super.onDestroy()
    }

    private fun startTimer() {
        val start = callService?.callStartTime ?: System.currentTimeMillis()
        timerJob = scope.launch {
            while (isActive) {
                val elapsed = System.currentTimeMillis() - start
                val s = (elapsed / 1000) % 60
                val m = (elapsed / 1000 / 60) % 60
                val h = elapsed / 1000 / 3600
                binding.tvTimer.text = if (h > 0)
                    "%02d:%02d:%02d".format(h, m, s)
                else
                    "%02d:%02d".format(m, s)
                delay(500)
            }
        }
    }
    
    private fun startQualityMonitor() {
        qualityJob = scope.launch {
            while (isActive) {
                val engine = callService?.audioEngine
                val quality = engine?.getConnectionQuality() ?: 3
                
                val icon: String
                val text: String
                val color: Int
                
                when (quality) {
                    3 -> {  // Excellent
                        icon = "📶"
                        text = "Отлично"
                        color = 0xFF4CAF50.toInt()  // Green
                    }
                    2 -> {  // Good
                        icon = "📶"
                        text = "Хорошо"
                        color = 0xFF8BC34A.toInt()  // Light green
                    }
                    1 -> {  // Fair
                        icon = "📶"
                        text = "Средне"
                        color = 0xFFFFC107.toInt()  // Amber
                    }
                    else -> {  // Poor
                        icon = "📶"
                        text = "Плохо"
                        color = 0xFFF44336.toInt()  // Red
                    }
                }
                
                binding.tvConnectionQuality.text = "$icon $text"
                binding.tvConnectionQuality.setTextColor(color)
                delay(1000)  // Update every second
            }
        }
    }

    @Deprecated("Deprecated")
    override fun onBackPressed() { moveTaskToBack(true) }
}
