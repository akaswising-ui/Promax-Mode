# Promax Funtouch microphone effect control protocol

This is an OEM/system integration contract. It is not callable by an ordinary
third-party APK. The Funtouch Game Assistant and audio-policy layer must own
the effect-control path.

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

## Required Game Assistant and audio-policy behavior

1. Detect the foreground game using the trusted Funtouch game-session API.
2. Confirm that the package/UID is in the OEM's supported-game allowlist.
3. Confirm that the game actually opened a microphone capture session and
   identify the exact input session owned by that UID.
4. Extend the Funtouch audio-policy path so the Promax effect is attached only
   to that game's capture session/UID. Do not rely on a global
   `<preprocess><stream type="mic">` rule for game-only behavior; that rule
   can affect other apps that use the same AudioSource.
5. Send the version-1 packet to that specific effect instance. Set
   `enabled=1` only while the allowlisted game owns the active mic session.
6. Disable/detach the effect on game exit, mic-session close, microphone
   privacy toggle, call takeover, user stop, audio-device change, service
   failure or controller death.
7. Never enable the effect globally for every microphone session by default.

The platform controller must use the device/vendor supported system API to
address the exact game capture effect instance. It must not rely on an ordinary
app writing protected properties or executing arbitrary shell commands.

## Runtime and routing checks

- Query the actual AudioSource used by each game, but treat it only as one
  input to the policy decision. Source type alone is not sufficient to isolate
  one game from other apps using that same source.
- Verify the capture session UID and the effect chain association in the
  vendor audio-policy implementation.
- Check that the game capture path contains Promax by instrumenting the vendor
  Effects HAL and comparing processed input/output energy during a test.
- Test that ordinary calls, voice notes, assistant hotword capture,
  accessibility capture, camera recording, Bluetooth headsets and microphone
  privacy remain unchanged.
- The effect must bypass unchanged when disabled, unsupported formats are
  received, or the controller cannot provide a valid packet.
- Run Audio Effects HAL VTS and OEM audio regression tests before shipping.

## Security boundary

Only a platform-signed Funtouch system component should be allowed to control
the game-scoped microphone effect. An APK installed by a user cannot install
or replace the vendor HAL library, edit the vendor effect configuration, or
obtain platform signing privileges.
