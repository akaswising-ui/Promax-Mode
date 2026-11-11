# Promax Mode

Promax Mode is an Android Game Space and Voice Studio prototype built with Kotlin, Jetpack Compose, Java compatibility helpers, Shizuku UserService and native C++ DSP.

## Toolchain

- Android Gradle Plugin: 9.3.1
- Gradle: 9.5.0
- Kotlin / Compose compiler plugin: 2.4.21
- Compile SDK: 37; target SDK: 36
- Minimum Android: API 26
- NDK: 27.2.12479018 (CI)
- CMake: 3.22.1
- Shizuku API / Provider: 13.1.5

## Current features

### Game Space
- Add/remove installed launcher apps from the game library.
- Best-effort foreground game detection through Usage Access.
- User-started foreground assistant service.
- Right-edge swipe handle opens the floating assistant panel; swipe right on its header to close.
- Tools panel: system CPU/RAM estimate, notification dismissal toggle, call-alert DND toggle and quick notes.
- Mini browser inside the overlay. HTTPS only; JavaScript, file access, content access and mixed content are disabled.
- Balanced / High changes Promax sampling interval, not the device governor.

### Shizuku and file tools
- Shizuku Binder connection and explicit permission request.
- AIDL UserService runs under Shizuku's current identity; the app reports ROOT or SHELL (ADB).
- Game FPS sampling uses SurfaceFlinger's timestats capture and attempts to read the selected package's layer averageFPS.
- GPU sampling probes the Qualcomm KGSL gpubusy counter only when it is readable. This is a device-wide GPU busy estimate, not per-game GPU attribution.
- File manager is intentionally limited to /storage/emulated/0 shared storage: list, create folders, rename, non-recursive delete of a file/empty directory and small text previews.
- Path traversal is rejected; recursive deletion and arbitrary shell-command execution are not exposed.

### Voice Studio
- Native C++ JNI audio pipeline built through NDK/CMake.
- PCM microphone recording and local monitor playback.
- Presets: Clean, Robot, Alien, Monster, Echo, Child, Old Man, Underwater.
- Pitch slider: -12 to +12 semitones.
- Formant shaping slider: -8 to +8 semitones (biquad spectral shaping, not a studio-grade formant-preserving algorithm).
- Noise gate threshold and Android NoiseSuppressor when available.
- Import audio files through Android document picker; soundboard supports MP3/WAV/M4A/OGG files readable by Android MediaPlayer.
- Export Promax's 44.1 kHz mono PCM16 recordings to 128 kbps MP3 using the Shine Layer III encoder.
- Save/load/delete up to 30 named voice profiles in local app storage.
- Built-in Android TextToSpeech.

## Shizuku setup

1. Install Shizuku separately from its official distribution.
2. Start Shizuku. On non-rooted Android 11+ devices, the standard setup uses Developer Options and Wireless Debugging. Shizuku must be restarted after reboot in ADB mode.
3. Open Promax > Access and select Request permission / connect.
4. Approve Promax in Shizuku's permission prompt.
5. Connect the helper. The UI displays the process identity.
6. Open Files to browse shared storage, or Home > Game-specific monitor to sample FPS/GPU support.

Shizuku ADB mode usually runs with shell UID 2000, not root UID 0. Access depends on Android version, SELinux policy and OEM implementation. Root/Sui mode is a different trust boundary and requires a rooted device.

## Android permission setup

- Display over other apps: required for the game sidebar.
- Usage access: best-effort foreground game detection.
- Notification access: optional; dismisses ordinary posted notifications when the in-app toggle is enabled.
- Do Not Disturb access: optional; silences call alerts, but does not reject calls.
- Microphone: required only for Voice Studio.
- Notifications: used for the user-started foreground service.

## Build

Install Android SDK Platform 37, Build Tools 36.0.0, NDK 27.2.12479018 and CMake 3.22.1. Open the project in Android Studio and sync Gradle.

CLI:

    gradle assembleDebug
    gradle testDebugUnitTest

GitHub Actions also builds app/build/outputs/apk/debug/app-debug.apk and uploads it as the promax-mode-debug artifact. A source commit is not proof that an APK build passed; check the Actions run before installing.

## Important limitations

- SurfaceFlinger FPS is best-effort: the selected game's layer must appear in the timestats dump and the shell identity must be permitted to run the diagnostic.
- KGSL GPU busy is Qualcomm-specific and device-wide. It is not a per-process GPU utilization reading. Other GPU drivers may not expose a readable counter.
- A normal app or Shizuku ADB shell cannot access arbitrary other-app private /data/user/0/package data. Root/Sui can have broader access, but it is still device/SELinux-dependent and destructive file operations remain guarded in this UI.
- Shizuku does not make every Android operation legal or technically possible. Promax does not expose a raw shell terminal, recursive storage wipe, package-data eraser or system-partition editor.
- Android does not allow an ordinary app to replace Discord, cellular-call or another game's microphone input. Voice modulation works inside Promax's recorder/player only.
- System NoiseSuppressor is hardware/device-dependent. The built-in noise gate is a separate simple threshold processor.
- Child and Old Man are pitch/formant-shaping presets, not synthesized character voice models.
- MP3 export currently accepts only Promax's own mono 44.1 kHz PCM16 WAV recordings. Imported MP3 files are for playback/soundboard use.
- Floating browser does not allow file:// navigation or JavaScript.

## Open-source notices

The MP3 encoder source in app/src/main/cpp/shine/ is the Shine Layer III encoder. Its upstream source is distributed under GNU Library General Public License v2; the corresponding license text is included at app/src/main/cpp/shine/COPYING. Keep that notice and provide corresponding source when redistributing the linked encoder.
