package com.promax.mode.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Environment
import android.os.Handler
import android.os.Looper
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.tanh

enum class VoiceEffect(val title: String) {
    CLEAN("Clean"),
    ROBOT("Robot"),
    ALIEN("Alien"),
    MONSTER("Monster"),
    ECHO("Echo")
}

/**
 * Records and locally monitors processed microphone audio inside Promax.
 * Android does not let an ordinary app inject this stream as the microphone
 * for Discord, a phone call, or another game's process.
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
        onSaved: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        if (recording) return
        recording = true

        worker = Thread {
            var recorder: AudioRecord? = null
            var player: AudioTrack? = null
            var sampleCount = 0L
            var outputFile: File? = null
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

                val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                    ?: context.filesDir
                if (!musicDir.exists()) musicDir.mkdirs()
                outputFile = File(musicDir, "promax_voice_" + System.currentTimeMillis() + ".wav")

                val processor = VoiceProcessor(effect, SAMPLE_RATE)
                val samples = ShortArray(1024)
                recorder.startRecording()
                player.play()

                BufferedOutputStream(FileOutputStream(outputFile)).use { stream ->
                    stream.write(ByteArray(44))
                    while (recording) {
                        val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                        if (count > 0) {
                            processor.process(samples, count)
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
                try { player?.stop() } catch (_: Exception) {}
                player?.release()
            }
        }.apply {
            name = "PromaxVoiceRecorder"
            start()
        }
    }

    fun stop() {
        recording = false
    }

    private class VoiceProcessor(
        private val effect: VoiceEffect,
        sampleRate: Int
    ) {
        private var phase = 0.0
        private val phaseStep = 2.0 * PI * 75.0 / sampleRate
        private val delay = ShortArray(sampleRate / 4)
        private var delayIndex = 0

        fun process(buffer: ShortArray, count: Int) {
            for (i in 0 until count) {
                val input = buffer[i].toFloat()
                val wave = sin(phase).toFloat()
                val output = when (effect) {
                    VoiceEffect.CLEAN -> input
                    VoiceEffect.ROBOT -> input * wave * 0.95f
                    VoiceEffect.ALIEN -> input * (0.25f + 0.75f * wave)
                    VoiceEffect.MONSTER -> (tanh(input / 6500.0) * 26000.0).toFloat()
                    VoiceEffect.ECHO -> {
                        val delayed = delay[delayIndex].toFloat()
                        delay[delayIndex] = input.toInt().coerceIn(-32768, 32767).toShort()
                        delayIndex = (delayIndex + 1) % delay.size
                        input + delayed * 0.48f
                    }
                }
                buffer[i] = output.toInt().coerceIn(-32768, 32767).toShort()
                phase += phaseStep
                if (phase >= 2.0 * PI) phase -= 2.0 * PI
            }
        }
    }
}
