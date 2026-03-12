package com.localcall.app

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.localcall.app.activities.MainActivity
import kotlin.system.exitProcess

class LocalCallApp : Application() {

    companion object {
        private const val CRASH_PREFS = "localcall_crash_guard"
        private const val KEY_LAST_CRASH_MS = "last_crash_ms"
        private const val KEY_CRASH_COUNT = "crash_count"
        private const val CRASH_WINDOW_MS = 60_000L
        private const val MAX_RESTARTS_IN_WINDOW = 3
    }

    override fun onCreate() {
        super.onCreate()
        installCrashRecoveryHandler()
    }

    private fun installCrashRecoveryHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (shouldScheduleRestart()) {
                scheduleMainActivityRestart()
            }
            previous?.uncaughtException(thread, throwable)
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    private fun shouldScheduleRestart(): Boolean {
        val prefs = getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CRASH_MS, 0L)
        val currentCount = if (now - last <= CRASH_WINDOW_MS) {
            prefs.getInt(KEY_CRASH_COUNT, 0) + 1
        } else {
            1
        }
        prefs.edit()
            .putLong(KEY_LAST_CRASH_MS, now)
            .putInt(KEY_CRASH_COUNT, currentCount)
            .apply()
        return currentCount <= MAX_RESTARTS_IN_WINDOW
    }

    private fun scheduleMainActivityRestart(delayMs: Long = 1500L) {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("crash_recovery", true)
        }
        val pending = PendingIntent.getActivity(
            this,
            902,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
        } else {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
        }
    }
}

