# Promax Funtouch microphone effect control protocol

This is an OEM/system integration contract. It is not callable by an ordinary
third-party APK. The Funtouch Game Assistant or a platform-signed privileged
controller must own the effect-control path.

## Control packet

Send the following exact 24-byte payload as the AIDL
`Parameter::Specific::vendorEffect` extension's `DefaultExtension.bytes`:

| Offset | Type | Field | Allowed values |
|---:|---|---|---|
| 0 | uint32 little-endian | version | 1 |
| 4 | uint32 little-endian | enabled | 0 or 1 |
| 8 | int32 little-endian | effect | 0 through 9, matching Promax DSP IDs |
| 12 | float32 little-endian | pitchSemitones | -12 to +12 |
| 16 | float32 little-endian | formantSemitones | -8 to +8 |
| 20 | float32 little-endian | noiseGateDb | -80 to -20 |

The native implementation rejects wrong packet lengths, unknown versions,
invalid booleans, out-of-range effect IDs and non-finite floats. Values are
clamped to the supported DSP range. The default state is disabled.

## Required Game Assistant behavior

1. Detect the foreground game using the system's trusted game-session API.
2. Confirm that the package is in the OEM's supported-game allowlist.
3. Confirm the input route is the built-in microphone and that the capture
   path actually runs through the registered Effects HAL preprocessing chain.
4. Set `enabled=1` only for that active game session.
5. Set `enabled=0` on game exit, microphone privacy toggle, call takeover,
   user stop, audio-device change, service failure or controller death.
6. Never enable the effect globally for every microphone session by default.

The platform controller must use the device/vendor supported system API to
address the live preprocessing effect instance. It must not rely on an
ordinary app writing protected properties or executing arbitrary shell
commands.

## Runtime and routing checks

- Query the actual AudioSource used by each game. The XML fragment is only a
  candidate mapping, not proof that a title uses either listed source.
- Check that the capture chain contains Promax by instrumenting the vendor
  Effects HAL and comparing processed input/output energy during a test.
- Test that ordinary calls, assistant hotword capture, accessibility capture,
  camera recording, Bluetooth headsets and microphone privacy remain correct.
- The effect must bypass unchanged when disabled, unsupported formats are
  received, or the controller cannot provide a valid packet.
- Run Audio Effects HAL VTS and OEM audio regression tests before shipping.

## Security boundary

Only a platform-signed Funtouch system component should be allowed to control
the global/device microphone effect. An APK installed by a user cannot install
or replace the vendor HAL library, edit the vendor effect configuration, or
obtain platform signing privileges.
