package com.promax.mode

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promax.mode.audio.NativeMp3
import com.promax.mode.audio.VoiceEffect
import com.promax.mode.audio.VoiceProfile
import com.promax.mode.audio.VoiceProfileStore
import com.promax.mode.audio.VoiceRecorder
import com.promax.mode.data.GameAppRepository
import com.promax.mode.monitor.DeviceMetrics
import com.promax.mode.monitor.DeviceMetricsReader
import com.promax.mode.permissions.PermissionTools
import com.promax.mode.service.GameAssistantService
import com.promax.mode.shizuku.ShizukuController
import com.promax.mode.shizuku.ShizukuFileEntry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuController.initialize(this)
        setContent { PromaxApp() }
    }

    override fun onDestroy() {
        if (isFinishing) ShizukuController.dispose()
        super.onDestroy()
    }
}

private enum class AppTab(val label: String) {
    HOME("Home"), GAMES("Games"), VOICE("Voice"), FILES("Files"), ACCESS("Access")
}

private val backgroundColor = Color(0xFF090D14)
private val cardColor = Color(0xFF141B26)
private val mutedText = Color(0xFF9DAEC3)

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
        background = backgroundColor,
        surface = cardColor,
        onBackground = Color(0xFFF3F6FB),
        onSurface = Color(0xFFF3F6FB)
    )

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            containerColor = backgroundColor,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF101722)) {
                    AppTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Text(item.label.take(1), fontWeight = FontWeight.Bold) },
                            label = { Text(item.label) }
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
                    AppTab.FILES -> ShizukuFileManagerScreen()
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
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
            .background(backgroundColor)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("PROMAX MODE", fontSize = 25.sp, fontWeight = FontWeight.Black, color = Color.White)
        Text("Game Space  •  Device tools  •  Voice studio", color = mutedText)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("CPU", metrics.cpuPercent?.let { String.format("%.0f%%", it) } ?: "Sampling", Modifier.weight(1f))
            MetricCard(
                "RAM",
                if (metrics.ramTotalMb > 0) metrics.ramUsedMb.toString() + " MB used" else "Reading",
                Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("GPU", "Shizuku / OEM", Modifier.weight(1f))
            MetricCard("FPS", "Shizuku / game", Modifier.weight(1f))
        }

        Card(colors = CardDefaults.cardColors(containerColor = cardColor)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Game library", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(games.size.toString() + " games selected", color = mutedText)
                Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                    Text("Add installed games")
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = cardColor)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Monitoring profile", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (highRate) "High monitoring rate" else "Balanced monitoring")
                        Text(
                            "Changes Promax sampling only, not the phone CPU/GPU governor.",
                            color = mutedText,
                            fontSize = 12.sp
                        )
                    }
                    FilterChip(
                        selected = highRate,
                        onClick = { onHighRate(!highRate) },
                        label = { Text(if (highRate) "High" else "Balanced") }
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
                                message = "Assistant started. Swipe left from the right edge in a selected game."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (serviceActive) "Assistant running" else "Start floating sidebar")
                }
                OutlinedButton(
                    onClick = {
                        context.stopService(Intent(context, GameAssistantService::class.java))
                        serviceActive = false
                        message = "Assistant stopped."
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Stop assistant") }
                if (message.isNotBlank()) Text(message, color = Color(0xFF9FC8FF), fontSize = 12.sp)
            }
        }

        ShizukuGameMonitorCard(games)

        Card(colors = CardDefaults.cardColors(containerColor = cardColor)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("RAM cleanup", fontWeight = FontWeight.Bold)
                Text(
                    "Clears Promax's own cache only. Android controls memory for other apps.",
                    color = mutedText,
                    fontSize = 12.sp
                )
                OutlinedButton(onClick = {
                    context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                    Toast.makeText(context, "Promax local cache cleared", Toast.LENGTH_SHORT).show()
                }) { Text("Clear Promax cache") }
            }
        }
    }
}

@Composable
private fun ShizukuGameMonitorCard(games: Set<String>) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    var selectedPackage by remember { mutableStateOf(games.firstOrNull().orEmpty()) }
    var menuOpen by remember { mutableStateOf(false) }
    var fpsStatus by remember { mutableStateOf("FPS not sampled") }
    var gpuStatus by remember { mutableStateOf("GPU not sampled") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(games) {
        if (selectedPackage !in games) selectedPackage = games.firstOrNull().orEmpty()
    }

    Card(colors = CardDefaults.cardColors(containerColor = cardColor)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Game-specific monitor", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "Uses Shizuku shell diagnostics. SurfaceFlinger FPS and GPU counters depend on your Android build and GPU driver.",
                color = mutedText,
                fontSize = 12.sp
            )
            Box {
                OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (selectedPackage.isBlank()) "Select a game" else GameAppRepository.labelFor(context, selectedPackage),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    games.forEach { pkg ->
                        DropdownMenuItem(
                            text = { Text(GameAppRepository.labelFor(context, pkg)) },
                            onClick = { selectedPackage = pkg; menuOpen = false }
                        )
                    }
                }
            }
            Text("FPS: " + fpsStatus, color = Color.White, fontSize = 13.sp)
            Text("GPU: " + gpuStatus, color = Color.White, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = selectedPackage.isNotBlank() && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { ShizukuController.startFrameStats(selectedPackage) }
                                    .getOrElse { "ERROR: " + it.message }
                            }
                            fpsStatus = result
                            busy = false
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Start FPS") }
                OutlinedButton(
                    enabled = selectedPackage.isNotBlank() && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { ShizukuController.dumpFrameStats(selectedPackage) }
                                    .getOrElse { "FPS_UNAVAILABLE: " + it.message }
                            }
                            fpsStatus = result
                            busy = false
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Read FPS") }
            }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                ShizukuController.getGpuUsage()
                                delay(1100)
                                ShizukuController.getGpuUsage()
                            }.getOrElse { "GPU_UNAVAILABLE: " + it.message }
                        }
                        gpuStatus = result
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Sample GPU usage") }
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
            Text(title, color = mutedText, fontSize = 12.sp)
            Spacer(Modifier.height(5.dp))
            Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
    Column(Modifier.fillMaxSize().background(backgroundColor).padding(18.dp)) {
        Text("Game Library", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Selected launcher apps", color = mutedText)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Add / detect games") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(games.toList().sorted(), key = { it }) { packageName ->
                Card(colors = CardDefaults.cardColors(containerColor = cardColor)) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(GameAppRepository.labelFor(context, packageName), color = Color.White)
                            Text(packageName, color = mutedText, fontSize = 10.sp)
                        }
                        OutlinedButton(onClick = {
                            val launch = context.packageManager.getLaunchIntentForPackage(packageName)
                            if (launch != null) context.startActivity(launch)
                        }) { Text("Open") }
                        OutlinedButton(onClick = { onRemove(packageName) }) { Text("Remove") }
                    }
                }
            }
            if (games.isEmpty()) item { Text("No games added yet.", color = mutedText) }
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
        title = { Text("Choose installed games") },
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
                        Text(if (selected.contains(app.packageName)) "☑" else "□", Modifier.padding(end = 10.dp))
                        Column {
                            Text(app.label)
                            Text(
                                if (app.isLikelyGame) "Likely game" else "Launcher app",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(selected.toSet()) }) { Text("Add selected") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun AccessScreen() {
    val context = LocalContext.current
    val postPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
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
    val postGranted = remember(refresh) {
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    var shizukuStatus by remember { mutableStateOf(ShizukuController.stateLabel()) }

    LaunchedEffect(refresh) {
        shizukuStatus = ShizukuController.stateLabel()
    }

    Column(
        Modifier.fillMaxSize().background(backgroundColor)
            .verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Permissions & services", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Grant only the access you plan to use.", color = mutedText)
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
        PermissionRow("Post foreground notification", postGranted) {
            if (Build.VERSION.SDK_INT >= 33) postPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        Card(colors = CardDefaults.cardColors(containerColor = cardColor)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shizuku connection", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(shizukuStatus, color = Color(0xFF9FC8FF), fontSize = 13.sp)
                Text(
                    "Start Shizuku separately, then grant Promax permission. ADB mode normally runs as shell UID 2000, not root.",
                    color = mutedText,
                    fontSize = 12.sp
                )
                Button(
                    onClick = {
                        if (!ShizukuController.binderAvailable()) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/")))
                            }
                        } else {
                            ShizukuController.connect()
                            shizukuStatus = ShizukuController.stateLabel()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (ShizukuController.binderAvailable()) "Request permission / connect" else "Shizuku setup guide") }
                OutlinedButton(
                    onClick = {
                        ShizukuController.connect()
                        shizukuStatus = ShizukuController.stateLabel()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Connect Shizuku service") }
                OutlinedButton(
                    onClick = {
                        shizukuStatus = ShizukuController.stateLabel()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Refresh status") }
                Text(
                    "Shizuku file tools are limited to shared storage. It does not unlock every app's private data or protected system partitions.",
                    color = mutedText,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = cardColor)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White)
                Text(
                    if (granted) "Allowed" else "Not allowed",
                    color = if (granted) Color(0xFF63E0C1) else Color(0xFFFFB36B),
                    fontSize = 12.sp
                )
            }
            OutlinedButton(onClick = onClick) { Text(if (granted) "Settings" else "Grant") }
        }
    }
}

@Composable
private fun VoiceStudioScreen() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val recorder = remember { VoiceRecorder(context) }
    var effect by remember { mutableStateOf(VoiceEffect.ROBOT) }
    var pitch by remember { mutableStateOf(0f) }
    var formant by remember { mutableStateOf(0f) }
    var noiseGate by remember { mutableStateOf(-80f) }
    var noiseSuppression by remember { mutableStateOf(true) }
    var recording by remember { mutableStateOf(false) }
    var lastWav by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf("Ready") }
    var profileName by remember { mutableStateOf("My voice") }
    var profiles by remember { mutableStateOf(VoiceProfileStore.list(context)) }
    var audioFiles by remember { mutableStateOf(emptyList<File>()) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var pendingExport by remember { mutableStateOf<File?>(null) }
    var ttsText by remember { mutableStateOf("Hello, this is Promax Voice Studio.") }
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }

    fun loadAudioFiles() {
        val music = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: context.filesDir, "Promax")
        val soundboard = File(music, "Soundboard")
        val output = (music.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("wav", "mp3", "m4a", "ogg") } ?: emptyList()) +
            (soundboard.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("wav", "mp3", "m4a", "ogg") } ?: emptyList())
        audioFiles = output.distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }
    }

    fun applyEffect(selected: VoiceEffect) {
        effect = selected
        when (selected) {
            VoiceEffect.CHILD -> { pitch = 5f; formant = 3.5f }
            VoiceEffect.OLD_MAN -> { pitch = -4.5f; formant = -2.5f }
            VoiceEffect.UNDERWATER -> { pitch = 0f; formant = -1.5f }
            VoiceEffect.MONSTER -> { pitch = -3f; formant = -3f }
            else -> { pitch = 0f; formant = 0f }
        }
    }

    fun startRecording() {
        recorder.start(
            effect = effect,
            pitchSemitones = pitch,
            formantSemitones = formant,
            noiseGateDb = noiseGate,
            systemNoiseSuppression = noiseSuppression,
            onSaved = {
                recording = false
                lastWav = it
                status = "WAV saved: " + it.name
                loadAudioFiles()
            },
            onError = {
                recording = false
                status = it
            }
        )
        recording = true
        status = "Recording: " + effect.title
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording() else status = "Microphone permission denied"
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val imported = withContext(Dispatchers.IO) {
                    try {
                        val mime = context.contentResolver.getType(uri).orEmpty()
                        val extension = when {
                            mime.contains("mpeg") -> "mp3"
                            mime.contains("wav") -> "wav"
                            mime.contains("mp4") || mime.contains("m4a") -> "m4a"
                            else -> "audio"
                        }
                        val music = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: context.filesDir, "Promax")
                        val destinationDir = File(music, "Soundboard")
                        destinationDir.mkdirs()
                        val name = "imported_" + System.currentTimeMillis() + "." + extension
                        val destination = File(destinationDir, name)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            destination.outputStream().use { output -> input.copyTo(output) }
                        } ?: throw IllegalStateException("Could not open selected audio")
                        destination
                    } catch (error: Exception) {
                        throw error
                    }
                }
                loadAudioFiles()
                status = "Imported: " + imported.name
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/mpeg")
    ) { uri ->
        val source = pendingExport
        if (uri != null && source != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            source.inputStream().use { input -> input.copyTo(output) }
                        } ?: throw IllegalStateException("Could not write export")
                    }
                }
                status = if (result.isSuccess) "MP3 exported" else "Export failed: " + result.exceptionOrNull()?.message
            }
        }
    }

    DisposableEffect(context) {
        val engine = TextToSpeech(context) { }
        ttsEngine = engine
        loadAudioFiles()
        onDispose {
            recorder.stop()
            player?.release()
            engine.stop()
            engine.shutdown()
        }
    }

    Column(
        Modifier.fillMaxSize().background(backgroundColor)
            .verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Voice Studio", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Native C++ DSP • WAV recording • MP3 export • Soundboard", color = mutedText, fontSize = 12.sp)

        Card(colors = CardDefaults.cardColors(containerColor = cardColor)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Voice presets", fontWeight = FontWeight.Bold, color = Color.White)
                VoiceEffect.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { item ->
                            FilterChip(
                                selected = effect == item,
                                onClick = { applyEffect(item) },
                                label = { Text(item.title) }
                            )
                        }
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = cardColor)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Pitch: " + String.format("%.1f", pitch) + " semitones", fontWeight = FontWeight.Bold)
                Slider(value = pitch, onValueChange = { pitch = it }, valueRange = -12f..12f)
                Text("Formant: " + String.format("%.1f", formant) + " semitones", fontWeight = FontWeight.Bold)
                Slider(value = formant, onValueChange = { formant = it }, valueRange = -8f..8f)
                Text(
                    if (noiseGate <= -79f) "Noise gate: Off" else "Noise gate threshold: " + String.format("%.0f", noiseGate) + " dB",
                    fontWeight = FontWeight.Bold
                )
                Slider(value = noiseGate, onValueChange = { noiseGate = it }, valueRange = -80f..-20f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Device noise suppression", fontWeight = FontWeight.Bold)
                        Text("Uses Android NoiseSuppressor when the microphone device supports it.", color = mutedText, fontSize = 11.sp)
                    }
                    Switch(checked = noiseSuppression, onCheckedChange = { noiseSuppression = it })
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = cardColor)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Recorder", fontWeight = FontWeight.Bold, color = Color.White)
                Text(status, color = mutedText, fontSize = 12.sp)
                Button(
                    onClick = {
                        if (recording) {
                            recorder.stop()
                            status = "Finalising WAV..."
                        } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            startRecording()
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (recording) "Stop and save WAV" else "Start voice recording") }
                OutlinedButton(
                    enabled = lastWav != null && !recording,
                    onClick = {
                        val wav = lastWav
                        if (wav == null) return@OutlinedButton
                        status = "Encoding MP3..."
                        scope.launch {
                            val encoded = withContext(Dispatchers.IO) {
                                val out = File(context.cacheDir, "promax_export_" + System.currentTimeMillis() + ".mp3")
                                val code = runCatching { NativeMp3.encodeWavToMp3(wav.absolutePath, out.absolutePath) }
                                    .getOrElse { -99 }
                                if (code == 0 && out.exists() && out.length() > 0) out else null
                            }
                            if (encoded != null) {
                                pendingExport = encoded
                                exportLauncher.launch("Promax-Voice.mp3")
                                status = "Choose where to save MP3"
                            } else {
                                status = "MP3 encoding failed. Only mono 44.1 kHz PCM16 WAV is supported."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Export last recording as MP3") }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = cardColor)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Soundboard", fontWeight = FontWeight.Bold, color = Color.White)
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("audio/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Import audio (MP3 / WAV / M4A)") }
                if (audioFiles.isEmpty()) {
                    Text("No audio imported or recorded yet.", color = mutedText, fontSize = 12.sp)
                } else {
                    audioFiles.take(30).forEach { file ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(file.name, Modifier.weight(1f), color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = {
                                runCatching {
                                    player?.release()
                                    val newPlayer = MediaPlayer()
                                    newPlayer.setDataSource(file.absolutePath)
                                    newPlayer.setOnPreparedListener {
                                        it.start()
                                        status = "Playing " + file.name
                                    }
                                    newPlayer.setOnCompletionListener {
                                        it.release()
                                        player = null
                                    }
                                    newPlayer.prepareAsync()
                                    player = newPlayer
                                    status = "Loading " + file.name
                                }.onFailure { status = "Playback failed: " + it.message }
                            }) { Text("Play") }
                            TextButton(onClick = {
                                player?.release()
                                player = null
                                status = "Playback stopped"
                            }) { Text("Stop") }
                        }
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = cardColor)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saved voice profiles", fontWeight = FontWeight.Bold, color = Color.White)
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Profile name") },
                    singleLine = true
                )
                Button(
                    onClick = {
                        if (profileName.isBlank()) return@Button
                        VoiceProfileStore.save(
                            context,
                            VoiceProfile(profileName.trim(), effect, pitch, formant, noiseGate, noiseSuppression)
                        )
                        profiles = VoiceProfileStore.list(context)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save current settings") }
                profiles.forEach { profile ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, color = Color.White)
                            Text(profile.effect.title + " • pitch " + profile.pitchSemitones + " • formant " + profile.formantSemitones, color = mutedText, fontSize = 10.sp)
                        }
                        TextButton(onClick = {
                            effect = profile.effect
                            pitch = profile.pitchSemitones
                            formant = profile.formantSemitones
                            noiseGate = profile.noiseGateDb
                            noiseSuppression = profile.systemNoiseSuppression
                        }) { Text("Load") }
                        TextButton(onClick = {
                            VoiceProfileStore.delete(context, profile.name)
                            profiles = VoiceProfileStore.list(context)
                        }) { Text("Delete") }
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = cardColor)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Text to speech", fontWeight = FontWeight.Bold, color = Color.White)
                OutlinedTextField(
                    value = ttsText,
                    onValueChange = { ttsText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Text") }
                )
                OutlinedButton(onClick = {
                    ttsEngine?.speak(ttsText, TextToSpeech.QUEUE_FLUSH, null, "promax-tts")
                }) { Text("Speak with device voice") }
            }
        }
        Text(
            "DSP sliders are native C++ pitch/formant shaping and a noise gate. Quality depends on microphone input; Android NoiseSuppressor is hardware/device dependent.",
            color = mutedText,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun ShizukuFileManagerScreen() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var status by remember { mutableStateOf(ShizukuController.stateLabel()) }
    var currentPath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<ShizukuFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf("") }
    var targetEntry by remember { mutableStateOf<ShizukuFileEntry?>(null) }
    var textPreview by remember { mutableStateOf("") }
    var folderName by remember { mutableStateOf("") }
    var renameValue by remember { mutableStateOf("") }

    fun loadDirectory(path: String = currentPath) {
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ShizukuController.listDirectory(path) }
            }
            result.onSuccess {
                currentPath = path
                entries = it
                status = ShizukuController.stateLabel()
            }.onFailure {
                status = "Folder read failed: " + it.message
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            status = ShizukuController.stateLabel()
            delay(1500)
        }
    }

    Column(
        Modifier.fillMaxSize().background(backgroundColor).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Shizuku File Manager", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(status, color = Color(0xFF9FC8FF), fontSize = 12.sp)
        Text("Shared storage only • /storage/emulated/0", color = mutedText, fontSize = 11.sp)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    ShizukuController.connect()
                    status = ShizukuController.stateLabel()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Connect Shizuku") }
            OutlinedButton(
                onClick = { loadDirectory() },
                enabled = !loading,
                modifier = Modifier.weight(1f)
            ) { Text("Refresh") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (currentPath.isBlank()) "/storage/emulated/0" else "/storage/emulated/0/" + currentPath,
                Modifier.weight(1f),
                color = Color.White,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = {
                val parent = currentPath.substringBeforeLast('/', "")
                loadDirectory(parent)
            }, enabled = currentPath.isNotBlank()) { Text("Up") }
            TextButton(onClick = { dialog = "mkdir"; folderName = "" }) { Text("New folder") }
        }

        if (loading) Text("Loading...", color = mutedText)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(entries, key = { it.name }) { entry ->
                Card(colors = CardDefaults.cardColors(containerColor = cardColor)) {
                    Row(
                        Modifier.fillMaxWidth().padding(9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier.weight(1f).clickable(enabled = entry.isDirectory) {
                                val next = if (currentPath.isBlank()) entry.name else currentPath + "/" + entry.name
                                loadDirectory(next)
                            }
                        ) {
                            Text((if (entry.isDirectory) "📁 " else "📄 ") + entry.name, color = Color.White, fontSize = 12.sp)
                            Text(
                                if (entry.isDirectory) "Folder" else entry.sizeBytes.toString() + " bytes",
                                color = mutedText,
                                fontSize = 10.sp
                            )
                        }
                        if (!entry.isDirectory) {
                            TextButton(onClick = {
                                targetEntry = entry
                                scope.launch {
                                    val path = if (currentPath.isBlank()) entry.name else currentPath + "/" + entry.name
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { ShizukuController.readTextPreview(path) }
                                    }
                                    textPreview = result.getOrElse { "Preview unavailable: " + it.message }
                                    dialog = "preview"
                                }
                            }) { Text("View") }
                        }
                        TextButton(onClick = {
                            targetEntry = entry
                            renameValue = entry.name
                            dialog = "rename"
                        }) { Text("Rename") }
                        TextButton(onClick = {
                            targetEntry = entry
                            dialog = "delete"
                        }) { Text("Delete") }
                    }
                }
            }
            if (!loading && entries.isEmpty()) {
                item { Text("Folder empty, or Shizuku has not connected.", color = mutedText, fontSize = 12.sp) }
            }
        }
    }

    when (dialog) {
        "mkdir" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Create folder") },
            text = {
                OutlinedTextField(folderName, { folderName = it }, label = { Text("Folder name") })
            },
            confirmButton = {
                Button(onClick = {
                    val path = if (currentPath.isBlank()) folderName.trim() else currentPath + "/" + folderName.trim()
                    dialog = ""
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { ShizukuController.createDirectory(path) }.getOrDefault(false) }
                        status = if (ok) "Folder created" else "Create folder failed"
                        loadDirectory()
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } }
        )
        "rename" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Rename entry") },
            text = {
                OutlinedTextField(renameValue, { renameValue = it }, label = { Text("New name") })
            },
            confirmButton = {
                Button(onClick = {
                    val item = targetEntry
                    if (item != null) {
                        val path = if (currentPath.isBlank()) item.name else currentPath + "/" + item.name
                        dialog = ""
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { ShizukuController.renameEntry(path, renameValue.trim()) }.getOrDefault(false)
                            }
                            status = if (ok) "Renamed" else "Rename failed"
                            loadDirectory()
                        }
                    }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } }
        )
        "delete" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Delete entry?") },
            text = {
                Text("This delete is not recursive. Folders must be empty. Files are permanently deleted from shared storage.")
            },
            confirmButton = {
                Button(onClick = {
                    val item = targetEntry
                    if (item != null) {
                        val path = if (currentPath.isBlank()) item.name else currentPath + "/" + item.name
                        dialog = ""
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { ShizukuController.deleteEntry(path) }.getOrDefault(false)
                            }
                            status = if (ok) "Deleted" else "Delete failed; folder may not be empty"
                            loadDirectory()
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } }
        )
        "preview" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text(targetEntry?.name ?: "File preview") },
            text = {
                Column(Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                    Text(textPreview, color = Color.White, fontSize = 11.sp)
                }
            },
            confirmButton = { Button(onClick = { dialog = "" }) { Text("Close") } }
        )
    }
}
