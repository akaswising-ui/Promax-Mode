package com.promax.mode.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.promax.mode.MainActivity
import com.promax.mode.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live microphone effect preview.
 *
 * This captures the physical microphone, runs Promax's native DSP and plays
 * the processed signal to the selected media output (headphones recommended).
 * Android does not expose a normal-app API for replacing another app's mic
 * input, so this service does not claim to inject audio into games or calls.
 */
class LiveVoiceMonitorService : Service() {
    companion object {
        const val ACTION_START = "com.promax.mode.voice.LIVE_START"
        const val ACTION_UPDATE = "com.promax.mode.voice.LIVE_UPDATE"
        const val ACTION_STOP = "com.promax.mode.voice.LIVE_STOP"

        private const val CHANNEL_ID = "promax_live_voice"
        private const val NOTIFICATION_ID = 7302
        private const val SAMPLE_RATE = 44_100
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val PREFS = "promax_voice_runtime"
        private const val KEY_ACTIVE = "live_monitor_active"
        private const val KEY_SILENCED = "live_monitor_silenced"
    }

    @Volatile private var settings = VoiceControlStore.load(this)
    @Volatile private var captureThread: Thread? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var noiseSuppressor: NoiseSuppressor? = null
    private val running = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioCallback by lazy {
        object : AudioRecord.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
                val activeConfig = audioRecord?.activeRecordingConfiguration
                val silenced = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    activeConfig?.isClientSilenced == true
                updateSilencedState(silenced)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                settings = VoiceControlStore.load(this)
                updateNotification()
                return START_STICKY
            }
            ACTION_START, null -> {
                settings = VoiceControlStore.load(this)
            }
            else -> return START_NOT_STICKY
        }

        if (!running.get()) {
            try {
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                startCapture()
            } catch (error: Exception) {
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putBoolean(KEY_ACTIVE, false).apply()
                stopForeground(STOP_FOREGROUND_REMOVE)
                Toast.makeText(
                    this,
                    "Live microphone could not start: " + (error.message ?: "permission or audio device unavailable"),
                    Toast.LENGTH_LONG
                ).show()
                stopSelf()
                return START_NOT_STICKY
            }
        } else {
            settings = VoiceControlStore.load(this)
            updateNotification()
        }
        return START_STICKY
    }

    private fun startCapture() {
        if (!running.compareAndSet(false, true)) return
        captureThread = Thread({
            var recorder: AudioRecord? = null
            var player: AudioTrack? = null
            var suppressor: NoiseSuppressor? = null
            var callbackRegistered = false
            try {
                val inputMin = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_IN,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val outputMin = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_OUT,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                check(inputMin > 0) { "Microphone buffer is not supported: " + inputMin }
                check(outputMin > 0) { "Audio output format is not supported: " + outputMin }

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_IN,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(inputMin * 2, 4096)
                )
                check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                    "Microphone could not be initialized"
                }
                audioRecord = recorder

                val outputFormat = AudioFormat.Builder()
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
                    .setAudioFormat(outputFormat)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(outputMin * 2, 4096))
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
                check(player.state == AudioTrack.STATE_INITIALIZED) {
                    "Audio output could not be initialized"
                }
                audioTrack = player

                val initial = settings
                if (initial.systemNoiseSuppression && NoiseSuppressor.isAvailable()) {
                    suppressor = runCatching {
                        NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
                    }.getOrNull()
                }
                noiseSuppressor = suppressor

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    recorder.registerAudioRecordingCallback(mainExecutor, audioCallback)
                    callbackRegistered = true
                }

                recorder.startRecording()
                player.play()
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putBoolean(KEY_ACTIVE, true).putBoolean(KEY_SILENCED, false).apply()
                updateNotification()

                val samples = ShortArray(1024)
                while (running.get()) {
                    val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    if (count > 0) {
                        val current = settings
                        NativeDsp.process(
                            samples,
                            count,
                            if (current.enabled) current.effect.id else VoiceEffect.CLEAN.id,
                            if (current.enabled) current.pitchSemitones else 0f,
                            if (current.enabled) current.formantSemitones else 0f,
                            if (current.enabled) current.noiseGateDb else -80f
                        )
                        val written = player.write(samples, 0, count, AudioTrack.WRITE_BLOCKING)
                        if (written < 0) throw IllegalStateException("Audio output failed: " + written)
                    } else if (count < 0 && count != AudioRecord.ERROR_INVALID_OPERATION) {
                        throw IllegalStateException("Microphone read failed: " + count)
                    }
                }
            } catch (error: Exception) {
                val message = error.message ?: "Live voice monitor stopped"
                mainHandler.post {
                    getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit().putBoolean(KEY_ACTIVE, false).apply()
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    stopSelf()
                }
            } finally {
                running.set(false)
                if (callbackRegistered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching { recorder?.unregisterAudioRecordingCallback(audioCallback) }
                }
                try { recorder?.stop() } catch (_: Exception) {}
                recorder?.release()
                try { player?.stop() } catch (_: Exception) {}
                player?.release()
                suppressor?.release()
                audioRecord = null
                audioTrack = null
                noiseSuppressor = null
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putBoolean(KEY_ACTIVE, false).putBoolean(KEY_SILENCED, false).apply()
            }
        }, "PromaxLiveVoiceMonitor").apply {
            isDaemon = true
            start()
        }
    }

    private fun updateSilencedState(silenced: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit().putBoolean(KEY_SILENCED, silenced).apply()
        updateNotification()
    }

    private fun updateNotification() {
        val notification = buildNotification()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Promax live voice monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Visible while Promax is processing live microphone audio"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            7302,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_VOICE_STUDIO, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            7303,
            Intent(this, LiveVoiceMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val silenced = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SILENCED, false)
        val title = if (silenced) "Promax live voice: microphone paused by Android" else "Promax live voice monitor active"
        val detail = if (silenced) {
            "Another app has microphone priority. Processed preview is receiving silence."
        } else {
            "Processed voice is playing to your selected audio output. Headphones recommended."
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()
    }

    private fun stopCapture() {
        running.set(false)
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioTrack?.pause() } catch (_: Exception) {}
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit().putBoolean(KEY_ACTIVE, false).putBoolean(KEY_SILENCED, false).apply()
    }

    override fun onDestroy() {
        stopCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
