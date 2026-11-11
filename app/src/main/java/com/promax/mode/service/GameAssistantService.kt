package com.promax.mode.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.promax.mode.MainActivity
import com.promax.mode.audio.VoiceControlSettings
import com.promax.mode.audio.VoiceControlStore
import com.promax.mode.audio.VoiceEffect
import com.promax.mode.audio.VoiceProfile
import com.promax.mode.audio.VoiceProfileStore
import com.promax.mode.data.GameAppRepository
import com.promax.mode.monitor.DeviceMetricsReader
import com.promax.mode.monitor.ForegroundGameDetector
import com.promax.mode.permissions.DndController
import com.promax.mode.permissions.PermissionTools

class GameAssistantService : Service() {
    companion object {
        const val ACTION_STOP = "com.promax.mode.action.STOP_OVERLAY"
        private const val CHANNEL_ID = "promax_game_assistant"
        private const val NOTIFICATION_ID = 7301
    }

    private val handler = Handler(Looper.getMainLooper())
    private val metricsReader by lazy { DeviceMetricsReader(this) }
    private var windowManager: WindowManager? = null
    private var edgeHandle: TextView? = null
    private var panel: LinearLayout? = null
    private var metricsView: TextView? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var browser: WebView? = null
    private var expanded = true

    private val pollTask = object : Runnable {
        override fun run() {
            val games = GameAppRepository.savedPackages(this@GameAssistantService)
            val foreground = if (PermissionTools.hasUsageAccess(this@GameAssistantService)) {
                ForegroundGameDetector.currentPackage(this@GameAssistantService)
            } else null

            if (foreground != null && games.contains(foreground) &&
                PermissionTools.hasOverlay(this@GameAssistantService)
            ) {
                showEdgeHandle()
                refreshMetrics()
            } else {
                removePanel()
                removeHandle()
            }
            handler.postDelayed(this, monitorInterval())
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        handler.removeCallbacks(pollTask)
        handler.post(pollTask)
        return START_STICKY
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun monitorInterval(): Long {
        val highRate = getSharedPreferences("promax_settings", MODE_PRIVATE)
            .getBoolean("high_monitor_rate", false)
        return if (highRate) 1_000L else 2_000L
    }

    private fun showEdgeHandle() {
        if (edgeHandle != null || !PermissionTools.hasOverlay(this)) return
        val handle = TextView(this).apply {
            text = "‹"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0xEE172333.toInt())
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0xFF6D9BCB.toInt())
            }
            setOnTouchListener(object : View.OnTouchListener {
                var downX = 0f
                var downY = 0f
                override fun onTouch(view: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.rawX
                            downY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            val dx = downX - event.rawX
                            val dy = kotlin.math.abs(downY - event.rawY)
                            if (dx > dp(35) || (kotlin.math.abs(dx) < dp(12) && dy < dp(35))) {
                                showPanel()
                            }
                            return true
                        }
                    }
                    return true
                }
            })
        }
        val params = WindowManager.LayoutParams(
            dp(34),
            dp(118),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 0
            y = 0
        }
        try {
            windowManager?.addView(handle, params)
            edgeHandle = handle
            handleParams = params
        } catch (_: Exception) {
            edgeHandle = null
            handleParams = null
        }
    }

    private fun showPanel() {
        if (panel != null || !PermissionTools.hasOverlay(this)) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(0xF20D1521.toInt())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0xFF526984.toInt())
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "PROMAX ASSISTANT"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(4), 0, 0, 0)
        }
        val close = Button(this).apply {
            text = "×"
            textSize = 20f
            setOnClickListener { removePanel() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(close, LinearLayout.LayoutParams(dp(54), dp(44)))
        root.addView(header)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val toolsTab = Button(this).apply { text = "Tools" }
        val voiceTab = Button(this).apply { text = "Voice" }
        val browserTab = Button(this).apply { text = "Browser" }
        tabs.addView(toolsTab, LinearLayout.LayoutParams(0, dp(42), 1f))
        tabs.addView(voiceTab, LinearLayout.LayoutParams(0, dp(42), 1f))
        tabs.addView(browserTab, LinearLayout.LayoutParams(0, dp(42), 1f))
        root.addView(tabs)

        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        fun addTools() {
            browser?.stopLoading()
            browser?.destroy()
            browser = null
            content.removeAllViews()
            val tools = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            val metrics = TextView(this).apply {
                textSize = 13f
                setTextColor(0xFFE5EDF8.toInt())
                text = "Reading device metrics..."
                setPadding(0, dp(8), 0, dp(8))
            }
            metricsView = metrics
            tools.addView(metrics)

            val prefs = getSharedPreferences("promax_settings", MODE_PRIVATE)
            val notificationButton = Button(this).apply {
                text = if (prefs.getBoolean("block_notifications", false)) {
                    "Notifications: ON"
                } else "Block notifications"
                setOnClickListener {
                    if (!PermissionTools.hasNotificationListenerAccess(this@GameAssistantService)) {
                        PermissionTools.openNotificationListenerSettings(this@GameAssistantService)
                    } else {
                        val next = !prefs.getBoolean("block_notifications", false)
                        prefs.edit().putBoolean("block_notifications", next).apply()
                        text = if (next) "Notifications: ON" else "Block notifications"
                    }
                }
            }
            tools.addView(notificationButton)

            val callsButton = Button(this).apply {
                text = if (prefs.getBoolean("silence_calls", false)) {
                    "Call alerts: SILENCED"
                } else "Silence call alerts (DND)"
                setOnClickListener {
                    if (!PermissionTools.hasNotificationPolicyAccess(this@GameAssistantService)) {
                        PermissionTools.openNotificationPolicySettings(this@GameAssistantService)
                    } else {
                        val next = !prefs.getBoolean("silence_calls", false)
                        if (DndController.silenceCalls(this@GameAssistantService, next)) {
                            prefs.edit().putBoolean("silence_calls", next).apply()
                            text = if (next) "Call alerts: SILENCED" else "Silence call alerts (DND)"
                        }
                    }
                }
            }
            tools.addView(callsButton)

            val note = EditText(this).apply {
                textSize = 13f
                hint = "Quick game note"
                setSingleLine(false)
                minLines = 2
                maxLines = 4
                setText(prefs.getString("quick_note", ""))
            }
            tools.addView(note, LinearLayout.LayoutParams(-1, dp(90)))
            tools.addView(Button(this).apply {
                text = "Save note"
                setOnClickListener {
                    prefs.edit().putString("quick_note", note.text.toString()).apply()
                    text = "Saved"
                }
            })
            content.addView(tools)
        }


        fun addVoice() {
            browser?.stopLoading()
            browser?.destroy()
            browser = null
            metricsView = null
            content.removeAllViews()

            var settings = VoiceControlStore.load(this@GameAssistantService)
            val scroll = ScrollView(this).apply { isFillViewport = true }
            val voiceLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(14))
            }
            scroll.addView(voiceLayout)
            content.addView(scroll)

            fun label(value: String, size: Float = 12f, color: Int = 0xFFE5EDF8.toInt()): TextView {
                return TextView(this).apply {
                    text = value
                    textSize = size
                    setTextColor(color)
                    setPadding(dp(2), dp(4), dp(2), dp(4))
                }
            }

            voiceLayout.addView(label("VOICE CHANGER", 16f, Color.WHITE))
            voiceLayout.addView(label(
                "Choose a preset and tune the sound. Settings are shared with Voice Studio.",
                11f, 0xFF9DAEC3.toInt()
            ))

            voiceLayout.addView(label("Voice effects", 13f, 0xFF9FC8FF.toInt()))
            val presetButtons = linkedMapOf<VoiceEffect, Button>()
            VoiceEffect.entries.chunked(2).forEach { pair ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                pair.forEach { effect ->
                    val button = Button(this).apply {
                        text = effect.title
                        textSize = 11f
                    }
                    presetButtons[effect] = button
                    row.addView(button, LinearLayout.LayoutParams(0, dp(43), 1f).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    })
                }
                voiceLayout.addView(row)
            }

            fun addSlider(
                title: String,
                minimum: Float,
                maximum: Float,
                initial: Float,
                format: (Float) -> String,
                onChanged: (Float) -> Unit
            ): Pair<SeekBar, TextView> {
                val valueLabel = label(title + ": " + format(initial), 12f, Color.WHITE)
                voiceLayout.addView(valueLabel)
                val bar = SeekBar(this).apply {
                    max = 200
                    progress = (((initial - minimum) / (maximum - minimum)) * 200f).toInt().coerceIn(0, 200)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            val value = minimum + (maximum - minimum) * progress / 200f
                            valueLabel.text = title + ": " + format(value)
                            if (fromUser) onChanged(value)
                        }
                        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                    })
                }
                voiceLayout.addView(bar, LinearLayout.LayoutParams(-1, dp(42)))
                return bar to valueLabel
            }

            val pitchControl = addSlider(
                "Pitch", -12f, 12f, settings.pitchSemitones,
                { String.format("%.1f semitones", it) }
            ) { value ->
                settings = settings.copy(pitchSemitones = value)
                VoiceControlStore.save(this@GameAssistantService, settings)
            }
            val formantControl = addSlider(
                "Formant", -8f, 8f, settings.formantSemitones,
                { String.format("%.1f semitones", it) }
            ) { value ->
                settings = settings.copy(formantSemitones = value)
                VoiceControlStore.save(this@GameAssistantService, settings)
            }
            val gateControl = addSlider(
                "Noise gate", -80f, -20f, settings.noiseGateDb,
                { if (it <= -79.5f) "Off" else String.format("%.0f dB", it) }
            ) { value ->
                settings = settings.copy(noiseGateDb = value)
                VoiceControlStore.save(this@GameAssistantService, settings)
            }

            val suppression = Switch(this).apply {
                text = "Device noise suppression"
                textSize = 12f
                setTextColor(Color.WHITE)
                isChecked = settings.systemNoiseSuppression
                setOnCheckedChangeListener { _, checked ->
                    settings = settings.copy(systemNoiseSuppression = checked)
                    VoiceControlStore.save(this@GameAssistantService, settings)
                }
            }
            voiceLayout.addView(suppression, LinearLayout.LayoutParams(-1, dp(48)))

            fun refreshControls() {
                pitchControl.first.progress =
                    (((settings.pitchSemitones + 12f) / 24f) * 200f).toInt().coerceIn(0, 200)
                formantControl.first.progress =
                    (((settings.formantSemitones + 8f) / 16f) * 200f).toInt().coerceIn(0, 200)
                gateControl.first.progress =
                    (((settings.noiseGateDb + 80f) / 60f) * 200f).toInt().coerceIn(0, 200)
                suppression.isChecked = settings.systemNoiseSuppression
                presetButtons.forEach { (effect, button) ->
                    button.text = (if (settings.effect == effect) "✓ " else "") + effect.title
                }
            }

            presetButtons.forEach { (effect, button) ->
                button.setOnClickListener {
                    settings = VoiceControlStore.withPreset(settings, effect)
                    VoiceControlStore.save(this@GameAssistantService, settings)
                    refreshControls()
                }
            }

            voiceLayout.addView(label("Saved profiles", 13f, 0xFF9FC8FF.toInt()))
            val profileContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            voiceLayout.addView(profileContainer)

            fun renderProfiles() {
                profileContainer.removeAllViews()
                val profiles = VoiceProfileStore.list(this@GameAssistantService)
                if (profiles.isEmpty()) {
                    profileContainer.addView(label("No saved profiles yet.", 11f, 0xFF9DAEC3.toInt()))
                    return
                }
                profiles.take(12).chunked(2).forEach { pair ->
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    pair.forEach { profile ->
                        val button = Button(this).apply {
                            text = profile.name
                            textSize = 10f
                            setOnClickListener {
                                settings = VoiceControlSettings(
                                    effect = profile.effect,
                                    pitchSemitones = profile.pitchSemitones,
                                    formantSemitones = profile.formantSemitones,
                                    noiseGateDb = profile.noiseGateDb,
                                    systemNoiseSuppression = profile.systemNoiseSuppression
                                )
                                VoiceControlStore.save(this@GameAssistantService, settings)
                                refreshControls()
                            }
                        }
                        row.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                            setMargins(dp(2), dp(2), dp(2), dp(2))
                        })
                    }
                    profileContainer.addView(row)
                }
            }
            renderProfiles()

            val profileName = EditText(this).apply {
                setSingleLine(true)
                textSize = 12f
                hint = "New profile name"
                setText("Floating voice")
            }
            voiceLayout.addView(profileName, LinearLayout.LayoutParams(-1, dp(48)))
            voiceLayout.addView(Button(this).apply {
                text = "Save current profile"
                setOnClickListener {
                    val name = profileName.text.toString().trim()
                    if (name.isBlank()) {
                        profileName.error = "Enter a profile name"
                    } else {
                        VoiceProfileStore.save(
                            this@GameAssistantService,
                            VoiceProfile(
                                name,
                                settings.effect,
                                settings.pitchSemitones,
                                settings.formantSemitones,
                                settings.noiseGateDb,
                                settings.systemNoiseSuppression
                            )
                        )
                        renderProfiles()
                        Toast.makeText(this@GameAssistantService, "Voice profile saved", Toast.LENGTH_SHORT).show()
                    }
                }
            }, LinearLayout.LayoutParams(-1, dp(46)))

            val status = label("Settings are saved for Promax Voice Studio.", 11f, 0xFF63E0C1.toInt())
            voiceLayout.addView(status)
            voiceLayout.addView(label(
                "Android does not let a normal floating app replace another game's or call app's microphone input. These controls configure Promax Voice Studio recording and local playback; they do not change your voice inside another app.",
                10f, 0xFFFFC27A.toInt()
            ))
            voiceLayout.addView(Button(this).apply {
                text = "Open Voice Studio"
                setOnClickListener {
                    VoiceControlStore.save(this@GameAssistantService, settings)
                    val intent = Intent(this@GameAssistantService, MainActivity::class.java).apply {
                        putExtra(MainActivity.EXTRA_OPEN_VOICE_STUDIO, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    startActivity(intent)
                }
            }, LinearLayout.LayoutParams(-1, dp(48)))

            refreshControls()
        }

        fun addBrowser() {
            content.removeAllViews()
            metricsView = null
            val browserLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val addressRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val address = EditText(this).apply {
                textSize = 12f
                setSingleLine(true)
                hint = "https://example.com"
                setText("https://")
                setTextColor(Color.WHITE)
            }
            val go = Button(this).apply { text = "Go" }
            addressRow.addView(address, LinearLayout.LayoutParams(0, dp(52), 1f))
            addressRow.addView(go, LinearLayout.LayoutParams(dp(68), dp(52)))
            browserLayout.addView(addressRow)

            val web = WebView(this).apply {
                settings.javaScriptEnabled = false
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.loadsImagesAutomatically = true
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                if (Build.VERSION.SDK_INT >= 26) settings.safeBrowsingEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val scheme = request.url.scheme?.lowercase()
                        return scheme != "https"
                    }

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                        return !url.startsWith("https://", ignoreCase = true)
                    }
                }
            }
            browser = web
            go.setOnClickListener {
                var url = address.text.toString().trim()
                if (url.isNotBlank() && !url.startsWith("https://", true)) {
                    url = "https://" + url.removePrefix("http://")
                }
                val uri = runCatching { Uri.parse(url) }.getOrNull()
                if (uri?.scheme == "https" && !uri.host.isNullOrBlank()) {
                    address.setText(url)
                    web.loadUrl(url)
                } else {
                    address.error = "HTTPS address required"
                }
            }
            browserLayout.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
            content.addView(browserLayout)
        }

        toolsTab.setOnClickListener {
            toolsTab.isSelected = true
            voiceTab.isSelected = false
            browserTab.isSelected = false
            addTools()
        }
        voiceTab.setOnClickListener {
            toolsTab.isSelected = false
            voiceTab.isSelected = true
            browserTab.isSelected = false
            addVoice()
        }
        browserTab.setOnClickListener {
            toolsTab.isSelected = false
            voiceTab.isSelected = false
            browserTab.isSelected = true
            addBrowser()
        }
        addTools()

        val layout = WindowManager.LayoutParams(
            minOf((resources.displayMetrics.widthPixels * 0.88f).toInt(), dp(420)),
            (resources.displayMetrics.heightPixels * 0.78f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = dp(8)
            y = 0
        }

        try {
            windowManager?.addView(root, layout)
            panel = root
            panelParams = layout
            root.translationX = layout.width.toFloat()
            root.animate().translationX(0f).setDuration(180).start()
        } catch (_: Exception) {
            panel = null
            panelParams = null
        }

        // Swipe right anywhere on the header to close the side panel.
        header.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (event.rawX - downX > dp(60)) removePanel()
                        return true
                    }
                }
                return true
            }
        })
    }

    private fun refreshMetrics() {
        val metrics = metricsReader.read()
        val cpu = metrics.cpuPercent?.let { String.format("%.0f%%", it) } ?: "sampling"
        val gpu = metrics.gpuPercent?.let { String.format("%.0f%%", it) } ?: "Shizuku provider needed"
        val ram = metrics.ramUsedMb.toString() + " / " + metrics.ramTotalMb + " MB"
        metricsView?.text = "CPU (system): " + cpu +
            "\nRAM (system): " + ram +
            "\nGPU: " + gpu +
            "\nFPS: open Promax Game Monitor"
    }

    private fun removePanel() {
        val current = panel ?: return
        browser?.stopLoading()
        browser?.destroy()
        browser = null
        try {
            current.animate().translationX(current.width.toFloat()).setDuration(120).withEndAction {
                try { windowManager?.removeView(current) } catch (_: Exception) {}
            }.start()
        } catch (_: Exception) {
            try { windowManager?.removeView(current) } catch (_: Exception) {}
        }
        panel = null
        panelParams = null
        metricsView = null
    }

    private fun removeHandle() {
        val current = edgeHandle ?: return
        try { windowManager?.removeView(current) } catch (_: Exception) {}
        edgeHandle = null
        handleParams = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Game Assistant",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Visible while the user-started game overlay is active" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Promax Game Assistant active")
            .setContentText("Swipe in from the screen edge while a selected game is open.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollTask)
        removePanel()
        removeHandle()
        val prefs = getSharedPreferences("promax_settings", MODE_PRIVATE)
        if (prefs.getBoolean("silence_calls", false)) {
            DndController.silenceCalls(this, false)
        }
        prefs.edit()
            .putBoolean("silence_calls", false)
            .putBoolean("block_notifications", false)
            .apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
