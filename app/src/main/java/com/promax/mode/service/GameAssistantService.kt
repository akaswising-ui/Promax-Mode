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
    private var activeGameLabel = "GAME"
    private var cpuMetricView: TextView? = null
    private var gpuMetricView: TextView? = null
    private var ramMetricView: TextView? = null

    private val pollTask = object : Runnable {
        override fun run() {
            val games = GameAppRepository.savedPackages(this@GameAssistantService)
            val foreground = if (PermissionTools.hasUsageAccess(this@GameAssistantService)) {
                ForegroundGameDetector.currentPackage(this@GameAssistantService)
            } else null

            if (foreground != null && games.contains(foreground) &&
                PermissionTools.hasOverlay(this@GameAssistantService)
            ) {
                activeGameLabel = runCatching {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(foreground, 0)).toString().uppercase()
                }.getOrDefault(foreground.substringAfterLast(".").uppercase())
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
            text = "GAME ASSISTANT  |  " + activeGameLabel
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(4), 0, dp(4), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        fun headerAction(symbol: String, description: String): TextView =
            TextView(this).apply {
                text = symbol
                contentDescription = description
                gravity = Gravity.CENTER
                textSize = 19f
                setTextColor(0xFFE5EDF8.toInt())
                background = GradientDrawable().apply {
                    setColor(0xFF1D2A3A.toInt())
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), 0xFF3B5876.toInt())
                }
                isClickable = true
                isFocusable = true
                setPadding(dp(7), 0, dp(7), 0)
            }
        val toolsTab = headerAction("⚙", "Settings")
        val voiceTab = headerAction("🎙", "Voice controls")
        val browserTab = headerAction("🌐", "Browser")
        val close = headerAction("×", "Close floating window")
        header.addView(title, LinearLayout.LayoutParams(0, dp(44), 1f))
        listOf(toolsTab, voiceTab, browserTab, close).forEach { action ->
            header.addView(action, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        root.addView(header)

        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        fun actionButton(title: String, onClick: () -> Unit): TextView =
            TextView(this).apply {
                text = title
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(5), dp(6), dp(5), dp(6))
                background = GradientDrawable().apply {
                    setColor(0xFF203349.toInt())
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), 0xFF3A5875.toInt())
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    animate().scaleX(0.96f).scaleY(0.96f).setDuration(65).withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(95).start()
                        onClick()
                    }.start()
                }
            }

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
            cpuMetricView = null
            gpuMetricView = null
            ramMetricView = null
            content.removeAllViews()

            var settings = VoiceControlStore.load(this@GameAssistantService)
            var refreshControls: (() -> Unit)? = null
            val scroll = ScrollView(this).apply { isFillViewport = true }
            val voiceLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(7), dp(4), dp(10))
            }
            scroll.addView(voiceLayout)
            content.addView(scroll)

            fun label(value: String, size: Float = 12f, color: Int = 0xFFE5EDF8.toInt()): TextView =
                TextView(this).apply {
                    text = value
                    textSize = size
                    setTextColor(color)
                    setPadding(dp(2), dp(3), dp(2), dp(3))
                }

            fun metricCard(caption: String, value: TextView): LinearLayout {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(7), dp(4), dp(7))
                    background = GradientDrawable().apply {
                        setColor(0xFF172536.toInt())
                        cornerRadius = dp(13).toFloat()
                        setStroke(dp(1), 0xFF34506B.toInt())
                    }
                }
                card.addView(label(caption, 10f, 0xFF9FC8FF.toInt()))
                card.addView(value)
                return card
            }

            val metricsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            fun metricValue(initial: String): TextView = label(initial, 17f, Color.WHITE).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
            }
            cpuMetricView = metricValue("…")
            gpuMetricView = metricValue("…")
            ramMetricView = metricValue("…")
            listOf(
                metricCard("CPU", cpuMetricView!!),
                metricCard("GPU", gpuMetricView!!),
                metricCard("RAM", ramMetricView!!)
            ).forEach { card ->
                metricsRow.addView(card, LinearLayout.LayoutParams(0, dp(66), 1f).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(7))
                })
            }
            voiceLayout.addView(metricsRow)

            val voiceCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(9))
                background = GradientDrawable().apply {
                    setColor(0xF21A293A.toInt())
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), 0xFF405F7D.toInt())
                }
            }
            voiceLayout.addView(voiceCard)

            val voiceHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            voiceHeader.addView(label("🎙", 18f, 0xFF6EC9FF.toInt()))
            voiceHeader.addView(label("VOICE CHANGER", 14f, 0xFF9FD9FF.toInt()),
                LinearLayout.LayoutParams(0, dp(42), 1f))
            val enableSwitch = Switch(this).apply {
                text = "Enable"
                textSize = 11f
                setTextColor(Color.WHITE)
                isChecked = settings.enabled
            }
            voiceHeader.addView(enableSwitch)
            voiceCard.addView(voiceHeader)
            voiceCard.addView(label("Voice effects and tuning for Promax Voice Studio", 10f, 0xFF9DAEC3.toInt()))

            val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            voiceCard.addView(controls)
            controls.addView(label("VOICE PRESETS", 11f, 0xFF9FC8FF.toInt()))

            val presetButtons = linkedMapOf<VoiceEffect, TextView>()
            val presets = listOf(
                VoiceEffect.ROBOT, VoiceEffect.CHILD, VoiceEffect.ALIEN,
                VoiceEffect.MONSTER, VoiceEffect.HELIUM, VoiceEffect.DEMON,
                VoiceEffect.ECHO, VoiceEffect.OLD_MAN, VoiceEffect.UNDERWATER,
                VoiceEffect.CLEAN
            )
            fun effectIcon(effect: VoiceEffect): String = when (effect) {
                VoiceEffect.ROBOT -> "🤖"
                VoiceEffect.CHILD -> "🧒"
                VoiceEffect.ALIEN -> "👽"
                VoiceEffect.MONSTER -> "👹"
                VoiceEffect.HELIUM -> "🎈"
                VoiceEffect.DEMON -> "😈"
                VoiceEffect.ECHO -> "🔊"
                VoiceEffect.OLD_MAN -> "👴"
                VoiceEffect.UNDERWATER -> "🌊"
                VoiceEffect.CLEAN -> "🎙"
            }
            presets.chunked(3).forEach { rowEffects ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                rowEffects.forEach { effect ->
                    val tile = TextView(this).apply {
                        text = effectIcon(effect) + "\n" + effect.title.uppercase()
                        textSize = 10f
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                        setPadding(dp(2), dp(5), dp(2), dp(5))
                        background = GradientDrawable().apply {
                            setColor(0xFF24364A.toInt())
                            cornerRadius = dp(12).toFloat()
                            setStroke(dp(1), 0xFF344A62.toInt())
                        }
                        isClickable = true
                        isFocusable = true
                    }
                    presetButtons[effect] = tile
                    tile.setOnClickListener {
                        if (!settings.enabled) return@setOnClickListener
                        tile.animate().scaleX(0.94f).scaleY(0.94f).setDuration(75).withEndAction {
                            tile.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                        }.start()
                        settings = VoiceControlStore.withPreset(settings, effect)
                        VoiceControlStore.save(this@GameAssistantService, settings)
                        refreshControls?.invoke()
                    }
                    row.addView(tile, LinearLayout.LayoutParams(0, dp(57), 1f).apply {
                        setMargins(dp(2), dp(3), dp(2), dp(3))
                    })
                }
                repeat(3 - rowEffects.size) {
                    if (rowEffects.size < 3) row.addView(View(this), LinearLayout.LayoutParams(0, dp(57), 1f))
                }
                controls.addView(row)
            }

            fun addSlider(
                title: String,
                minimum: Float,
                maximum: Float,
                initial: Float,
                format: (Float) -> String,
                onChanged: (Float) -> Unit
            ): SeekBar {
                val valueLabel = label(title + ": " + format(initial), 11f, Color.WHITE)
                controls.addView(valueLabel)
                val bar = SeekBar(this).apply {
                    max = 200
                    progress = (((initial - minimum) / (maximum - minimum)) * 200f)
                        .toInt().coerceIn(0, 200)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            val value = minimum + (maximum - minimum) * progress / 200f
                            valueLabel.text = title + ": " + format(value)
                            if (fromUser && settings.enabled) onChanged(value)
                        }
                        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                    })
                }
                controls.addView(bar, LinearLayout.LayoutParams(-1, dp(38)))
                return bar
            }

            val pitchControl = addSlider("PITCH (±12 semitones)", -12f, 12f, settings.pitchSemitones,
                { String.format("%.1f", it) }) { value ->
                settings = settings.copy(pitchSemitones = value)
                VoiceControlStore.save(this@GameAssistantService, settings)
            }
            val formantControl = addSlider("FORMANT", -8f, 8f, settings.formantSemitones,
                { String.format("%.1f semitones", it) }) { value ->
                settings = settings.copy(formantSemitones = value)
                VoiceControlStore.save(this@GameAssistantService, settings)
            }
            val gateControl = addSlider("NOISE GATE", -80f, -20f, settings.noiseGateDb,
                { if (it <= -79.5f) "OFF" else String.format("%.0f dB", it) }) { value ->
                settings = settings.copy(noiseGateDb = value)
                VoiceControlStore.save(this@GameAssistantService, settings)
            }

            val suppression = Switch(this).apply {
                text = "Device noise suppression"
                textSize = 11f
                setTextColor(Color.WHITE)
                isChecked = settings.systemNoiseSuppression
                setOnCheckedChangeListener { _, checked ->
                    settings = settings.copy(systemNoiseSuppression = checked)
                    VoiceControlStore.save(this@GameAssistantService, settings)
                }
            }
            controls.addView(suppression, LinearLayout.LayoutParams(-1, dp(42)))

            fun setDescendantsEnabled(view: View, enabled: Boolean) {
                view.isEnabled = enabled
                if (view is android.view.ViewGroup) {
                    for (index in 0 until view.childCount) setDescendantsEnabled(view.getChildAt(index), enabled)
                }
            }
            refreshControls = {
                pitchControl.progress = (((settings.pitchSemitones + 12f) / 24f) * 200f).toInt().coerceIn(0, 200)
                formantControl.progress = (((settings.formantSemitones + 8f) / 16f) * 200f).toInt().coerceIn(0, 200)
                gateControl.progress = (((settings.noiseGateDb + 80f) / 60f) * 200f).toInt().coerceIn(0, 200)
                suppression.isChecked = settings.systemNoiseSuppression
                presetButtons.forEach { (effect, tile) ->
                    tile.background = GradientDrawable().apply {
                        setColor(if (settings.effect == effect) 0xFF174D70.toInt() else 0xFF24364A.toInt())
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(1), if (settings.effect == effect) 0xFF62C7FF.toInt() else 0xFF344A62.toInt())
                    }
                }
                controls.alpha = if (settings.enabled) 1f else 0.48f
                setDescendantsEnabled(controls, settings.enabled)
                enableSwitch.isChecked = settings.enabled
            }
            enableSwitch.setOnCheckedChangeListener { _, checked ->
                settings = settings.copy(enabled = checked)
                VoiceControlStore.save(this@GameAssistantService, settings)
                refreshControls?.invoke()
            }

            voiceCard.addView(label("SAVED VOICE PROFILES", 11f, 0xFF9FC8FF.toInt()))
            val profileContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            voiceCard.addView(profileContainer)
            fun renderProfiles() {
                profileContainer.removeAllViews()
                val profiles = VoiceProfileStore.list(this@GameAssistantService)
                if (profiles.isEmpty()) {
                    profileContainer.addView(label("No saved profiles yet.", 10f, 0xFF9DAEC3.toInt()))
                    return
                }
                profiles.take(6).chunked(2).forEach { group ->
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    group.forEach { profile ->
                        val item = TextView(this).apply {
                            text = profile.name
                            textSize = 10f
                            gravity = Gravity.CENTER
                            setTextColor(Color.WHITE)
                            background = GradientDrawable().apply {
                                setColor(0xFF24364A.toInt())
                                cornerRadius = dp(10).toFloat()
                                setStroke(dp(1), 0xFF344A62.toInt())
                            }
                            setPadding(dp(4), dp(7), dp(4), dp(7))
                            setOnClickListener {
                                settings = VoiceControlSettings(
                                    effect = profile.effect,
                                    pitchSemitones = profile.pitchSemitones,
                                    formantSemitones = profile.formantSemitones,
                                    noiseGateDb = profile.noiseGateDb,
                                    systemNoiseSuppression = profile.systemNoiseSuppression,
                                    enabled = settings.enabled
                                )
                                VoiceControlStore.save(this@GameAssistantService, settings)
                                refreshControls?.invoke()
                            }
                        }
                        row.addView(item, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                            setMargins(dp(2), dp(2), dp(2), dp(2))
                        })
                    }
                    profileContainer.addView(row)
                }
            }
            renderProfiles()
            val profileName = EditText(this).apply {
                setSingleLine(true)
                textSize = 11f
                hint = "New profile name"
                setText("Floating voice")
                setTextColor(Color.WHITE)
            }
            voiceCard.addView(profileName, LinearLayout.LayoutParams(-1, dp(42)))
            voiceCard.addView(actionButton("SAVE CURRENT PROFILE") {
                val name = profileName.text.toString().trim()
                if (name.isBlank()) profileName.error = "Enter a profile name" else {
                    VoiceProfileStore.save(this@GameAssistantService, VoiceProfile(
                        name, settings.effect, settings.pitchSemitones, settings.formantSemitones,
                        settings.noiseGateDb, settings.systemNoiseSuppression
                    ))
                    renderProfiles()
                    Toast.makeText(this@GameAssistantService, "Voice profile saved", Toast.LENGTH_SHORT).show()
                }
            })

            voiceLayout.addView(label("QUICK TOOLS", 11f, 0xFF9FC8FF.toInt()))
            val actionRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun actionRow(first: Pair<String, () -> Unit>, second: Pair<String, () -> Unit>) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(actionButton(first.first, first.second), LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                    setMargins(dp(2), dp(3), dp(2), dp(3))
                })
                row.addView(actionButton(second.first, second.second), LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                    setMargins(dp(2), dp(3), dp(2), dp(3))
                })
                actionRows.addView(row)
            }
            val prefs = getSharedPreferences("promax_settings", MODE_PRIVATE)
            actionRow("📞  BLOCK CALLS" to {
                if (!PermissionTools.hasNotificationPolicyAccess(this@GameAssistantService)) {
                    PermissionTools.openNotificationPolicySettings(this@GameAssistantService)
                } else {
                    val next = !prefs.getBoolean("silence_calls", false)
                    if (DndController.silenceCalls(this@GameAssistantService, next)) {
                        prefs.edit().putBoolean("silence_calls", next).apply()
                    }
                }
            }, "🔕  BLOCK NOTIFS" to {
                if (!PermissionTools.hasNotificationListenerAccess(this@GameAssistantService)) {
                    PermissionTools.openNotificationListenerSettings(this@GameAssistantService)
                } else {
                    val next = !prefs.getBoolean("block_notifications", false)
                    prefs.edit().putBoolean("block_notifications", next).apply()
                }
            })
            actionRow("🧹  CLEAR RAM" to {
                cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
                Toast.makeText(this@GameAssistantService,
                    "Promax temporary cache cleared. Android manages other apps' RAM.", Toast.LENGTH_LONG).show()
            }, "⚙  SETTINGS" to { addTools() })
            voiceLayout.addView(actionRows)

            voiceLayout.addView(label(
                "Android does not let a normal app replace another game's microphone input. These controls apply to Promax Voice Studio recordings and local monitoring.",
                10f, 0xFFFFC27A.toInt()
            ))
            voiceLayout.addView(actionButton("OPEN VOICE STUDIO") {
                val intent = Intent(this@GameAssistantService, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_OPEN_VOICE_STUDIO, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
            }, LinearLayout.LayoutParams(-1, dp(46)))

            refreshControls?.invoke()
            refreshMetrics()
            scroll.alpha = 0f
            scroll.translationY = dp(12).toFloat()
            scroll.animate().alpha(1f).translationY(0f).setDuration(220).start()
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

        toolsTab.setOnClickListener { addTools() }
        voiceTab.setOnClickListener { addVoice() }
        browserTab.setOnClickListener { addBrowser() }
        close.setOnClickListener { removePanel() }
        addVoice()

        val layout = WindowManager.LayoutParams(
            minOf((resources.displayMetrics.widthPixels * 0.84f).toInt(), dp(380)),
            (resources.displayMetrics.heightPixels * 0.84f).toInt(),
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
        cpuMetricView = null
        gpuMetricView = null
        ramMetricView = null
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
