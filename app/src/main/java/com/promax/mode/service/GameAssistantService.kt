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
        fun surface(color: Int, stroke: Int = 0xFF34485F.toInt(), radius: Int = 14): GradientDrawable =
            GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radius).toFloat()
                setStroke(dp(1), stroke)
            }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = surface(0xF20D1521.toInt(), 0xFF526984.toInt(), 20)
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
                background = surface(0xFF1D2A3A.toInt(), 0xFF3B5876.toInt(), 12)
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
                    background = surface(0xFF172536.toInt(), 0xFF34506B.toInt(), 13)
                }
                card.addView(label(caption, 10f, 0xFF9FC8FF.toInt()))
                card.addView(value)
                return card
            }

            val metricsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            fun metricValue(initial: String): TextView = label(initial, 19f, Color.WHITE).apply {
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
                background = surface(0xF21A293A.toInt(), 0xFF405F7D.toInt(), 16)
            }
            voiceLayout.addView(voiceCard)

            val voiceHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val voiceIcon = TextView(this).apply {
                text = "🎙"
                textSize = 18f
                setTextColor(0xFF6EC9FF.toInt())
                setPadding(dp(2), 0, dp(5), 0)
            }
            voiceHeader.addView(voiceIcon)
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

            voiceCard.addView(label(
                "Voice effects and tuning for Promax Voice Studio",
                10f, 0xFF9DAEC3.toInt()
            ))

            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            voiceCard.addView(controls)

            val presetTitle = label("VOICE PRESETS", 11f, 0xFF9FC8FF.toInt())
            controls.addView(presetTitle)
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
                        background = surface(0xFF24364A.toInt(), 0xFF344A62.toInt(), 12)
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
                        refreshControls()
                    }
                    row.addView(tile, LinearLayout.LayoutParams(0, dp(57), 1f).apply {
                        setMargins(dp(2), dp(3), dp(2), dp(3))
                    })
                }
                if (rowEffects.size < 3) {
                    repeat(3 - rowEffects.size) {
                        row.addView(View(this), LinearLayout.LayoutParams(0, dp(57), 1f))
                    }
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
            ): Pair<SeekBar, TextView> {
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
                return bar to valueLabel
            }

            val pitchControl = addSlider(
                "PITCH (±12 semitones)", -12f, 12f, settings.pitchSemitones,
                { String.format("%.1f", it) }
            ) { value ->
                settings = settings.copy(pitchSemitones = value)
                VoiceControlStore.save(this@GameAssistantService, settings)
            }
            val formantControl = addSlider(
                "FORMANT", -8f, 8f, settings.formantSemitones,
                { String.format("%.1f semitones", it) }
            ) { value ->
                settings = settings.copy(formantSemitones = value)
                VoiceControlStore.save(this@GameAssistantService, settings)
            }
            val gateControl = addSlider(
                "NOISE GATE", -80f, -20f, settings.noiseGateDb,
                { if (it <= -79.5f) "OFF" else String.format("%.0f dB", it) }
            ) { value ->
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

            fun setControlsEnabled(view: View, enabled: Boolean) {
                view.isEnabled = enabled
                if (view is android.view.ViewGroup) {
                    for (index in 0 until view.childCount) {
                        setControlsEnabled(view.getChildAt(index), enabled)
                    }
                }
            }

            fun refreshControls() {
                pitchControl.first.progress =
                    (((settings.pitchSemitones + 12f) / 24f) * 200f).toInt().coerceIn(0, 200)
                formantControl.first.progress =
                    (((settings.formantSemitones + 8f) / 16f) * 200f).toInt().coerceIn(0, 200)
                gateControl.first.progress =
                    (((settings.noiseGateDb + 80f) / 60f) * 200f).toInt().coerceIn(0, 200)
                suppression.isChecked = settings.systemNoiseSuppression
                presetButtons.forEach { (effect, tile) ->
                    tile.background = surface(
                        if (settings.effect == effect) 0xFF174D70.toInt() else 0xFF24364A.toInt(),
                        if (settings.effect == effect) 0xFF62C7FF.toInt() else 0xFF344A62.toInt(),
                        12
                    )
                }
                controls.alpha = if (settings.enabled) 1f else 0.48f
                setControlsEnabled(controls, settings.enabled)
                enableSwitch.isChecked = settings.enabled
            }
            enableSwitch.setOnCheckedChangeListener { _, checked ->
                settings = settings.copy(enabled = checked)
                VoiceControlStore.save(this@GameAssistantService, settings)
                refreshControls()
                if (checked) {
                    Toast.makeText(this@GameAssistantService, "Voice effects enabled for Voice Studio", Toast.LENGTH_SHORT).show()
                }
            }

            voiceCard.addView(label("SAVED VOICE PROFILES", 11f, 0xFF9FC8FF.toInt()))
            val profileContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
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
                            background = surface(0xFF24364A.toInt(), 0xFF344A62.toInt(), 10)
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
                                refreshControls()
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
                if (name.isBlank()) {
                    profileName.error = "Enter a profile name"
                } else {
                    VoiceProfileStore.save(
                        this@GameAssistantService,
                        VoiceProfile(
                            name, settings.effect, settings.pitchSemitones,
                            settings.formantSemitones, settings.noiseGateDb,
                            settings.systemNoiseSuppression
                        )
                    )
                    renderProfiles()
                    Toast.makeText(this@GameAssistantService, "Voice profile saved", Toast.LENGTH_SHORT).show()
                }
            })

            voiceLayout.addView(label(
                "QUICK TOOLS", 11f, 0xFF9FC8FF.toInt()
            ))
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
                        Toast.makeText(this@GameAssistantService, if (next) "Call alerts silenced" else "Call alerts restored", Toast.LENGTH_SHORT).show()
                    }
                }
            }, "🔕  BLOCK NOTIFS" to {
                if (!PermissionTools.hasNotificationListenerAccess(this@GameAssistantService)) {
                    PermissionTools.openNotificationListenerSettings(this@GameAssistantService)
                } else {
                    val next = !prefs.getBoolean("block_notifications", false)
                    prefs.edit().putBoolean("block_notifications", next).apply()
                    Toast.makeText(this@GameAssistantService, if (next) "Notification blocking enabled" else "Notification blocking disabled", Toast.LENGTH_SHORT).show()
                }
            })
            actionRow("🧹  CLEAR RAM" to {
                cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
                Toast.makeText(this@GameAssistantService, "Promax temporary cache cleared. Android manages other apps' RAM.", Toast.LENGTH_LONG).show()
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

            refreshControls()
            refreshMetrics()
            scroll.alpha = 0f
            scroll.translationY = dp(12).toFloat()
            scroll.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }

        fun actionButton(title: String, onClick: () -> Unit): TextView =
            TextView(this).apply {
                text = title
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(5), dp(6), dp(5), dp(6))
                background = surface(0xFF203349.toInt(), 0xFF3A5875.toInt(), 12)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    animate().scaleX(0.96f).scaleY(0.96f).setDuration(65).withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(95).start()
                        onClick()
                    }.start()
                }
            }


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
