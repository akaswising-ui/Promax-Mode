# Promax OS-level game voice integration

## Current change
The native processor is extracted to `native/voice_dsp` as a reusable C++17 engine. Android Voice Studio JNI uses the same per-instance processor. It supports float mono/stereo and PCM16 at 44.1/48 kHz with bounded pitch, formant and gate settings.

## Why the APK cannot inject into a game
Android's public app APIs do not let an ordinary app replace another app's microphone capture stream. A foreground service that reads the built-in microphone and plays processed audio back is only a monitor, not a virtual input device for PUBG, Free Fire, Discord or another process. Shizuku/ADB shell does not grant ownership of the vendor Audio Effects HAL.

## Required OEM/AOSP integration
1. Build this core into a vendor audio-effect library.
2. Implement the device's Effects HAL adapter: Stable AIDL on Android 14+ or the supported HIDL Effects HAL on older builds.
3. Register the implementation UUID and library in the device audio-effects configuration.
4. Attach the effect as preprocessing to the microphone sources used by games, normally at least `mic` and `voice_communication`. Verify each game's actual source.
5. Add a privileged system control service to pass effect settings to the HAL. A normal APK cannot control another app's capture session or write vendor properties.
6. Add vendor SELinux/property rules, service registration, product makefiles and platform signing.
7. Run HAL VTS and test concurrent capture, microphone privacy, latency, clipping, Bluetooth changes and actual in-game voice chat.

## AIDL audio-effects configuration shape
Merge an equivalent stanza into the OEM's existing `audio_effects_config.xml` only after the vendor HAL library and UUIDs exist:

```xml
<libraries><library name="promax_voice" path="libpromaxvoiceaidl.so"/></libraries>
<effects><effect name="promax_voice_changer" library="promax_voice"
 uuid="REPLACE_WITH_IMPLEMENTATION_UUID" type="REPLACE_WITH_EFFECT_TYPE_UUID"/></effects>
<preprocess>
 <stream type="mic"><apply effect="promax_voice_changer"/></stream>
 <stream type="voice_communication"><apply effect="promax_voice_changer"/></stream>
</preprocess>
```

This is a merge template, not a ready-to-install binary or a replacement for the OEM's full audio configuration. Android 14+ AIDL audio policy is served through the HAL; older HIDL builds use their supported vendor configuration.

## No-root boundary
The user needs no root only when an OEM or properly built custom system image already ships the effect and privileged controller. Installing this APK on unchanged stock firmware cannot add an Effects HAL implementation. Universal support across every Android phone cannot be guaranteed because HAL versions, routing and policy differ.

## DSP tests
```sh
cmake -S native/voice_dsp -B .build/voice-dsp
cmake --build .build/voice-dsp --parallel
ctest --test-dir .build/voice-dsp --output-on-failure
```
