package com.localcall.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.localcall.app.activities.SettingsActivity
import com.localcall.app.services.CallService

class RestartServiceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val keepAlive = prefs.getBoolean(SettingsActivity.KEY_BACKGROUND_SERVICE, true)
        if (!keepAlive) return

        val serviceIntent = Intent(context, CallService::class.java).apply {
            action = CallService.ACTION_START_BACKGROUND_SERVICE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}

