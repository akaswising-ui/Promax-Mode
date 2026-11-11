# Live voice monitor

## What it does

Promax can capture the device microphone while its Voice Studio is foreground-started, run the current native C++ voice DSP chain (preset, pitch, formant and noise gate), then play the processed PCM to the selected media output through a low-latency AudioTrack. The live monitor runs as an Android microphone foreground service and keeps a visible notification with a Stop action.

Use wired or Bluetooth headphones. Playing a microphone monitor through the phone speaker can feed the processed sound back into the microphone and create echo/feedback.

## What it does not do

This is a live *monitor/preview*, not a virtual microphone. A normal third-party Android app cannot replace the microphone input stream consumed by Free Fire, Discord, a phone call, or another app. Promax does not inject the processed signal into another app's AudioRecord path.

Android manages concurrent microphone capture by priority. Two ordinary apps cannot always capture simultaneously; when another app owns a privacy-sensitive voice-communication or camcorder input, Android can deliver silence to Promax's AudioRecord. Promax listens to AudioRecordingCallback and shows a "microphone paused by Android" notification when the active recording configuration reports that its capture is silenced.

Official Android references:
- https://developer.android.com/media/platform/sharing-audio-input
- https://developer.android.com/reference/android/media/AudioRecord
- https://source.android.com/docs/core/audio/concurrent

## How to use

1. Grant Promax the Android microphone permission.
2. Open Voice Studio while Promax is visible.
3. Choose a preset and adjust pitch/formant/noise gate.
4. Tap **Start Live Preview** and use headphones.
5. Return to the game. The notification remains available so the monitor can be stopped.
6. If the game takes exclusive microphone priority, Android may silence Promax's preview. The game still receives its own physical microphone stream, not the processed Promax output.

## What would be required for true in-game voice injection

A virtual microphone or audio-HAL / OEM integration that makes the processed stream a system input device is required. This is outside the public API available to a normal Play-installed app and is not included here. Root alone does not automatically create a virtual microphone. Promax deliberately does not claim that a live-preview toggle changes the microphone used by another app.
