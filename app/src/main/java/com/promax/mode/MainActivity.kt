package com.promax.mode

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promax.mode.audio.VoiceEffect
import com.promax.mode.audio.VoiceRecorder
import com.promax.mode.data.GameAppRepository
import com.promax.mode.monitor.DeviceMetrics
import com.promax.mode.monitor.DeviceMetricsReader
import com.promax.mode.permissions.PermissionTools
import com.promax.mode.service.GameAssistantService
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PromaxApp() }
    }
}

private enum class AppTab(val label: String) {
    HOME("Home"), GAMES("Games"), VOICE("Voice"), ACCESS("Access")
}

@Composable
private fun PromaxApp() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(AppTab.HOME) }
    var games by remember { mutableStateOf(GameAppRepository.savedPackages(context)) }
    var showPicker by remember { mutableStateOf(false) }
    var highRate by remember {
        mutableStateOf(
            context.getSharedPreferences("promax_settings", Context.MODE_PRIVATE)
                .getBoolean("high_monitor_rate", false)
        )
    }
    val colors = androidx.compose.material3.darkColorScheme(
        primary = Color(0xFF7DB7FF),
        secondary = Color(0xFF63E0C1),
        background = Color(0xFF090D14),
        surface = Color(0xFF141B26),
        onBackground = Color(0xFFF3F6FB),
        onSurface = Color(0xFFF3F6FB)
    )

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            containerColor = colors.background,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF101722)) {
                    AppTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { androidx.compose.material3.Text(item.label.take(1), fontWeight = FontWeight.Bold) },
                            label = { androidx.compose.material3.Text(item.label) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    AppTab.HOME -> DashboardScreen(
                        games = games,
                        highRate = highRate,
                        onHighRate = {
                            highRate = it
                            context.getSharedPreferences("promax_settings", Context.MODE_PRIVATE)
                                .edit().putBoolean("high_monitor_rate", it).apply()
                        },
                        onAdd = { tab = AppTab.GAMES; showPicker = true }
                    )
                    AppTab.GAMES -> GameLibraryScreen(
                        games = games,
                        onAdd = { showPicker = true },
                        onRemove = { packageName ->
                            games = games - packageName
                            GameAppRepository.savePackages(context, games)
                        }
                    )
                    AppTab.VOICE -> VoiceStudioScreen()
                    AppTab.ACCESS -> AccessScreen()
                }
            }
        }
    }

    if (showPicker) {
        GamePickerDialog(
            onDismiss = { showPicker = false },
            onSave = { selected ->
                games = games + selected
                GameAppRepository.savePackages(context, games)
                showPicker = false
            }
        )
    }
}

@Composable
private fun DashboardScreen(
    games: Set<String>,
    highRate: Boolean,
    onHighRate: (Boolean) -> Unit,
    onAdd: () -> Unit
) {
    val context = LocalContext.current
    var metrics by remember { mutableStateOf(DeviceMetrics(null, 0, 0, null)) }
    var serviceActive by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val reader = DeviceMetricsReader(context)
        while (true) {
            metrics = reader.read()
            delay(2_000)
        }
    }

    Column(
        Modifier.fillMaxSize()
            .background(Color(0xFF090D14))
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        androidx.compose.material3.Text("PROMAX MODE", fontSize = 25.sp, fontWeight = FontWeight.Black, color = Color.White)
        androidx.compose.material3.Text("Game Space  •  Device tools  •  Voice studio", color = Color(0xFF9DAEC3))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("CPU", metrics.cpuPercent?.let { String.format("%.0f%%", it) } ?: "Sampling", Modifier.weight(1f))
            MetricCard("RAM", if (metrics.ramTotalMb > 0) {
                metrics.ramUsedMb.toString() + " MB used"
            } else "Reading", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("GPU", "Not exposed", Modifier.weight(1f))
            MetricCard("FPS", "Not exposed", Modifier.weight(1f))
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141B26))) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Text("Game library", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                androidx.compose.material3.Text(games.size.toString() + " games selected", color = Color(0xFF9DAEC3))
                Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.Text("Add installed games")
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141B26))) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Text("Monitoring profile", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        androidx.compose.material3.Text(if (highRate) "High monitoring rate" else "Balanced monitoring")
                        androidx.compose.material3.Text(
                            "Changes Promax sampling only, not the phone CPU/GPU governor.",
                            color = Color(0xFF9DAEC3),
                            fontSize = 12.sp
                        )
                    }
                    FilterChip(
                        selected = highRate,
                        onClick = { onHighRate(!highRate) },
                        label = { androidx.compose.material3.Text(if (highRate) "High" else "Balanced") }
                    )
                }
                Button(
                    onClick = {
                        when {
                            games.isEmpty() -> message = "Add at least one game first."
                            !PermissionTools.hasOverlay(context) -> {
                                PermissionTools.openOverlaySettings(context)
                                message = "Allow display over other apps, then return here."
                            }
                            !PermissionTools.hasUsageAccess(context) -> {
                                PermissionTools.openUsageSettings(context)
                                message = "Allow Usage Access, then return here."
                            }
                            else -> {
                                context.startForegroundService(Intent(context, GameAssistantService::class.java))
                                serviceActive = true
                                message = "Assistant started. Open a selected game."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    androidx.compose.material3.Text(if (serviceActive) "Assistant running" else "Start floating assistant")
                }
                OutlinedButton(
                    onClick = {
                        context.stopService(Intent(context, GameAssistantService::class.java))
                        serviceActive = false
                        message = "Assistant stopped."
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { androidx.compose.material3.Text("Stop assistant") }
                if (message.isNotBlank()) androidx.compose.material3.Text(message, color = Color(0xFF9FC8FF), fontSize = 12.sp)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141B26))) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                androidx.compose.material3.Text("RAM cleanup", fontWeight = FontWeight.Bold)
                androidx.compose.material3.Text(
                    "Clears Promax's own cache only. Android controls memory for other apps.",
                    color = Color(0xFF9DAEC3),
                    fontSize = 12.sp
                )
                OutlinedButton(onClick = {
                    context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                    Toast.makeText(context, "Promax local cache cleared", Toast.LENGTH_SHORT).show()
                }) { androidx.compose.material3.Text("Clear Promax cache") }
            }
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF182334)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            androidx.compose.material3.Text(title, color = Color(0xFF9DAEC3), fontSize = 12.sp)
            Spacer(Modifier.height(5.dp))
            androidx.compose.material3.Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun GameLibraryScreen(
    games: Set<String>,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(Color(0xFF090D14)).padding(18.dp)) {
        androidx.compose.material3.Text("Game Library", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        androidx.compose.material3.Text("Selected launcher apps", color = Color(0xFF9DAEC3))
        Spacer(Modifier.height(12.dp))
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.Text("Add / detect games")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(games.toList().sorted(), key = { it }) { packageName ->
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141B26))) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            androidx.compose.material3.Text(GameAppRepository.labelFor(context, packageName), color = Color.White)
                            androidx.compose.material3.Text(packageName, color = Color(0xFF9DAEC3), fontSize = 10.sp)
                        }
                        OutlinedButton(onClick = {
                            val launch = context.packageManager.getLaunchIntentForPackage(packageName)
                            if (launch != null) context.startActivity(launch)
                        }) { androidx.compose.material3.Text("Open") }
                        OutlinedButton(onClick = { onRemove(packageName) }) { androidx.compose.material3.Text("Remove") }
                    }
                }
            }
            if (games.isEmpty()) {
                item { androidx.compose.material3.Text("No games added yet.", color = Color(0xFF9DAEC3)) }
            }
        }
    }
}

@Composable
private fun GamePickerDialog(onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    val context = LocalContext.current
    val apps = remember { GameAppRepository.launchableApps(context) }
    val selected = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("Choose installed games") },
        text = {
            LazyColumn(Modifier.height(380.dp)) {
                items(apps, key = { it.packageName }) { app ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (selected.contains(app.packageName)) selected.remove(app.packageName)
                            else selected.add(app.packageName)
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Text(
                            if (selected.contains(app.packageName)) "☑" else "□",
                            modifier = Modifier.padding(end = 10.dp)
                        )
                        Column {
                            androidx.compose.material3.Text(app.label)
                            androidx.compose.material3.Text(
                                if (app.isLikelyGame) "Likely game" else "Launcher app",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(selected.toSet()) }) { androidx.compose.material3.Text("Add selected") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { androidx.compose.material3.Text("Cancel") } }
    )
}

@Composable
private fun AccessScreen() {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1200)
            refresh++
        }
    }
    val overlayGranted = remember(refresh) { PermissionTools.hasOverlay(context) }
    val usageGranted = remember(refresh) { PermissionTools.hasUsageAccess(context) }
    val listenerGranted = remember(refresh) { PermissionTools.hasNotificationListenerAccess(context) }
    val dndGranted = remember(refresh) { PermissionTools.hasNotificationPolicyAccess(context) }
    val postNotificationsGranted = remember(refresh) {
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    Column(
        Modifier.fillMaxSize()
            .background(Color(0xFF090D14))
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        androidx.compose.material3.Text("Permissions", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        androidx.compose.material3.Text("Grant only the access you plan to use.", color = Color(0xFF9DAEC3))
        PermissionRow("Display over other apps", overlayGranted) {
            PermissionTools.openOverlaySettings(context)
        }
        PermissionRow("Usage access", usageGranted) {
            PermissionTools.openUsageSettings(context)
        }
        PermissionRow("Notification access", listenerGranted) {
            PermissionTools.openNotificationListenerSettings(context)
        }
        PermissionRow("Do Not Disturb access", dndGranted) {
            PermissionTools.openNotificationPolicySettings(context)
        }
        PermissionRow("Post foreground notification", postNotificationsGranted) {
            if (Build.VERSION.SDK_INT >= 33 && context is ComponentActivity) {
                context.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7302)
            }
        }
        androidx.compose.material3.Text(
            "Notification access can dismiss ordinary notifications when enabled. DND can silence call alerts, but cannot reject calls. Android may show additional confirmation screens.",
            color = Color(0xFF9DAEC3),
            fontSize = 12.sp
        )
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141B26))) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                androidx.compose.material3.Text(title, color = Color.White)
                androidx.compose.material3.Text(
                    if (granted) "Allowed" else "Not allowed",
                    color = if (granted) Color(0xFF63E0C1) else Color(0xFFFFB36B),
                    fontSize = 12.sp
                )
            }
            OutlinedButton(onClick = onClick) {
                androidx.compose.material3.Text(if (granted) "Settings" else "Grant")
            }
        }
    }
}

@Composable
private fun VoiceStudioScreen() {
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context) }
    var effect by remember { mutableStateOf(VoiceEffect.ROBOT) }
    var recording by remember { mutableStateOf(false) }
    var savedFile by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Ready") }
    var ttsText by remember { mutableStateOf("Hello, this is Promax voice studio.") }
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(context) {
        val engine = TextToSpeech(context) { }
        ttsEngine = engine
        onDispose {
            recorder.stop()
            engine.stop()
            engine.shutdown()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            recorder.start(
                effect,
                onSaved = {
                    recording = false
                    savedFile = it.absolutePath
                    status = "Saved WAV recording"
                },
                onError = {
                    recording = false
                    status = it
                }
            )
            recording = true
            status = "Recording with " + effect.title + " effect"
        } else {
            status = "Microphone permission denied"
        }
    }

    Column(
        Modifier.fillMaxSize()
            .background(Color(0xFF090D14))
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        androidx.compose.material3.Text("Voice Studio", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        androidx.compose.material3.Text(
            "Record and monitor effects inside Promax. This is not a virtual microphone for other apps.",
            color = Color(0xFF9DAEC3),
            fontSize = 12.sp
        )
        androidx.compose.material3.Text("Voice presets", color = Color.White, fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VoiceEffect.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item ->
                        FilterChip(
                            selected = effect == item,
                            onClick = { effect = item },
                            label = { androidx.compose.material3.Text(item.title) }
                        )
                    }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141B26))) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Text("Microphone recorder", fontWeight = FontWeight.Bold, color = Color.White)
                androidx.compose.material3.Text(status, color = Color(0xFF9DAEC3), fontSize = 12.sp)
                Button(
                    onClick = {
                        if (recording) {
                            recorder.stop()
                            status = "Saving recording..."
                        } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            recorder.start(
                                effect,
                                onSaved = {
                                    recording = false
                                    savedFile = it.absolutePath
                                    status = "Saved WAV recording"
                                },
                                onError = {
                                    recording = false
                                    status = it
                                }
                            )
                            recording = true
                            status = "Recording with " + effect.title + " effect"
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    androidx.compose.material3.Text(if (recording) "Stop and save" else "Start recording")
                }
                if (savedFile.isNotBlank()) {
                    androidx.compose.material3.Text("Saved file: " + savedFile, color = Color(0xFF9FC8FF), fontSize = 10.sp)
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141B26))) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Text("Text to speech", fontWeight = FontWeight.Bold, color = Color.White)
                OutlinedTextField(
                    value = ttsText,
                    onValueChange = { ttsText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { androidx.compose.material3.Text("Text") }
                )
                OutlinedButton(onClick = {
                    ttsEngine?.speak(ttsText, TextToSpeech.QUEUE_FLUSH, null, "promax-tts")
                }) {
                    androidx.compose.material3.Text("Speak with device voice")
                }
            }
        }
        androidx.compose.material3.Text(
            "MVP supports Clean, Robot, Alien, Monster and Echo WAV recording. Pitch/formant sliders, MP3 import/export, custom soundboards and saved profiles are planned.",
            color = Color(0xFF9DAEC3),
            fontSize = 11.sp
        )
    }
}
