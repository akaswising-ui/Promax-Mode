package com.promax.mode.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.promax.mode.MainActivity
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
    private var panel: LinearLayout? = null
    private var metricsView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
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
                showPanel()
                refreshMetrics()
            } else {
                removePanel()
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

    private fun monitorInterval(): Long {
        val highRate = getSharedPreferences("promax_settings", MODE_PRIVATE)
            .getBoolean("high_monitor_rate", false)
        return if (highRate) 1_000L else 2_000L
    }

    private fun showPanel() {
        if (panel != null || !PermissionTools.hasOverlay(this)) return

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(0xEE121821.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0xFF42536B.toInt())
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "PROMAX  •  GAME ASSIST"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, dp(6), 0)
        }
        val toggle = Button(this)
        toggle.text = "Hide"
        toggle.setOnClickListener {
            expanded = !expanded
            metricsView?.visibility = if (expanded) View.VISIBLE else View.GONE
            toggle.text = if (expanded) "Hide" else "Show"
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(toggle, LinearLayout.LayoutParams(dp(72), dp(42)))
        root.addView(header)

        val metrics = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFE5EDF8.toInt())
            setPadding(0, dp(6), 0, dp(8))
            text = "Reading device metrics..."
        }
        metricsView = metrics
        root.addView(metrics)

        val prefs = getSharedPreferences("promax_settings", MODE_PRIVATE)
        val notificationButton = Button(this)
        notificationButton.text = if (prefs.getBoolean("block_notifications", false)) {
            "Notifications: ON"
        } else {
            "Block notifications"
        }
        notificationButton.setOnClickListener {
            if (!PermissionTools.hasNotificationListenerAccess(this)) {
                PermissionTools.openNotificationListenerSettings(this)
            } else {
                val next = !prefs.getBoolean("block_notifications", false)
                prefs.edit().putBoolean("block_notifications", next).apply()
                notificationButton.text = if (next) "Notifications: ON" else "Block notifications"
            }
        }
        root.addView(notificationButton)

        val callsButton = Button(this)
        callsButton.text = if (prefs.getBoolean("silence_calls", false)) {
            "Call alerts: SILENCED"
        } else {
            "Silence call alerts (DND)"
        }
        callsButton.setOnClickListener {
            if (!PermissionTools.hasNotificationPolicyAccess(this)) {
                PermissionTools.openNotificationPolicySettings(this)
            } else {
                val next = !prefs.getBoolean("silence_calls", false)
                if (DndController.silenceCalls(this, next)) {
                    prefs.edit().putBoolean("silence_calls", next).apply()
                    callsButton.text = if (next) "Call alerts: SILENCED" else "Silence call alerts (DND)"
                }
            }
        }
        root.addView(callsButton)

        val note = EditText(this).apply {
            textSize = 12f
            hint = "Quick game note"
            setSingleLine(false)
            minLines = 1
            maxLines = 3
            setText(prefs.getString("quick_note", ""))
        }
        root.addView(note, LinearLayout.LayoutParams(-1, dp(78)))

        val save = Button(this).apply {
            text = "Save note"
            setOnClickListener {
                prefs.edit().putString("quick_note", note.text.toString()).apply()
                text = "Saved"
                postDelayed({ text = "Save note" }, 900)
            }
        }
        root.addView(save)

        // Drag from the title area without swallowing taps on the Hide button.
        title.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val layout = params ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = layout.x
                        startY = layout.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layout.x = startX + (event.rawX - touchX).toInt()
                        layout.y = startY + (event.rawY - touchY).toInt()
                        try { windowManager?.updateViewLayout(root, layout) } catch (_: Exception) {}
                        return true
                    }
                }
                return false
            }
        })

        val layout = WindowManager.LayoutParams(
            dp(270),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(10)
            y = dp(145)
        }

        try {
            windowManager?.addView(root, layout)
            panel = root
            params = layout
        } catch (_: Exception) {
            panel = null
            params = null
        }
    }

    private fun refreshMetrics() {
        val metrics = metricsReader.read()
        val cpu = metrics.cpuPercent?.let { String.format("%.0f%%", it) } ?: "sampling"
        val gpu = metrics.gpuPercent?.let { String.format("%.0f%%", it) } ?: "not exposed"
        val ram = metrics.ramUsedMb.toString() + " / " + metrics.ramTotalMb + " MB"
        metricsView?.text = "CPU (system): " + cpu +
            "\nRAM (system): " + ram +
            "\nGPU: " + gpu +
            "\nFPS: not available to regular apps"
    }

    private fun removePanel() {
        val current = panel ?: return
        try { windowManager?.removeView(current) } catch (_: Exception) {}
        panel = null
        params = null
        metricsView = null
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
            .setContentText("Monitoring is active for games in your library.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollTask)
        removePanel()
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
