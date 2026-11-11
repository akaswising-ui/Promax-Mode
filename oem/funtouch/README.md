# Promax Live Game Voice for Funtouch OS

This folder is the OEM-side AIDL Effects HAL adapter. It is not part of the
regular Android APK build. It reuses Promax's shared C++ DSP inside the
microphone preprocessing pipeline.

## What this enables

When the adapter is compiled into the device's AIDL Effects HAL library,
registered in the existing audio-effects config, attached by Funtouch audio
policy to an allowlisted game's exact microphone capture session, and enabled
by the privileged Game Assistant controller, that game can capture the
processed voice as its microphone input. No external microphone or root is
needed on a product image that already contains this system integration.

## Integrate into an AOSP/Funtouch source tree

Target Android 14+ devices using Stable AIDL Effects HAL.

1. Copy `PromaxVoiceEffect.cpp`, `PromaxVoiceEffect.h`,
   `PromaxVoiceDsp.cpp` and `PromaxVoiceDsp.h` into:
   `hardware/interfaces/audio/aidl/default/promax/`.
2. Append the contents of `Android.bp.fragment` to
   `hardware/interfaces/audio/aidl/default/Android.bp`. The module is placed
   in this AOSP package so it can use `aidlaudioeffectservice_defaults` and
   `:effectCommonFile` from the same package.
3. Merge only the library and effect registration elements from
   `audio_effects_config.fragment.xml` into the existing AIDL
   `audio_effects_config.xml`. Do not overwrite the device's current
   audio configuration.
4. Make sure the device's AIDL Effects HAL factory loads this configuration
   and the library is installed to the vendor sound-effects directory.
5. Add a Funtouch audio-policy extension that associates the effect with the
   exact microphone capture session/UID of an allowlisted foreground game.
   Do not attach a global `mic` preprocessing rule in production: it can
   alter voice from unrelated apps using the same source.
6. Add a platform-signed Funtouch Game Assistant controller that sends the
   packet defined in `CONTROL_PROTOCOL.md` to that game's effect instance.
   Disable/detach it on every exit or microphone privacy/error path.
7. Build with the product's normal target, then verify the generated
   `libpromaxvoiceaidl.so` is in the correct vendor partition/ABI.
8. Run Effects HAL VTS, CTS audio tests and the game/microphone regression
   matrix before enabling the feature for users.

Example build check after adding the module to the AOSP default tree:

```sh
source build/envsetup.sh
lunch <your_funtouch_product>-userdebug
m libpromaxvoiceaidl
```

## Important constraints

- Android 14+ uses the Stable AIDL Audio HAL interfaces; the actual vendor
  device must already ship a compatible AIDL Effects HAL factory. A vendor
  still using only the legacy effects path needs a separate legacy/HIDL
  adapter for that exact Android branch.
- The checked-in adapter has not been built against Vivo's proprietary
  Funtouch OS source tree. AOSP revisions, SoC vendor audio stacks, module
  visibility and product configuration can differ. Treat the integration
  fragment as an OEM porting patch, not a prebuilt drop-in binary.
- The audio-effects XML registers the implementation but intentionally does
  not attach a global mic preprocessing chain. Game-only attachment requires
  an OEM audio-policy change that can identify a specific capture UID/session.
- The effect is disabled by default and requires a privileged controller.
  This avoids modifying regular calls, recordings and other apps' microphone
  sessions accidentally.
- This does not bypass Android microphone privacy, app permissions or audio
  capture policy.
- No universal support claim is made for every Vivo or Android model.

See `CONTROL_PROTOCOL.md` for the privileged control contract and safety
requirements.
