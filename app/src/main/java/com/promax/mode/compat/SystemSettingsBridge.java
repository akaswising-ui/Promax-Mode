package com.promax.mode.compat;

import android.content.Intent;
import android.provider.Settings;

/**
 * Small Java bridge for settings intents used by the Kotlin application.
 * Keep platform compatibility helpers here when integrating older Java SDKs.
 */
public final class SystemSettingsBridge {
    private SystemSettingsBridge() {}

    public static Intent notificationAccessIntent() {
        return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
    }

    public static Intent notificationPolicyIntent() {
        return new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
    }
}
