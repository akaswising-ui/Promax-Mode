# Shizuku, FPS/GPU and file access

## What the Shizuku permission means

Promax uses the official Shizuku API and its AIDL UserService model. The user must install/start Shizuku separately and approve Promax's permission request.

The helper reports its actual UID:
- 0: root identity (normally Shizuku started as root or Sui)
- 2000: Android shell identity (normally Shizuku started through ADB/Wireless Debugging)

ADB shell is more privileged than a normal app process, but it is not root. In particular, shell cannot read arbitrary other-app private data under /data/user/0/package. OEM SELinux policy and Android version further affect access.

## File manager safety boundary

The Promax file manager root is /storage/emulated/0. Paths are canonicalized and kept inside that root. Directory deletion is non-recursive; only empty folders can be removed. Text previews are capped at 128 KiB and limited to common text extensions.

Promax does not provide unrestricted su, arbitrary shell command execution, protected partition editing, app-private-data browsing or recursive storage deletion.

## FPS

The FPS helper uses the Android developer-recommended SurfaceFlinger timestats flow:
1. dumpsys SurfaceFlinger --timestats -clear -enable
2. Play the selected game for a period.
3. dumpsys SurfaceFlinger --timestats -dump
4. Match the selected package's packageName / layerName and extract averageFPS.

OEM builds may disable or format this output differently; Promax returns FPS_UNAVAILABLE when it cannot find a matching layer/value. The reported value is an average over the sampling interval, not a frame-by-frame game SDK callback.

## GPU

The native helper probes /sys/class/kgsl/kgsl-3d0/gpubusy, which is associated with Qualcomm KGSL drivers. If readable, it computes a delta-based busy percentage. This metric is device-wide, not attributable to one game process. Unsupported nodes or permissions produce GPU_UNAVAILABLE.

## Voice and MP3

Voice processing is native C++ and uses JNI. Pitch uses a granular delay-line shifter; formant control uses two peaking biquad filters. Noise gate is a simple amplitude threshold. Android NoiseSuppressor is enabled only if the device advertises it.

MP3 export uses Shine, a fixed-point Layer III encoder. The current export path accepts only Promax mono 44.1 kHz PCM16 WAV output. The Shine source and license are included in app/src/main/cpp/shine/.
