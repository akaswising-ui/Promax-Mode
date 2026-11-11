# Promax implementation notes

## Overlay lifecycle
- A user explicitly starts GameAssistantService from the dashboard.
- The service posts an ongoing foreground notification.
- It samples Usage Access events to detect one of the user's selected packages.
- When the selected game is foreground, a small right-edge overlay handle appears.
- Swipe left on the handle to open the assistant; swipe right on the title bar or tap close to dismiss it.
- The panel owns a Tools tab and an HTTPS-only WebView tab.

## Shizuku
- ShizukuProvider is declared in the app manifest.
- ShizukuController tracks binder/permission/service lifecycle.
- PromaxShellService is an AIDL UserService with a deliberately restricted method set.
- It uses only fixed arguments for SurfaceFlinger diagnostics; no command string is provided by the UI.
- Shared-storage paths are canonicalized and constrained to /storage/emulated/0.

## Native audio
- promax_dsp.cpp owns the PCM DSP and Shine MP3 encoder JNI entry points.
- VoiceRecorder supplies PCM chunks from AudioRecord and routes processed chunks through AudioTrack.
- UI calls are NativeDsp.process(samples, count, effect, pitch, formant, gate) and NativeMp3.encodeWavToMp3(input, output).
- Voice profiles are local JSON in SharedPreferences.

## Build dependencies
- Shizuku API/Provider 13.1.5
- NDK 27.2.12479018
- CMake 3.22.1
- AGP 9.3.1 / Gradle 9.5.0
