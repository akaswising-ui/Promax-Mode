package com.promax.mode.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.role.RoleManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.SystemClock
import android.os.BatteryManager
import android.net.TrafficStats
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
import android.widget.HorizontalScrollView
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
import com.promax.mode.shizuku.ShizukuController

private class RingGaugeView(context: android.content.Context, private val caption: String) : View(context) {
    private val density = resources.displayMetrics.density
    private var percentage: Float? = null
    private var valueText: String = "--"
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        color = 0xFF344A5D.toInt()
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        color = 0xFF26D18B.toInt()
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10f * density
        color = 0xFFE5EDF8.toInt()
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10f * density
        color = 0xFFD7E3ED.toInt()
    }

    fun setMetric(progress: Float?, displayed: String) {
        percentage = progress?.coerceIn(0f, 100f)
        valueText = displayed
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = 30f * density
        val radius = 22f * density
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, 135f, 270f, false, trackPaint)
        val percent = percentage
        if (percent != null) {
            canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, 135f, 270f * percent / 100f, false, activePaint)
        }
        canvas.drawText(caption, cx, cy + 3.5f * density, titlePaint)
        canvas.drawText(valueText, cx, 76f * density, valuePaint)
    }
}

class GameAssistantService : Service() {
    companion object {
        const val ACTION_STOP = "com.promax.mode.action.STOP_OVERLAY"
        const val ACTION_SET_EYE_FILTER = "com.promax.mode.action.SET_EYE_FILTER"
        const val EXTRA_EYE_FILTER_ENABLED = "com.promax.mode.extra.EYE_FILTER_ENABLED"
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
    private var cpuGaugeView: RingGaugeView? = null
    private var gpuGaugeView: RingGaugeView? = null
    private var ramGaugeView: RingGaugeView? = null
    private var eyeFilterView: View? = null
    private var activeGamePackage: String = ""
    private var topStatsView: TextView? = null
    private var overlayFpsStatus: String = "--"
    private var overlayGpuStatus: String = "N/A"
    private var lastNetworkRx: Long = -1L
    private var lastNetworkTx: Long = -1L
    private var lastNetworkSampleMs: Long = -1L

    private val pollTask = object : Runnable {
        override fun run() {
            val games = GameAppRepository.savedPackages(this@GameAssistantService)
            val foreground = if (PermissionTools.hasUsageAccess(this@GameAssistantService)) {
                ForegroundGameDetector.currentPackage(this@GameAssistantService)
            } else null

            if (foreground != null && games.contains(foreground) &&
                PermissionTools.hasOverlay(this@GameAssistantService)
            ) {
                activeGamePackage = foreground
                activeGameLabel = runCatching {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(foreground, 0)).toString().uppercase()
                }.getOrDefault(foreground.substringAfterLast(".").uppercase())
                showEdgeHandle()
                val savedFilter = getSharedPreferences("promax_settings", MODE_PRIVATE)
                    .getString("game_filter", "OFF") ?: "OFF"
                if (savedFilter != "OFF") {
                    applyGameFilter(savedFilter, false)
                } else {
                    removeEyeFilter()
                }
                refreshMetrics()
            } else {
                removeEyeFilter()
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
        if (intent?.action == ACTION_SET_EYE_FILTER) {
            val enabled = intent.getBooleanExtra(EXTRA_EYE_FILTER_ENABLED, false)
            getSharedPreferences("promax_settings", MODE_PRIVATE)
                .edit().putBoolean("eye_filter_enabled", enabled).apply()
            if (enabled) applyEyeFilter() else removeEyeFilter()
        }
        handler.removeCallbacks(pollTask)
        handler.post(pollTask)
        return START_STICKY
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun monitorInterval(): Long {
        val profile = getSharedPreferences("promax_settings", MODE_PRIVATE)
            .getString("performance_profile", null)
        return when (profile) {
            "LOW POWER" -> 4_000L
            "PRO GAMER" -> 1_000L
            else -> 2_000L
        }
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

        fun panelSurface(fill: Int, stroke: Int = 0xFF3B536C.toInt(), radiusDp: Int = 16): GradientDrawable =
            GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dp(radiusDp).toFloat()
                setStroke(dp(1), stroke)
            }

        fun label(textValue: String, sizeSp: Float = 12f, color: Int = 0xFFE5EDF8.toInt()): TextView =
            TextView(this).apply {
                text = textValue
                textSize = sizeSp
                setTextColor(color)
                gravity = Gravity.CENTER_VERTICAL
            }

        fun actionTile(textValue: String, onTap: () -> Unit): TextView =
            TextView(this).apply {
                text = textValue
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(0xFFE5EDF8.toInt())
                setPadding(dp(4), dp(5), dp(4), dp(5))
                background = panelSurface(0xFF1B2A3B.toInt(), 0xFF344A61.toInt(), 12)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    animate().scaleX(0.96f).scaleY(0.96f).setDuration(65).withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(95).start()
                        onTap()
                    }.start()
                }
            }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(8), dp(9), dp(9))
            background = panelSurface(0xF20D1521.toInt(), 0xFF526984.toInt(), 20)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerTitle = label("GAME ASSISTANT", 13f, 0xFF72C9FF.toInt())
        headerTitle.setTypeface(null, android.graphics.Typeface.BOLD)
        val gameName = label(activeGameLabel, 10f, 0xFFB8C5D3.toInt())
        gameName.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        val close = label("×", 23f, 0xFFE7F1FA.toInt()).apply {
            gravity = Gravity.CENTER
            background = panelSurface(0xFF1A2939.toInt(), 0xFF354E67.toInt(), 10)
            isClickable = true
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { removePanel() }
        }
        header.addView(headerTitle, LinearLayout.LayoutParams(0, dp(38), 1f))
        header.addView(gameName, LinearLayout.LayoutParams(0, dp(38), 1f))
        header.addView(close, LinearLayout.LayoutParams(dp(36), dp(36)))
        root.addView(header)

        val metricsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = panelSurface(0xFF202732.toInt(), 0xFF36424E.toInt(), 14)
        }
        val statsHeading = label("LIVE PERFORMANCE", 9f, 0xFF9BCFC3.toInt())
        statsHeading.setTypeface(null, android.graphics.Typeface.BOLD)
        metricsCard.addView(statsHeading)
        topStatsView = label("FPS: --    CPU: --    GPU: --\nRAM: --    TEMP: --    NET: --", 10f, 0xFFE4EBF0.toInt())
        topStatsView?.setPadding(0, dp(5), 0, 0)
        metricsCard.addView(topStatsView)
        root.addView(metricsCard, LinearLayout.LayoutParams(-1, dp(76)).apply {
            topMargin = dp(5)
            bottomMargin = dp(5)
        })

        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        var showVoicePanel: (() -> Unit)? = null
        var showToolsPanel: (() -> Unit)? = null
        var showBrowserPanel: (() -> Unit)? = null

        val prefs = getSharedPreferences("promax_settings", MODE_PRIVATE)
        val quickTools = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(5), 0, dp(4))
        }
        val quickToolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun quickShortcut(icon: String, title: String, onTap: () -> Unit) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(5), dp(3), dp(5), dp(3))
                setOnClickListener {
                    animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                        onTap()
                    }.start()
                }
            }
            val circle = TextView(this).apply {
                text = icon
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(0xFFBDF4E7.toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF183B3A.toInt())
                    setStroke(dp(1), 0xFF397A71.toInt())
                }
            }
            item.addView(circle, LinearLayout.LayoutParams(dp(42), dp(42)))
            item.addView(TextView(this).apply {
                text = title
                textSize = 8.5f
                gravity = Gravity.CENTER
                setTextColor(0xFFDCE7E8.toInt())
                maxLines = 1
            }, LinearLayout.LayoutParams(dp(58), dp(20)))
            quickToolsRow.addView(item, LinearLayout.LayoutParams(dp(62), dp(66)))
        }
        quickShortcut("🎙", "VOICE") { showVoicePanel?.invoke() }
        quickShortcut("📸", "SHOT") {
            Thread {
                val result = runCatching { ShizukuController.takeScreenshot() }
                    .getOrElse { "Connect Shizuku in Promax > Access first" }
                handler.post { Toast.makeText(this@GameAssistantService, result, Toast.LENGTH_LONG).show() }
            }.start()
        }
        quickShortcut("⏺", "RECORD") {
            Thread {
                val active = prefs.getBoolean("screen_recording_active", false)
                val result = runCatching {
                    if (active) ShizukuController.stopScreenRecording()
                    else ShizukuController.startScreenRecording()
                }.getOrElse { "Connect Shizuku in Promax > Access first" }
                val recording = result.startsWith("RECORDING:")
                prefs.edit().putBoolean("screen_recording_active", recording).apply()
                handler.post { Toast.makeText(this@GameAssistantService, result, Toast.LENGTH_LONG).show() }
            }.start()
        }
        quickShortcut("🔕", "SILENT") {
            if (!PermissionTools.hasNotificationPolicyAccess(this@GameAssistantService)) {
                PermissionTools.openNotificationPolicySettings(this@GameAssistantService)
            } else {
                val next = !prefs.getBoolean("silence_calls", false)
                if (DndController.silenceCalls(this@GameAssistantService, next)) {
                    prefs.edit().putBoolean("silence_calls", next).apply()
                    Toast.makeText(this@GameAssistantService, if (next) "Call alerts silenced" else "Call alerts restored", Toast.LENGTH_SHORT).show()
                }
            }
        }
        quickShortcut("◉", "NOTIFS") {
            if (!PermissionTools.hasNotificationListenerAccess(this@GameAssistantService)) {
                PermissionTools.openNotificationListenerSettings(this@GameAssistantService)
            } else {
                val next = !prefs.getBoolean("block_notifications", false)
                prefs.edit().putBoolean("block_notifications", next).apply()
                Toast.makeText(this@GameAssistantService, if (next) "Notification blocking on" else "Notification blocking off", Toast.LENGTH_SHORT).show()
            }
        }
        quickShortcut("☎", "CALLS") { toggleUnknownCallBlocking() }
        quickTools.addView(quickToolsRow)
        root.addView(quickTools, LinearLayout.LayoutParams(-1, dp(75)))

        fun addTools() {
            browser?.stopLoading()
            browser?.destroy()
            browser = null
            metricsView = null
            content.removeAllViews()
            val tools = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            tools.addView(label("DEVICE TOOLS", 13f, 0xFF8FD4FF.toInt()))
            val metrics = label("Reading device metrics...", 11f)
            metricsView = metrics
            tools.addView(metrics)
            val notifications = Button(this).apply {
                text = if (prefs.getBoolean("block_notifications", false)) "Notifications: ON" else "Block notifications"
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
            tools.addView(notifications, LinearLayout.LayoutParams(-1, dp(44)))
            val calls = Button(this).apply {
                text = if (prefs.getBoolean("silence_calls", false)) "Call alerts: SILENCED" else "Silence call alerts (DND)"
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
            tools.addView(calls, LinearLayout.LayoutParams(-1, dp(44)))
            tools.addView(Button(this).apply {
                text = if (prefs.getBoolean("block_unknown_calls", false)) "Call blocking: ON" else "Block incoming calls"
                setOnClickListener {
                    toggleUnknownCallBlocking()
                    text = if (prefs.getBoolean("block_unknown_calls", false)) "Unknown-call blocking: ON" else "Block unknown incoming calls"
                }
            }, LinearLayout.LayoutParams(-1, dp(44)))
            val note = EditText(this).apply {
                textSize = 12f
                hint = "Quick game note"
                setSingleLine(false)
                minLines = 2
                maxLines = 4
                setText(prefs.getString("quick_note", ""))
            }
            tools.addView(note, LinearLayout.LayoutParams(-1, dp(82)))
            tools.addView(Button(this).apply {
                text = "Save note"
                setOnClickListener {
                    prefs.edit().putString("quick_note", note.text.toString()).apply()
                    text = "Saved"
                }
            }, LinearLayout.LayoutParams(-1, dp(42)))
            tools.addView(Button(this).apply {
                text = "Open floating browser"
                setOnClickListener { showBrowserPanel?.invoke() }
            }, LinearLayout.LayoutParams(-1, dp(42)))
            tools.addView(Button(this).apply {
                text = "Back to Game Assistant"
                setOnClickListener { showVoicePanel?.invoke() }
            }, LinearLayout.LayoutParams(-1, dp(42)))
            content.addView(ScrollView(this).apply { addView(tools) })
            refreshMetrics()
        }

        fun addGameHome() {
            browser?.stopLoading()
            browser?.destroy()
            browser = null
            content.removeAllViews()
            metricsView = null

            val homeScroll = ScrollView(this).apply { isFillViewport = true }
            val home = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(2), dp(3), dp(2), dp(4))
            }
            homeScroll.addView(home)

            val modeTitle = label("PERFORMANCE MODE", 11f, 0xFFB6D7D0.toInt())
            modeTitle.setTypeface(null, android.graphics.Typeface.BOLD)
            home.addView(modeTitle, LinearLayout.LayoutParams(-1, dp(30)))

            val currentProfile = prefs.getString("performance_profile", "BALANCED") ?: "BALANCED"
            val modeCards = linkedMapOf<String, TextView>()
            val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun setPerformanceProfile(profile: String, showToast: Boolean = true) {
                prefs.edit()
                    .putString("performance_profile", profile)
                    .putBoolean("high_monitor_rate", profile == "PRO GAMER")
                    .apply()
                modeCards.forEach { (name, view) ->
                    val selected = name == profile
                    view.background = panelSurface(
                        if (selected) 0xFF174C48.toInt() else 0xFF242A34.toInt(),
                        if (selected) 0xFF53C7B0.toInt() else 0xFF3B4653.toInt(),
                        12
                    )
                    view.setTextColor(if (selected) 0xFFBDF4E7.toInt() else 0xFFD5DDE5.toInt())
                }
                handler.removeCallbacks(pollTask)
                handler.post(pollTask)
                if (showToast) Toast.makeText(
                    this@GameAssistantService,
                    when (profile) {
                        "LOW POWER" -> "Low-power monitoring profile selected"
                        "PRO GAMER" -> "High-rate monitoring profile selected"
                        else -> "Balanced monitoring profile selected"
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
            listOf("LOW POWER", "BALANCED", "PRO GAMER").forEach { profile ->
                val tile = label(profile, 9f, 0xFFD5DDE5.toInt()).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(2), dp(3), dp(2), dp(3))
                    isClickable = true
                    isFocusable = true
                }
                modeCards[profile] = tile
                modeRow.addView(tile, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(4))
                })
                tile.setOnClickListener { setPerformanceProfile(profile) }
            }
            home.addView(modeRow)
            setPerformanceProfile(currentProfile, false)

            val metricsTitle = label("LIVE GAME STATUS", 11f, 0xFFB6D7D0.toInt()).apply {
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            home.addView(metricsTitle, LinearLayout.LayoutParams(-1, dp(30)))
            val liveMetrics = label("CPU: --     RAM: --\nGPU: driver dependent     FPS: start capture", 10f, 0xFFD9E2E8.toInt()).apply {
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = panelSurface(0xFF202832.toInt(), 0xFF37444E.toInt(), 12)
                gravity = Gravity.CENTER_VERTICAL
            }
            metricsView = liveMetrics
            home.addView(liveMetrics, LinearLayout.LayoutParams(-1, dp(58)).apply {
                bottomMargin = dp(6)
            })
            home.addView(label(
                "CPU / RAM are device-wide. GPU uses an exposed driver counter. FPS needs Shizuku and a readable game layer.",
                9f,
                0xFF9BAEB7.toInt()
            ), LinearLayout.LayoutParams(-1, dp(34)))

            val monitorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val startFps = actionTile("START FPS") {
                val pkg = activeGamePackage
                if (pkg.isBlank()) {
                    Toast.makeText(this@GameAssistantService, "Open a selected game first.", Toast.LENGTH_SHORT).show()
                } else {
                    liveMetrics.text = "FPS: capturing " + activeGameLabel + "…"
                    Thread {
                        val result = runCatching {
                            val started = ShizukuController.startFrameStats(pkg)
                            if (started != "CAPTURE_STARTED") started
                            else {
                                var measured = "FPS_UNAVAILABLE: no matching layer"
                                repeat(6) {
                                    Thread.sleep(850L)
                                    val read = ShizukuController.dumpFrameStats(pkg)
                                    if (read.startsWith("FPS=")) {
                                        measured = read
                                        return@runCatching measured
                                    }
                                    measured = read
                                }
                                measured
                            }
                        }.getOrElse { "FPS_UNAVAILABLE: connect Shizuku" }
                        handler.post {
                            overlayFpsStatus = result.removePrefix("FPS=").removePrefix("FPS_UNAVAILABLE:").trim().take(24)
                            refreshMetrics()
                            val current = liveMetrics.text.toString()
                            liveMetrics.text = current.substringBefore("\n") + "\n" + result
                        }
                    }.start()
                }
            }
            val sampleGpu = actionTile("SAMPLE GPU") {
                Thread {
                    val result = runCatching {
                        ShizukuController.getGpuUsage()
                        Thread.sleep(1_050L)
                        ShizukuController.getGpuUsage()
                    }.getOrElse { "GPU_UNAVAILABLE: connect Shizuku" }
                    handler.post {
                        overlayGpuStatus = result.removePrefix("GPU_DEVICE_WIDE=").removePrefix("GPU_UNAVAILABLE:").trim().take(24)
                        refreshMetrics()
                        Toast.makeText(this@GameAssistantService, result, Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            monitorRow.addView(startFps, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                setMargins(dp(2), dp(2), dp(3), dp(2))
            })
            monitorRow.addView(sampleGpu, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                setMargins(dp(3), dp(2), dp(2), dp(2))
            })
            home.addView(monitorRow)

            val toolsTitle = label("GAME TOOLS", 11f, 0xFFB6D7D0.toInt()).apply {
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            home.addView(toolsTitle, LinearLayout.LayoutParams(-1, dp(34)))

            fun toolRow(first: Pair<String, () -> Unit>, second: Pair<String, () -> Unit>) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(actionTile(first.first, first.second), LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                    setMargins(dp(2), dp(2), dp(3), dp(2))
                })
                row.addView(actionTile(second.first, second.second), LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                    setMargins(dp(3), dp(2), dp(2), dp(2))
                })
                home.addView(row)
            }
            toolRow("🎙\nVOICE CHANGER" to { showVoicePanel?.invoke() },
                "🎨\nGAME FILTERS" to {
                    val filters = arrayOf("Warm tint", "Cool tint", "Green tint", "Off")
                    android.app.AlertDialog.Builder(this@GameAssistantService)
                        .setTitle("Game display filters")
                        .setItems(filters) { _, which ->
                            val value = when (which) {
                                0 -> "WARM"
                                1 -> "COOL"
                                2 -> "GREEN"
                                else -> "OFF"
                            }
                            applyGameFilter(value)
                            Toast.makeText(this@GameAssistantService, if (value == "OFF") "Filter off" else value.lowercase() + " tint enabled", Toast.LENGTH_SHORT).show()
                        }
                        .show()
                })
            toolRow("☝\nTOUCH OPTIM." to {
                Toast.makeText(
                    this@GameAssistantService,
                    "Touch sampling is controlled by the phone manufacturer; Promax cannot change the OEM controller.",
                    Toast.LENGTH_LONG
                ).show()
            }, "🔔\nNOTIFICATIONS" to {
                if (!PermissionTools.hasNotificationListenerAccess(this@GameAssistantService)) {
                    PermissionTools.openNotificationListenerSettings(this@GameAssistantService)
                } else {
                    val next = !prefs.getBoolean("block_notifications", false)
                    prefs.edit().putBoolean("block_notifications", next).apply()
                    Toast.makeText(this@GameAssistantService, if (next) "Notifications blocked" else "Notifications allowed", Toast.LENGTH_SHORT).show()
                }
            })
            toolRow("☎\nCALL BLOCKER" to { toggleUnknownCallBlocking() },
                "⚙\nMORE SETTINGS" to { showToolsPanel?.invoke() })

            val limitation = label(
                "Performance profile changes Promax monitoring only. OEM CPU/GPU boost and touch response require supported system services.",
                9f,
                0xFF9BAEB7.toInt()
            )
            limitation.setPadding(dp(4), dp(8), dp(4), dp(5))
            home.addView(limitation)
            content.addView(homeScroll)
            refreshMetrics()
            homeScroll.alpha = 0f
            homeScroll.translationY = dp(8).toFloat()
            homeScroll.animate().alpha(1f).translationY(0f).setDuration(190).start()
        }

        fun addBrowser() {
            content.removeAllViews()
            metricsView = null
            val browserLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            browserLayout.addView(Button(this).apply {
                text = "Back to Game Assistant"
                setOnClickListener { showVoicePanel?.invoke() }
            }, LinearLayout.LayoutParams(-1, dp(42)))
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
            addressRow.addView(address, LinearLayout.LayoutParams(0, dp(48), 1f))
            addressRow.addView(go, LinearLayout.LayoutParams(dp(64), dp(48)))
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
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.scheme?.lowercase() != "https"

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                        !url.startsWith("https://", ignoreCase = true)
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

        fun addVoice() {
            browser?.stopLoading()
            browser?.destroy()
            browser = null
            metricsView = null
            content.removeAllViews()

            var settings = VoiceControlStore.load(this@GameAssistantService)
            var refreshVoice: (() -> Unit)? = null
            val scroll = ScrollView(this).apply { isFillViewport = true }
            val voiceCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(9), dp(8), dp(9), dp(9))
                background = panelSurface(0xFF1A293A.toInt(), 0xFF354E68.toInt(), 16)
            }
            scroll.addView(voiceCard)

            val voiceHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            voiceHeader.addView(label("🎙", 18f, 0xFF78CFFF.toInt()),
                LinearLayout.LayoutParams(dp(30), dp(40)))
            val voiceTitle = label("VOICE CHANGER", 14f, 0xFF92D4FF.toInt())
            voiceTitle.setTypeface(null, android.graphics.Typeface.BOLD)
            voiceHeader.addView(voiceTitle, LinearLayout.LayoutParams(0, dp(40), 1f))
            val enableText = label("Enable Voice", 10f, Color.WHITE)
            voiceHeader.addView(enableText)
            val enableSwitch = Switch(this).apply {
                isChecked = settings.enabled
                text = ""
                setPadding(0, 0, 0, 0)
            }
            voiceHeader.addView(enableSwitch, LinearLayout.LayoutParams(dp(48), dp(42)))
            val collapse = label("⌃", 19f, 0xFFB7CBDD.toInt()).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(dp(5), 0, dp(5), 0)
            }
            voiceHeader.addView(collapse)
            voiceCard.addView(voiceHeader)

            val voiceBody = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            voiceCard.addView(voiceBody)
            val presetHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            presetHeader.addView(label("6 Voice Presets", 11f, 0xFFB6C9DB.toInt()),
                LinearLayout.LayoutParams(0, dp(34), 1f))
            val expandPresets = label("＋", 18f, 0xFFB6C9DB.toInt()).apply {
                gravity = Gravity.CENTER
                setPadding(dp(9), 0, dp(9), 0)
                isClickable = true
            }
            presetHeader.addView(expandPresets, LinearLayout.LayoutParams(dp(34), dp(34)))
            voiceBody.addView(presetHeader)

            val presetGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val presetButtons = linkedMapOf<VoiceEffect, TextView>()
            val presetEffects = listOf(
                VoiceEffect.ROBOT, VoiceEffect.CHILD, VoiceEffect.ALIEN,
                VoiceEffect.MONSTER, VoiceEffect.HELIUM, VoiceEffect.DEMON
            )
            fun iconFor(effect: VoiceEffect): String = when (effect) {
                VoiceEffect.ROBOT -> "🤖"
                VoiceEffect.CHILD -> "🧒"
                VoiceEffect.ALIEN -> "👽"
                VoiceEffect.MONSTER -> "👹"
                VoiceEffect.HELIUM -> "🎈"
                VoiceEffect.DEMON -> "😈"
                else -> "🎙"
            }
            presetEffects.chunked(3).forEach { group ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                group.forEach { effect ->
                    val tile = TextView(this).apply {
                        text = iconFor(effect) + "\n" + effect.title.uppercase()
                        textSize = 10f
                        gravity = Gravity.CENTER
                        setTextColor(0xFFE6EDF5.toInt())
                        setPadding(dp(2), dp(4), dp(2), dp(4))
                        background = panelSurface(0xFF24364A.toInt(), 0xFF344B63.toInt(), 12)
                        isClickable = true
                        isFocusable = true
                    }
                    presetButtons[effect] = tile
                    tile.setOnClickListener {
                        if (!settings.enabled) return@setOnClickListener
                        tile.animate().scaleX(0.96f).scaleY(0.96f).setDuration(65).withEndAction {
                            tile.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                        }.start()
                        settings = VoiceControlStore.withPreset(settings, effect)
                        VoiceControlStore.save(this@GameAssistantService, settings)
                        refreshVoice?.invoke()
                    }
                    row.addView(tile, LinearLayout.LayoutParams(0, dp(59), 1f).apply {
                        setMargins(dp(2), dp(3), dp(2), dp(3))
                    })
                }
                presetGrid.addView(row)
            }
            voiceBody.addView(presetGrid)

            val pitchHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            pitchHeader.addView(label("PITCH (±12 semitones)", 11f, Color.WHITE),
                LinearLayout.LayoutParams(0, dp(32), 1f))
            val pitchValue = label(String.format("%.1f", settings.pitchSemitones), 10f, 0xFFB7C7D6.toInt())
            pitchHeader.addView(pitchValue)
            voiceBody.addView(pitchHeader)

            val pitchRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val pitchMinus = actionTile("−") { }
            val pitchPlus = actionTile("+") { }
            val pitchBar = SeekBar(this).apply {
                max = 240
                progress = ((settings.pitchSemitones + 12f) * 10f).toInt().coerceIn(0, 240)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        settings = settings.copy(pitchSemitones = -12f + progress / 10f)
                        pitchValue.text = String.format("%.1f", settings.pitchSemitones)
                        if (fromUser) VoiceControlStore.save(this@GameAssistantService, settings)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }
            pitchMinus.setOnClickListener { pitchBar.progress = (pitchBar.progress - 10).coerceAtLeast(0) }
            pitchPlus.setOnClickListener { pitchBar.progress = (pitchBar.progress + 10).coerceAtMost(240) }
            pitchRow.addView(pitchMinus, LinearLayout.LayoutParams(dp(30), dp(34)))
            pitchRow.addView(pitchBar, LinearLayout.LayoutParams(0, dp(42), 1f))
            pitchRow.addView(pitchPlus, LinearLayout.LayoutParams(dp(30), dp(34)))
            voiceBody.addView(pitchRow)

            expandPresets.setOnClickListener {
                val nextVisible = presetGrid.visibility != View.VISIBLE
                presetGrid.visibility = if (nextVisible) View.VISIBLE else View.GONE
                expandPresets.text = if (nextVisible) "－" else "＋"
            }
            var collapsed = false
            collapse.setOnClickListener {
                collapsed = !collapsed
                voiceBody.visibility = if (collapsed) View.GONE else View.VISIBLE
                collapse.text = if (collapsed) "⌄" else "⌃"
            }

            refreshVoice = {
                pitchBar.progress = ((settings.pitchSemitones + 12f) * 10f).toInt().coerceIn(0, 240)
                pitchValue.text = String.format("%.1f", settings.pitchSemitones)
                presetButtons.forEach { (effect, tile) ->
                    tile.background = panelSurface(
                        if (settings.effect == effect) 0xFF174E71.toInt() else 0xFF24364A.toInt(),
                        if (settings.effect == effect) 0xFF62C7FF.toInt() else 0xFF344B63.toInt(),
                        12
                    )
                }
                voiceBody.alpha = if (settings.enabled) 1f else 0.48f
                voiceBody.isEnabled = settings.enabled
                presetButtons.values.forEach { it.isEnabled = settings.enabled }
                pitchBar.isEnabled = settings.enabled
                pitchMinus.isEnabled = settings.enabled
                pitchPlus.isEnabled = settings.enabled
                enableSwitch.isChecked = settings.enabled
            }
            enableSwitch.setOnCheckedChangeListener { _, checked ->
                settings = settings.copy(enabled = checked)
                VoiceControlStore.save(this@GameAssistantService, settings)
                refreshVoice?.invoke()
            }

            content.addView(scroll)
            refreshVoice?.invoke()
            refreshMetrics()
            scroll.alpha = 0f
            scroll.translationY = dp(8).toFloat()
            scroll.animate().alpha(1f).translationY(0f).setDuration(180).start()
        }

        showVoicePanel = { addVoice() }
        showToolsPanel = { addTools() }
        showBrowserPanel = { addBrowser() }
        addGameHome()

        val layout = WindowManager.LayoutParams(
            if (resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels) {
                minOf((resources.displayMetrics.widthPixels * 0.34f).toInt(), dp(360))
            } else {
                minOf((resources.displayMetrics.widthPixels * 0.84f).toInt(), dp(360))
            },
            (resources.displayMetrics.heightPixels * if (resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels) 0.88f else 0.82f).toInt(),
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
            root.translationX = dp(24).toFloat()
            root.alpha = 0f
            root.animate().translationX(0f).alpha(1f).setDuration(200).start()
        } catch (_: Exception) {
            panel = null
            panelParams = null
        }

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

    private fun toggleUnknownCallBlocking() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Call screening requires Android 10 or newer.", Toast.LENGTH_LONG).show()
            return
        }
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager == null || !roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            Toast.makeText(this, "This Android build does not expose call screening.", Toast.LENGTH_LONG).show()
            return
        }
        if (!roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            val request = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(request)
            Toast.makeText(this, "Allow Promax as call-screening app, then tap Block Calls again.", Toast.LENGTH_LONG).show()
            return
        }
        val prefs = getSharedPreferences("promax_settings", MODE_PRIVATE)
        val enabled = !prefs.getBoolean("block_unknown_calls", false)
        prefs.edit().putBoolean("block_unknown_calls", enabled).apply()
        Toast.makeText(this, if (enabled) "Incoming calls will be rejected while this switch is enabled." else "Incoming-call blocking disabled.", Toast.LENGTH_LONG).show()
    }

    private fun applyEyeFilter() {
        applyGameFilter("WARM", false)
    }

    /**
     * These are lightweight screen tints, not the OEM's GPU color-LUT filter
     * pipeline. They are presented as display tints to avoid claiming a
     * per-game renderer effect that a normal overlay cannot provide.
     */
    private fun applyGameFilter(filter: String, persist: Boolean = true) {
        val normalized = filter.uppercase()
        if (persist) {
            getSharedPreferences("promax_settings", MODE_PRIVATE)
                .edit().putString("game_filter", normalized).putBoolean("eye_filter_enabled", normalized != "OFF").apply()
        }
        if (normalized == "OFF") {
            removeEyeFilter()
            return
        }
        if (!PermissionTools.hasOverlay(this)) return

        val tintColor = when (normalized) {
            "WARM" -> 0x20FF9A42
            "COOL" -> 0x1C70BFFF
            "GREEN" -> 0x1B37E6A2
            else -> 0x20FF9A42
        }.toInt()
        val current = eyeFilterView
        if (current != null) {
            current.setBackgroundColor(tintColor)
            return
        }
        val tint = View(this).apply { setBackgroundColor(tintColor) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            windowManager?.addView(tint, params)
            eyeFilterView = tint
        } catch (_: Exception) {
            eyeFilterView = null
        }
    }

    private fun removeEyeFilter() {
        val current = eyeFilterView ?: return
        try { windowManager?.removeView(current) } catch (_: Exception) {}
        eyeFilterView = null
    }

    private fun refreshMetrics() {
        val metrics = metricsReader.read()
        val cpu = metrics.cpuPercent
        val cpuText = cpu?.let { String.format("%.0f%%", it) } ?: "--"
        val ramText = if (metrics.ramTotalMb > 0L) {
            String.format("%.0f%%", (metrics.ramUsedMb * 100f / metrics.ramTotalMb).coerceIn(0f, 100f))
        } else "--"

        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperatureTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val temperatureText = if (temperatureTenths > 0) String.format("%.1f°C", temperatureTenths / 10f) else "N/A"

        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val now = SystemClock.elapsedRealtime()
        var networkText = "--"
        if (rx >= 0L && tx >= 0L && lastNetworkRx >= 0L && lastNetworkTx >= 0L && lastNetworkSampleMs > 0L) {
            val elapsedSeconds = ((now - lastNetworkSampleMs).coerceAtLeast(1L)) / 1000f
            val bytesPerSecond = ((rx - lastNetworkRx).coerceAtLeast(0L) + (tx - lastNetworkTx).coerceAtLeast(0L)) / elapsedSeconds
            networkText = String.format("%.2f MB/s", bytesPerSecond / (1024f * 1024f))
        }
        lastNetworkRx = rx
        lastNetworkTx = tx
        lastNetworkSampleMs = now

        val gpuText = metrics.gpuPercent?.let { String.format("%.0f%%", it) } ?: overlayGpuStatus
        val ramUsed = metrics.ramUsedMb / 1024f
        val ramTotal = metrics.ramTotalMb / 1024f
        val ramAmount = if (metrics.ramTotalMb > 0L) String.format("%.1f/%.1fGB", ramUsed, ramTotal) else "--"

        topStatsView?.text =
            "FPS: " + overlayFpsStatus + "    CPU: " + cpuText + "    GPU: " + gpuText +
            "\nRAM: " + ramAmount + "    TEMP: " + temperatureText + "    NET: " + networkText

        metricsView?.text = "CPU (system): " + cpuText +
            "\nRAM (system): " + ramAmount +
            "\nGPU: " + gpuText +
            "\nFPS: " + overlayFpsStatus +
            "\nTemperature: " + temperatureText +
            "\nNetwork: " + networkText
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
        cpuGaugeView = null
        gpuGaugeView = null
        ramGaugeView = null
        topStatsView = null
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
        removeEyeFilter()
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
