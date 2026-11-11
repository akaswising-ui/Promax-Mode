# Promax Live Game Voice for Funtouch OS

This folder is the OEM-side AIDL Effects HAL adapter. It is not part of the
regular Android APK build. It reuses Promax's shared C++ DSP inside the
microphone preprocessing pipeline so the game receives processed mic samples
from Android's normal capture path.

## What this enables

When the adapter is compiled into the device's AIDL Effects HAL library,
registered in the product audio-effects configuration, attached to the
microphone source selected by a game, and enabled by a privileged Funtouch
controller, the game can capture the processed voice as its microphone input.
No external microphone or root is needed on a product image that already
contains this vendor/system integration.

## Integrate into an AOSP/Funtouch source tree

Target Android 14+ devices using Stable AIDL Effects HAL.

1. Copy `PromaxVoiceEffect.cpp`, `PromaxVoiceEffect.h`,
   `PromaxVoiceDsp.cpp` and `PromaxVoiceDsp.h` into:
   `hardware/interfaces/audio/aidl/default/promax/`.
2. Append the contents of `Android.bp.fragment` to
   `hardware/interfaces/audio/aidl/default/Android.bp`. The module is placed
   in this AOSP package so it can use `aidlaudioeffectservice_defaults` and
   `:effectCommonFile` from the same package.
3. Merge `audio_effects_config.fragment.xml` into the existing
   AIDL `audio_effects_config.xml`. Do not overwrite the device's current
   audio configuration. The explicit custom `type` UUID is supported by
   Android 16 QPR2's reference config parser; for older vendor branches, verify
   the parser supports custom type UUIDs or add the equivalent mapping to
   `EffectConfig::findUuid`.
4. Make sure the device's AIDL Effects HAL factory loads this configuration
   and the library is installed to the vendor sound-effects directory.
5. Build with the product's normal target, then verify the generated
   `libpromaxvoiceaidl.so` is in the correct vendor partition/ABI.
6. Add a platform-signed Funtouch Game Assistant controller that sends the
   packet defined in `CONTROL_PROTOCOL.md` to the active preprocessing
   effect. The effect is disabled until a valid enabled packet is received.
7. Run Effects HAL VTS, CTS audio tests and the game/microphone regression
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
- The XML fragment lists `mic` and `voice_communication` as candidate
  capture sources. Confirm each game’s actual AudioSource and audio policy.
- The effect is disabled by default and requires a privileged controller.
  This avoids modifying regular calls, recordings and other apps' microphone
  sessions accidentally.
- This does not bypass Android microphone privacy, app permissions or audio
  focus/capture policy.
- No universal support claim is made for every Vivo or Android model.

See `CONTROL_PROTOCOL.md` for the privileged control contract and safety
requirements.
