# Android capability and permission notes

## Overlay and browser
SYSTEM_ALERT_WINDOW is special app access. The user must enable Display over other apps in Settings. Promax starts the overlay only after the user starts its foreground assistant service. The browser uses a WebView with HTTPS navigation only; JavaScript, local file access, content access and mixed content are disabled.

## Usage Access
PACKAGE_USAGE_STATS is granted in Settings. Promax reads UsageEvents to detect a selected launcher app in the foreground. OEMs can delay, restrict or change event visibility, so game detection is best-effort.

## Foreground service
The assistant is a user-started specialUse foreground service and declares its subtype in the manifest. Android/Play distribution requirements still apply; declaring a service type does not guarantee store approval.

## Notifications and DND
The notification listener requires separate user approval. It dismisses ordinary posted notifications while the toggle is enabled; it does not stop apps from posting notifications. DND access is separate and silences call interruption alerts. It does not reject or decline the call.

## Shizuku
Promax uses Shizuku API/Provider 13.1.5 with an AIDL UserService. The user installs and starts Shizuku separately and explicitly grants Promax permission. The helper reports UID 0 (root) or UID 2000 (shell/ADB).

ADB shell is not root and cannot access arbitrary app-private data under /data/user/0/package. SELinux policy, Android release and OEM changes affect what is accessible. The file manager is constrained to /storage/emulated/0 shared storage. It has canonical path checks and non-recursive deletion. It does not execute arbitrary shell text or edit protected partitions.

## FPS and GPU
FPS sampling uses SurfaceFlinger's timestats commands and filters a matching layer/package name from the dump. The layer has to be exposed by the device build and the Shizuku identity must be allowed to query it. Unsupported devices return FPS_UNAVAILABLE.

GPU sampling probes the Qualcomm KGSL gpubusy node. This is device-wide GPU busy time, not a game-process GPU percentage. Other vendors may use different private counters; when the node is absent or unreadable the UI reports GPU_UNAVAILABLE.

## Voice
AudioRecord captures microphone PCM, native C++ DSP modifies samples and AudioTrack monitors them inside Promax. It is not a virtual microphone for Discord, cellular calls or another game's process. NoiseSuppressor is used only when the device advertises it; the noise gate is a separate threshold processor.

The pitch slider uses a granular delay-line pitch shift. Formant control uses biquad spectral shaping and is not a studio-grade formant-preserving vocoder. Child and Old Man presets combine those controls; they do not generate neural character voices.

## MP3
The Shine Layer III encoder is included with its upstream LGPL v2 license at app/src/main/cpp/shine/COPYING. Current MP3 export supports Promax 44.1 kHz mono PCM16 WAV recordings. Imported MP3 files are playable through the Android soundboard.

## Package visibility
The manifest declares launcher-intent visibility instead of QUERY_ALL_PACKAGES. Users can select apps visible as launcher activities. Game classification is heuristic and editable.
