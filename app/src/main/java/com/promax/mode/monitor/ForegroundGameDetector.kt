package com.promax.mode.monitor

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build

object ForegroundGameDetector {
    fun currentPackage(context: Context): String? {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        // A long-running game may have been resumed many minutes ago. Read a wider
        // window and apply foreground/background events instead of using only recent events.
        val events = try {
            manager.queryEvents(end - 24L * 60L * 60L * 1000L, end)
        } catch (_: SecurityException) {
            return null
        } ?: return null

        val event = UsageEvents.Event()
        var foregroundPackage: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (Build.VERSION.SDK_INT >= 29) {
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> foregroundPackage = event.packageName
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED -> {
                        if (foregroundPackage == event.packageName) foregroundPackage = null
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> foregroundPackage = event.packageName
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        if (foregroundPackage == event.packageName) foregroundPackage = null
                    }
                }
            }
        }
        return foregroundPackage
    }
}
