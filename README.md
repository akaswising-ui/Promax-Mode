# Promax Mode

Promax Mode is an Android Game Space prototype with a user-started game overlay, a small game library, device-level CPU/RAM snapshots, notification/DND shortcuts, quick notes, and a local voice-effects recorder.

## Current build

- Android application: Kotlin + Jetpack Compose
- Minimum Android version: Android 8.0 (API 26)
- Compile SDK: 37
- Target SDK: 36
- UI: dark dashboard, Game Library, Voice Studio, Permissions
- Java: settings-intent compatibility bridge
- Voice processing: Kotlin PCM pipeline; native C++ is not required for this first pass

## Features in this first source drop

1. Add launcher apps to a persistent game list. The auto-detect hint uses Android app category and app labels; it is not a perfect game classifier.
2. Start a foreground service from the dashboard. It watches Usage Access events and displays a draggable overlay only when a selected game is in the foreground.
3. Show system-wide CPU activity estimate and RAM usage from public Android APIs.
4. Open notification-listener and Do Not Disturb settings. When granted, the overlay can dismiss ordinary notifications and silence call interruptions using DND.
5. Save a quick note in the overlay.
6. Record microphone audio inside Promax and monitor Clean, Robot, Alien, Monster or Echo processing. Output is a WAV file in the app's external files Music directory.
7. Use Android's built-in TextToSpeech engine.

## Build

Open the repository in a recent Android Studio version that supports AGP 9.5 and Android SDK 37. Install Android SDK platform/build tools for API 37, sync Gradle and run the app configuration.

Command line, after installing Android SDK and Gradle 9.6:

    gradle assembleDebug
    gradle testDebugUnitTest

The GitHub Actions workflow builds a debug APK and uploads it as a workflow artifact. This source commit does not include a compiled APK or signed release build.

## First run

1. Install the debug APK.
2. Open Promax Mode and add one or more games.
3. Open Access and grant Display over other apps and Usage access.
4. Start the floating assistant, then launch a selected game.
5. Grant Notification access only to enable notification dismissal.
6. Grant Do Not Disturb access only if you want Promax to silence call interruptions.
7. Grant microphone access only when using Voice Studio.

## Android limitations (no simulated metrics)

- FPS for another app is not exposed to ordinary third-party apps. A real cross-app FPS overlay needs OEM/system integration, a rooted device, or a game-side SDK.
- Cross-app GPU utilization is not exposed as a stable public API. Promax shows "not exposed" instead of inventing a percentage.
- Android does not let a normal app inject a replacement microphone into Discord, cellular calls, or another game's process. Voice effects here apply to audio captured and played/recorded inside Promax.
- Do Not Disturb can silence call alerts but cannot reject or block the call itself.
- Notification access can dismiss posted notifications; it cannot prevent an app from creating them.
- Third-party apps cannot force-stop other apps or clear their private memory. Clear is limited to Promax's own cache.
- Balanced/High changes Promax's sampling interval. It does not change CPU/GPU frequencies or OEM performance modes.
- Floating browser, MP3 import/export, pitch/formant shifting, soundboard, saved voice profiles and C++ native DSP are not in this first source drop.

## Repository structure

    app/
      src/main/
        AndroidManifest.xml
        java/com/promax/mode/
          MainActivity.kt
          audio/
          compat/
          data/
          monitor/
          permissions/
          service/
        res/values/strings.xml
      src/test/
    docs/
    .github/workflows/

Read docs/ANDROID_LIMITS.md before extending privileged or overlay functionality.
