# Android capability and permission notes

## Overlay

SYSTEM_ALERT_WINDOW is special app access, not a normal runtime permission. Check Settings.canDrawOverlays() and send the user to ACTION_MANAGE_OVERLAY_PERMISSION. The overlay service starts only after the user explicitly presses Start.

## Usage Access

PACKAGE_USAGE_STATS is granted in Settings. The foreground detector reads usage events and matches the active package against the user's saved game list. Device vendors can delay or restrict usage events, so detection is best-effort.

## Foreground service

Android 14+ requires an appropriate foreground-service type. This prototype declares specialUse and describes its subtype as a user-started game overlay. Distribution still requires Play Console declaration/review where applicable. Do not silently start this service at boot.

## Notification access and DND

The notification listener is protected by BIND_NOTIFICATION_LISTENER_SERVICE and must be enabled by the user in Settings. The listener dismisses ordinary non-ongoing notifications only when the in-app toggle is enabled. Telecom packages are excluded.

DND policy access is separate. The call-alert shortcut removes the Calls category from the priority policy and saves/restores the previous filter and policy values. It silences alerts; it does not decline a call. DND is device-wide and can affect other apps.

## CPU, RAM, GPU and FPS

The app reads /proc/stat for a system CPU activity estimate and uses ActivityManager.getMemoryInfo() for aggregate RAM. Android does not provide ordinary apps with a supported, universal API for another process's FPS or GPU utilization. OEM game dashboards may use privileged APIs unavailable to third-party apps.

## Voice

The recorder captures microphone PCM, applies local DSP, plays the processed audio through an AudioTrack, and writes WAV. It is not a virtual audio input device. Android's audio-input sharing and privacy rules do not allow an ordinary app to replace another app's microphone stream.

## Package visibility

The manifest declares launcher-intent visibility instead of requesting QUERY_ALL_PACKAGES. Users can select apps visible as launcher activities. Game classification is a heuristic and should be user-editable.
