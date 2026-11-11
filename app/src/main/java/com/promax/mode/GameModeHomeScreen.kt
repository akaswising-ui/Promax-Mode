package com.promax.mode

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promax.mode.data.GameAppRepository
import com.promax.mode.monitor.DeviceMetrics
import com.promax.mode.monitor.DeviceMetricsReader
import com.promax.mode.permissions.PermissionTools
import com.promax.mode.service.GameAssistantService
import com.promax.mode.shizuku.ShizukuController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

private val gameBg = Color(0xFF070B12)
private val gamePanel = Color(0xFF121B25)
private val gamePanel2 = Color(0xFF182633)
private val gameMuted = Color(0xFF9BAEB9)
private val gameAccent = Color(0xFFB8F34A)
private val gameCyan = Color(0xFF47DCC1)

@Composable
internal fun GameModeHomeScreen(
    games: Set<String>,
    highRate: Boolean,
    onHighRate: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onVoice: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedPackage by remember { mutableStateOf(games.firstOrNull().orEmpty()) }
    var gameMenuOpen by remember { mutableStateOf(false) }
    var serviceActive by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var fpsStatus by remember { mutableStateOf("Not measured") }
    var gpuStatus by remember { mutableStateOf("Not sampled") }
    var recording by remember { mutableStateOf(false) }
    var metrics by remember { mutableStateOf(DeviceMetrics(null, 0, 0, null)) }
    var batteryTemp by remember { mutableStateOf("--") }
    var networkSpeed by remember { mutableStateOf("--") }
    var eyeFilterEnabled by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }

    LaunchedEffect(games) {
        if (selectedPackage !in games) selectedPackage = games.firstOrNull().orEmpty()
    }

    LaunchedEffect(highRate) {
        val reader = DeviceMetricsReader(context)
        var previousRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
        var previousTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
        var previousTime = SystemClock.elapsedRealtime()
        while (true) {
            val nextMetrics = withContext(Dispatchers.IO) { reader.read() }
            metrics = nextMetrics
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            batteryTemp = if (tempTenths > 0) String.format("%.1f°C", tempTenths / 10f) else "N/A"
            val nowRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
            val nowTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
            val now = SystemClock.elapsedRealtime()
            val seconds = max(1L, now - previousTime) / 1000f
            val byteDelta = max(0L, nowRx - previousRx) + max(0L, nowTx - previousTx)
            val mbps = (byteDelta * 8f / seconds) / 1_000_000f
            networkSpeed = if (TrafficStats.getTotalRxBytes() < 0L) "N/A" else String.format("%.2f Mbps", mbps)
            previousRx = nowRx
            previousTx = nowTx
            previousTime = now
            delay(if (highRate) 1_200L else 2_400L)
        }
    }

    val cpuValue = metrics.cpuPercent?.let { String.format("%.0f%%", it) } ?: "Sampling"
    val ramValue = if (metrics.ramTotalMb > 0) {
        String.format("%.0f%%", (metrics.ramUsedMb * 100f / metrics.ramTotalMb).coerceIn(0f, 100f))
    } else "N/A"

    LazyColumn(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF09131A), gameBg, Color(0xFF05080E))))
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("PROMAX", color = Color.White, fontWeight = FontWeight.Black, fontSize = 25.sp)
                    Text("GAME SPACE  /  PERFORMANCE HUB", color = gameMuted, fontSize = 10.sp, letterSpacing = 1.2.sp)
                }
                Surface(shape = CircleShape, color = Color(0xFF213027)) {
                    Text("✦", modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp), color = gameAccent, fontSize = 18.sp)
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = gamePanel2),
                modifier = Modifier.fillMaxWidth().animateContentSize()
            ) {
                Box(
                    Modifier.fillMaxWidth()
                        .background(Brush.linearGradient(listOf(Color(0xFF253C39), Color(0xFF14202B), Color(0xFF14202B))))
                        .padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = gameAccent.copy(alpha = 0.16f), shape = RoundedCornerShape(50)) {
                                Text("GAME MODE", color = gameAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp))
                            }
                            Spacer(Modifier.weight(1f))
                            Text(if (serviceActive) "● ACTIVE" else "○ STANDBY",
                                color = if (serviceActive) gameAccent else gameMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(58.dp).clip(RoundedCornerShape(18.dp))
                                    .background(Color(0xFF30443F)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🎮", fontSize = 28.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (selectedPackage.isBlank()) "No game selected" else GameAppRepository.labelFor(context, selectedPackage),
                                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(if (games.isEmpty()) "Add a game to create its profile" else selectedPackage,
                                    color = gameMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Box {
                                Text("⌄", color = gameAccent, fontSize = 24.sp,
                                    modifier = Modifier.clickable { gameMenuOpen = true }.padding(8.dp))
                                DropdownMenu(expanded = gameMenuOpen, onDismissRequest = { gameMenuOpen = false }) {
                                    games.forEach { packageName ->
                                        DropdownMenuItem(
                                            text = { Text(GameAppRepository.labelFor(context, packageName)) },
                                            onClick = { selectedPackage = packageName; gameMenuOpen = false }
                                        )
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Button(
                                onClick = {
                                    val launch = context.packageManager.getLaunchIntentForPackage(selectedPackage)
                                    if (launch != null) context.startActivity(launch)
                                    else Toast.makeText(context, "Game launch activity not found.", Toast.LENGTH_SHORT).show()
                                },
                                enabled = selectedPackage.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = gameAccent, contentColor = Color(0xFF17210C)),
                                modifier = Modifier.weight(1f)
                            ) { Text("PLAY GAME", fontWeight = FontWeight.Black) }
                            OutlinedButton(onClick = onAdd, modifier = Modifier.weight(1f)) {
                                Text("＋ ADD GAME", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        Button(
                            onClick = {
                                when {
                                    games.isEmpty() -> notice = "Pehle Game Library mein game add karo."
                                    !PermissionTools.hasOverlay(context) -> {
                                        PermissionTools.openOverlaySettings(context)
                                        notice = "Display over other apps permission allow karo."
                                    }
                                    !PermissionTools.hasUsageAccess(context) -> {
                                        PermissionTools.openUsageSettings(context)
                                        notice = "Usage Access allow karke wapas aao."
                                    }
                                    else -> {
                                        context.startForegroundService(Intent(context, GameAssistantService::class.java))
                                        serviceActive = true
                                        notice = "Game Assistant active. Game ke right edge se swipe karo."
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263B38), contentColor = gameAccent),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (serviceActive) "FLOATING GAME PANEL ACTIVE" else "START GAME ASSISTANT",
                                fontWeight = FontWeight.Bold)
                        }
                        AnimatedVisibility(visible = notice.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
                            Text(notice, color = gameAccent, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LIVE DEVICE MONITOR", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp)
                Spacer(Modifier.weight(1f))
                Text("SYSTEM-WIDE", color = gameMuted, fontSize = 9.sp, letterSpacing = 1.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                GameMetricCard("CPU", cpuValue, "PROCESSOR", Color(0xFF67DDBB), Modifier.weight(1f))
                GameMetricCard("RAM", ramValue, "MEMORY", Color(0xFFB8F34A), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                GameMetricCard("BATTERY TEMP", batteryTemp, "BATTERY SENSOR", Color(0xFFFFB35B), Modifier.weight(1f))
                GameMetricCard("NETWORK", networkSpeed, "TOTAL DEVICE TRAFFIC", Color(0xFF76B9FF), Modifier.weight(1f))
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = gamePanel), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("GAME MONITOR", color = Color.White, fontWeight = FontWeight.Black, fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                        Text("SHIZUKU / OEM", color = gameMuted, fontSize = 9.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        GameMetricCard("FPS", fpsStatus, "SELECTED GAME", gameAccent, Modifier.weight(1f), compact = true)
                        GameMetricCard("GPU", gpuStatus, "DRIVER COUNTER", gameCyan, Modifier.weight(1f), compact = true)
                    }
                    Text(
                        "FPS uses SurfaceFlinger game-layer data. GPU is a device-wide driver counter when exposed; it is not per-game attribution.",
                        color = gameMuted, fontSize = 10.sp, lineHeight = 15.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = selectedPackage.isNotBlank() && !busy,
                            onClick = {
                                val target = selectedPackage
                                busy = true
                                fpsStatus = "Capturing..."
                                scope.launch {
                                    try {
                                        val started = withContext(Dispatchers.IO) { ShizukuController.startFrameStats(target) }
                                        if (started == "CAPTURE_STARTED") {
                                            fpsStatus = "Measuring..."
                                            var finalStatus = "Layer not found"
                                            var measuredFps: String? = null
                                            for (attempt in 0 until 6) {
                                                delay(850)
                                                val read = withContext(Dispatchers.IO) { ShizukuController.dumpFrameStats(target) }
                                                if (read.startsWith("FPS=")) {
                                                    measuredFps = read.removePrefix("FPS=").trim()
                                                    break
                                                }
                                                finalStatus = read.removePrefix("FPS_UNAVAILABLE:").trim()
                                            }
                                            fpsStatus = measuredFps
                                                ?: if (finalStatus.contains("no matching", true)) "Retry with game open" else finalStatus.take(35)
                                        } else {
                                            fpsStatus = started.take(42)
                                        }
                                    } catch (error: Throwable) {
                                        fpsStatus = "Connect Shizuku"
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = gameAccent, contentColor = Color(0xFF17210C)),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text("START FPS")
                        }
                        OutlinedButton(
                            enabled = selectedPackage.isNotBlank() && !busy,
                            onClick = {
                                val target = selectedPackage
                                busy = true
                                scope.launch {
                                    fpsStatus = try {
                                        withContext(Dispatchers.IO) { ShizukuController.dumpFrameStats(target) }
                                            .removePrefix("FPS=").take(42)
                                    } catch (_: Throwable) { "Connect Shizuku" }
                                    busy = false
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("READ FPS") }
                    }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                gpuStatus = try {
                                    withContext(Dispatchers.IO) { ShizukuController.getGpuUsage() }
                                    delay(1_050)
                                    withContext(Dispatchers.IO) { ShizukuController.getGpuUsage() }
                                        .removePrefix("GPU_DEVICE_WIDE=")
                                        .removePrefix("GPU_UNAVAILABLE:")
                                        .take(42)
                                } catch (_: Throwable) { "Connect Shizuku" }
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("SAMPLE GPU COUNTER") }
                }
            }
        }

        item {
            Text("GAME TOOLS", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                GameActionTile("VOICE STUDIO", "Local voice effects", "🎙", onVoice, Modifier.weight(1f))
                GameActionTile("SCREENSHOT", "Shizuku capture", "📸", {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { ShizukuController.takeScreenshot() }.getOrElse { "Connect Shizuku first" }
                        }
                        Toast.makeText(context, result, Toast.LENGTH_LONG).show()
                    }
                }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                GameActionTile("SCREEN RECORD", if (recording) "Tap to stop" else "Up to 180 seconds", "⏺", {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                if (recording) ShizukuController.stopScreenRecording()
                                else ShizukuController.startScreenRecording()
                            }.getOrElse { "Connect Shizuku first" }
                        }
                        recording = result.startsWith("RECORDING:")
                        Toast.makeText(context, result, Toast.LENGTH_LONG).show()
                    }
                }, Modifier.weight(1f))
                GameActionTile("EYE FILTER", if (eyeFilterEnabled) "Warm tint ON" else "Warm tint OFF", "🌗", {
                    eyeFilterEnabled = !eyeFilterEnabled
                    context.startForegroundService(
                        Intent(context, GameAssistantService::class.java)
                            .setAction(GameAssistantService.ACTION_SET_EYE_FILTER)
                            .putExtra(GameAssistantService.EXTRA_EYE_FILTER_ENABLED, eyeFilterEnabled)
                    )
                }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                GameActionTile("SILENT / DND", "Call alerts only", "🔕", {
                    Toast.makeText(context, "Use DND control inside the floating Game Panel.", Toast.LENGTH_SHORT).show()
                    context.startForegroundService(Intent(context, GameAssistantService::class.java))
                    serviceActive = true
                }, Modifier.weight(1f))
                GameActionTile("BLOCK NOTIFS", "Notification access", "🔔", {
                    if (!PermissionTools.hasNotificationListenerAccess(context)) {
                        PermissionTools.openNotificationListenerSettings(context)
                    } else {
                        Toast.makeText(context, "Toggle notification blocking in the floating panel.", Toast.LENGTH_SHORT).show()
                        context.startForegroundService(Intent(context, GameAssistantService::class.java))
                        serviceActive = true
                    }
                }, Modifier.weight(1f))
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = gamePanel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("MONITOR PROFILE", color = Color.White, fontWeight = FontWeight.Black, fontSize = 14.sp)
                    Text("Changes Promax polling speed only. Android still controls device CPU/GPU clocks and touch sampling.",
                        color = gameMuted, fontSize = 10.sp, lineHeight = 15.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !highRate, onClick = { onHighRate(false) }, label = { Text("BALANCED") })
                        FilterChip(selected = highRate, onClick = { onHighRate(true) }, label = { Text("PRO MONITOR") })
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun GameMetricCard(
    title: String,
    value: String,
    foot: String,
    accent: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Card(
        modifier = modifier.animateContentSize(),
        shape = RoundedCornerShape(if (compact) 16.dp else 20.dp),
        colors = CardDefaults.cardColors(containerColor = gamePanel)
    ) {
        Column(Modifier.fillMaxWidth().padding(if (compact) 12.dp else 14.dp)) {
            Text(title, color = accent, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 0.7.sp)
            Spacer(Modifier.height(6.dp))
            Text(value, color = Color.White, fontSize = if (compact) 14.sp else 19.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(foot, color = gameMuted, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun GameActionTile(
    title: String,
    subtitle: String,
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.animateContentSize().clickable(onClick = onClick),
        color = gamePanel,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF24352F)),
                contentAlignment = Alignment.Center
            ) {
                Text(icon, fontSize = 18.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = gameMuted, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
