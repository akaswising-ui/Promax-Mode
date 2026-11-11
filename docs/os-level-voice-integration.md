# Promax OS-level game voice integration

## Current implementation split

- `native/voice_dsp` contains the reusable C++17 DSP core used by the Promax Android live preview.
- `oem/funtouch` contains an AOSP Stable AIDL Effects HAL adapter, the audio-effects config fragment, the Soong integration fragment and the privileged control protocol.
- The normal Gradle APK remains a local microphone preview. The OEM adapter is separate source code and is not installed by the APK.

## Why the APK cannot inject into a game

Android's public app APIs do not let an ordinary app replace another app's microphone capture stream. A foreground service that reads the built-in microphone and plays processed audio back is only a monitor, not a virtual input device for PUBG, Free Fire, Discord or another process. Shizuku/ADB shell does not grant ownership of the vendor Audio Effects HAL.

## OEM adapter now in this branch

The Funtouch package implements the AOSP `EffectImpl` plugin contract and reuses Promax's DSP during float PCM microphone preprocessing. It advertises the effect as `PRE_PROC`, exports the AIDL effect-library entry points and starts in bypass mode.

Key files:

- `oem/funtouch/PromaxVoiceEffect.cpp` and `.h`: AIDL effect instance, effect UUIDs, control packet parsing and real-time DSP processing.
- `oem/funtouch/PromaxVoiceDsp.cpp` and `.h`: bundled copy of the shared native DSP for the AOSP source tree.
- `oem/funtouch/Android.bp.fragment`: Soong module fragment for the AOSP AIDL Effects HAL default package.
- `oem/funtouch/audio_effects_config.fragment.xml`: library/effect registration fragment.
- `oem/funtouch/CONTROL_PROTOCOL.md`: control packet contract for the privileged Funtouch system component.
- `oem/funtouch/README.md`: porting and build procedure.

## AOSP/Funtouch integration requirements

1. Use the AIDL Effects HAL on Android 14+ or write a separate adapter for an older HIDL/legacy effects branch.
2. Copy the four native source/header files into `hardware/interfaces/audio/aidl/default/promax/`.
3. Append `Android.bp.fragment` to `hardware/interfaces/audio/aidl/default/Android.bp`, then build `libpromaxvoiceaidl`.
4. Merge the XML fragment into the existing device AIDL effects config. Do not replace the vendor's current configuration.
5. Confirm the product's AIDL Effects HAL factory loads the new library. Extend Funtouch audio policy so it attaches the effect only to the allowlisted game's exact capture session/UID.
6. Add a platform-signed Funtouch Game Assistant controller. It must enable the effect only for an allowlisted foreground game session and turn it off on exit, privacy-mic disable, call takeover, device change or controller failure.
7. Run AIDL Effects HAL VTS, CTS audio tests, SELinux/product integration checks and real game voice-chat tests.

Android 14+ uses Stable AIDL for the Audio HAL. AOSP's default AIDL implementation is the reference for the Effects HAL plugin API. The AIDL adapter derives from AOSP's `EffectImpl` helper and must be compiled against the exact AOSP revision used by the product. The checked-in Soong fragment is intentionally placed in the AOSP default package because it references that package's `aidlaudioeffectservice_defaults` and `:effectCommonFile` modules. [AIDL Audio HAL reference](https://source.android.com/docs/core/audio/aidl-implement).

## Capture-source mapping

The checked-in XML fragment registers the library and effect but intentionally does not attach a global `<preprocess>` mapping. A global `mic` or `voice_communication` mapping may affect unrelated apps that use the same source. Funtouch's audio-policy extension must identify the game capture UID/session and attach the effect to that session only. The actual AudioSource is still important to confirm, but source type alone does not isolate a particular game.

## Control and privacy behavior

The AIDL adapter is disabled by default. A privileged platform-signed controller must send the 24-byte version-1 `vendorEffect` packet documented in `oem/funtouch/CONTROL_PROTOCOL.md`. Ordinary APKs must not write protected vendor properties or issue arbitrary shell commands to control the HAL. The audio-policy layer must not attach the effect globally to ordinary calls, recordings, assistant hotword capture or accessibility sessions.

## Current validation status

- Shared DSP host tests and the regular Android preview build were already covered by the earlier shared-DSP CI run.
- This OEM AIDL adapter has **not** been built against Vivo's proprietary Funtouch OS source tree or run on a Vivo device.
- The APK has **not** gained a working game-microphone injection path by itself.
- Actual teammate hearing is only established after the system image contains the adapter, Funtouch audio policy attaches it to the game's exact capture session/UID, the privileged controller enables it and in-game loopback/remote tests pass.

## No-root boundary

No root is required at runtime on a product image that already ships this OEM/system integration. Installing the ordinary APK on unchanged stock firmware cannot add an Effects HAL implementation or obtain platform signing privileges. Support depends on Android branch, SoC audio HAL, routing policy, microphone source and vendor permissions; universal support across every Android model is not guaranteed.

## DSP tests

```sh
cmake -S native/voice_dsp -B .build/voice-dsp
cmake --build .build/voice-dsp --parallel
ctest --test-dir .build/voice-dsp --output-on-failure
```
