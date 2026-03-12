package com.localcall.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.localcall.app.activities.SettingsActivity
import com.localcall.app.services.CallService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val intentAction = intent?.action ?: return
        if (
            intentAction != Intent.ACTION_BOOT_COMPLETED &&
            intentAction != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val prefs = context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val keepAlive = prefs.getBoolean(SettingsActivity.KEY_BACKGROUND_SERVICE, true)
        if (!keepAlive) return

        val serviceIntent = Intent(context, CallService::class.java).apply {
            this.action = CallService.ACTION_START_BACKGROUND_SERVICE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
