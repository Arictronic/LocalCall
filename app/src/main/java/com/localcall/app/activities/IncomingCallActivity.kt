package com.localcall.app.activities

import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.localcall.app.databinding.ActivityIncomingCallBinding
import com.localcall.app.services.CallService

/**
 * Full-screen incoming call screen.
 * Launched directly by CallService.notifyIncomingCall() so it works
 * even when the app is in the background or screen is locked.
 */
class IncomingCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIncomingCallBinding
    private var callService: CallService? = null
    private var serviceBound = false
    private var remoteIp = ""

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            callService  = (service as CallService.LocalBinder).getService()
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            callService  = null
            serviceBound = false
        }
    }

    private val callEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Caller hung up before we answered
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show on lock screen and keep screen on
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON      or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED    or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        binding   = ActivityIncomingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        remoteIp  = intent.getStringExtra(CallService.EXTRA_REMOTE_IP) ?: ""
        binding.tvCallerIp.text = remoteIp

        binding.btnAccept.setOnClickListener {
            startService(Intent(this, CallService::class.java).apply {
                action = CallService.ACTION_ACCEPT_CALL
            })
            callService?.acceptIncomingCall()
            startActivity(Intent(this, CallActivity::class.java).apply {
                putExtra(CallService.EXTRA_REMOTE_IP, remoteIp)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
            finish()
        }

        binding.btnReject.setOnClickListener {
            startService(Intent(this, CallService::class.java).apply {
                action = CallService.ACTION_REJECT_CALL
            })
            callService?.rejectIncomingCall()
            finish()
        }

        // Listen for caller hanging up before we answer
        ContextCompat.registerReceiver(
            this,
            callEndedReceiver,
            IntentFilter(CallService.BROADCAST_CALL_ENDED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        bindService(Intent(this, CallService::class.java),
            serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        unregisterReceiver(callEndedReceiver)
        if (serviceBound) unbindService(serviceConnection)
        super.onDestroy()
    }

    @Deprecated("Deprecated")
    override fun onBackPressed() {
        // Don't dismiss on back — only explicit buttons
    }
}
