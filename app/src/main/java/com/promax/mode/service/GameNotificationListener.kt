package com.promax.mode.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class GameNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val prefs = getSharedPreferences("promax_settings", MODE_PRIVATE)
        if (!prefs.getBoolean("block_notifications", false)) return

        // Never try to cancel call/telecom notifications. DND can silence call alerts separately.
        val packageName = sbn.packageName
        val protectedPackages = setOf(
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.incallui",
            "com.google.android.dialer"
        )
        if (packageName == this.packageName || packageName in protectedPackages) return
        if (!sbn.isOngoing) cancelNotification(sbn.key)
    }
}
