package com.promax.mode.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Environment
import android.os.Handler
import android.os.Looper
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Captures microphone PCM, processes it in native C++ and stores the result as
 * a local WAV. Audio is not injected into other apps or phone calls.
 */
class VoiceRecorder(private val context: Context) {
    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    }

    @Volatile private var recording = false
    private var worker: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start(
        effect: VoiceEffect,
        pitchSemitones: Float,
        formantSemitones: Float,
        noiseGateDb: Float,
        systemNoiseSuppression: Boolean,
        onSaved: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        if (recording) return
        recording = true

        worker = Thread {
            var recorder: AudioRecord? = null
            var player: AudioTrack? = null
            var noiseSuppressor: NoiseSuppressor? = null
            var outputFile: File? = null
            var sampleCount = 0L
            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_IN,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                require(minBuffer > 0) { "AudioRecord buffer setup failed: " + minBuffer }

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_IN,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer * 2, 4096)
                )
                check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                    "Microphone could not be initialized"
                }

                if (systemNoiseSuppression && NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = runCatching {
                        NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
                    }.getOrNull()
                }

                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_OUT)
                    .build()
                player = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(audioFormat)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(minBuffer * 2, 4096))
                    .build()

                val musicDir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir,
                    "Promax"
                )
                if (!musicDir.exists()) musicDir.mkdirs()
                outputFile = File(musicDir, "promax_voice_" + System.currentTimeMillis() + ".wav")

                val samples = ShortArray(1024)
                recorder.startRecording()
                player.play()

                BufferedOutputStream(FileOutputStream(outputFile)).use { stream ->
                    stream.write(ByteArray(44))
                    while (recording) {
                        val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                        if (count > 0) {
                            NativeDsp.process(
                                samples,
                                count,
                                effect.id,
                                pitchSemitones,
                                formantSemitones,
                                noiseGateDb
                            )
                            player.write(samples, 0, count, AudioTrack.WRITE_BLOCKING)
                            for (i in 0 until count) {
                                val value = samples[i].toInt()
                                stream.write(value and 0xff)
                                stream.write((value ushr 8) and 0xff)
                            }
                            sampleCount += count
                        } else if (count < 0) {
                            throw IllegalStateException("Microphone read failed: " + count)
                        }
                    }
                    stream.flush()
                }

                WavFile.writePcm16MonoHeader(outputFile, SAMPLE_RATE, sampleCount)
                val saved = outputFile
                mainHandler.post { onSaved(saved) }
            } catch (error: Exception) {
                val message = error.message ?: "Audio recording failed"
                mainHandler.post { onError(message) }
            } finally {
                recording = false
                try { recorder?.stop() } catch (_: Exception) {}
                recorder?.release()
                noiseSuppressor?.release()
                try { player?.stop() } catch (_: Exception) {}
                player?.release()
            }
        }.apply {
            name = "PromaxNativeVoiceRecorder"
            start()
        }
    }

    fun stop() {
        recording = false
    }
}
