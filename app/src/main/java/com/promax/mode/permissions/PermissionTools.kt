package com.promax.mode.permissions

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.promax.mode.compat.SystemSettingsBridge

object PermissionTools {
    fun hasOverlay(context: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun hasNotificationPolicyAccess(context: Context): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return Build.VERSION.SDK_INT < 23 || manager.isNotificationPolicyAccessGranted
    }

    fun hasNotificationListenerAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabled.split(':').any { it.contains(context.packageName) }
    }

    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + context.packageName)
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openUsageSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openNotificationListenerSettings(context: Context) {
        context.startActivity(
            SystemSettingsBridge.notificationAccessIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openNotificationPolicySettings(context: Context) {
        context.startActivity(
            SystemSettingsBridge.notificationPolicyIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

object DndController {
    private const val PREFS = "promax_settings"
    private const val KEY_FILTER = "previous_dnd_filter"
    private const val KEY_CATEGORIES = "previous_dnd_categories"
    private const val KEY_CALL_SENDERS = "previous_dnd_call_senders"
    private const val KEY_MESSAGE_SENDERS = "previous_dnd_message_senders"
    private const val KEY_SUPPRESSED = "previous_dnd_suppressed"

    /**
     * Silences call interruptions through Android Do Not Disturb.
     * It does not reject the actual phone call. The previous policy is restored
     * when this shortcut is switched off.
     */
    fun silenceCalls(context: Context, enabled: Boolean): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT < 23 || !manager.isNotificationPolicyAccessGranted) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        return try {
            if (enabled) {
                if (!prefs.contains(KEY_FILTER)) {
                    val old = manager.notificationPolicy
                    prefs.edit()
                        .putInt(KEY_FILTER, manager.currentInterruptionFilter)
                        .putInt(KEY_CATEGORIES, old.priorityCategories)
                        .putInt(KEY_CALL_SENDERS, old.priorityCallSenders)
                        .putInt(KEY_MESSAGE_SENDERS, old.priorityMessageSenders)
                        .putInt(KEY_SUPPRESSED, if (Build.VERSION.SDK_INT >= 28) old.suppressedVisualEffects else 0)
                        .apply()
                }
                val old = manager.notificationPolicy
                val categories = old.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS.inv()
                val changed = if (Build.VERSION.SDK_INT >= 28) {
                    NotificationManager.Policy(
                        categories,
                        old.priorityCallSenders,
                        old.priorityMessageSenders,
                        old.suppressedVisualEffects
                    )
                } else {
                    NotificationManager.Policy(categories, old.priorityCallSenders, old.priorityMessageSenders)
                }
                manager.setNotificationPolicy(changed)
                manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            } else if (prefs.contains(KEY_FILTER)) {
                val categories = prefs.getInt(KEY_CATEGORIES, 0)
                val callSenders = prefs.getInt(KEY_CALL_SENDERS, NotificationManager.Policy.PRIORITY_SENDERS_ANY)
                val messageSenders = prefs.getInt(KEY_MESSAGE_SENDERS, NotificationManager.Policy.PRIORITY_SENDERS_ANY)
                val suppressed = prefs.getInt(KEY_SUPPRESSED, 0)
                val restored = if (Build.VERSION.SDK_INT >= 28) {
                    NotificationManager.Policy(categories, callSenders, messageSenders, suppressed)
                } else {
                    NotificationManager.Policy(categories, callSenders, messageSenders)
                }
                manager.setNotificationPolicy(restored)
                manager.setInterruptionFilter(
                    prefs.getInt(KEY_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL)
                )
                prefs.edit()
                    .remove(KEY_FILTER)
                    .remove(KEY_CATEGORIES)
                    .remove(KEY_CALL_SENDERS)
                    .remove(KEY_MESSAGE_SENDERS)
                    .remove(KEY_SUPPRESSED)
                    .apply()
            }
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
