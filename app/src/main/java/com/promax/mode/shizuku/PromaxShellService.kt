package com.promax.mode.shizuku

import android.system.Os
import android.system.OsConstants
import com.promax.mode.monitor.SurfaceFlingerFpsParser
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Runs under Shizuku's explicitly granted shell/root identity.
 * Exposes an allow-list of diagnostics and shared-storage operations.
 */
class PromaxShellService : IShizukuBridge.Stub() {
    companion object {
        private const val STORAGE_ROOT = "/storage/emulated/0"
        private val PACKAGE_PATTERN = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")
        private var lastGpuBusy = -1L
        private var lastGpuTotal = -1L
    }

    private var screenRecordPid: Int? = null
    private var screenRecordPath: String? = null

    override fun getIdentity(): String {
        val uid = Os.getuid()
        val mode = when (uid) {
            0 -> "ROOT"
            2000 -> "SHELL (ADB)"
            else -> "UID " + uid
        }
        return mode + " | pid=" + Os.getpid() + " | uid=" + uid
    }

    override fun startFrameStats(packageName: String): String {
        if (!PACKAGE_PATTERN.matches(packageName)) return "ERROR: invalid package name"
        val result = runFixedCommand(
            listOf("/system/bin/dumpsys", "SurfaceFlinger", "--timestats", "-clear", "-enable")
        )
        return if (result.exitCode == 0) {
            "CAPTURE_STARTED"
        } else {
            "ERROR: SurfaceFlinger stats unavailable: " + result.output.take(300)
        }
    }

    override fun dumpFrameStats(packageName: String): String {
        if (!PACKAGE_PATTERN.matches(packageName)) return "ERROR: invalid package name"
        val result = runFixedCommand(
            listOf("/system/bin/dumpsys", "SurfaceFlinger", "--timestats", "-dump")
        )
        if (result.exitCode != 0) {
            return "FPS_UNAVAILABLE: " + result.output.take(250)
        }

        val fps = SurfaceFlingerFpsParser.parse(result.output, packageName)
        return if (fps != null) {
            "FPS=" + fps
        } else {
            "FPS_UNAVAILABLE: no matching SurfaceFlinger game layer for " + packageName
        }
    }

    override fun getGpuUsage(): String {
        // Qualcomm KGSL only. This counter is device-wide, not per-game attribution.
        val file = File("/sys/class/kgsl/kgsl-3d0/gpubusy")
        val values = try {
            file.readText().trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        } catch (_: Exception) {
            return "GPU_UNAVAILABLE: Qualcomm KGSL counter is absent or not readable"
        }
        if (values.size < 2) return "GPU_UNAVAILABLE: unrecognised KGSL counter"

        val busy = values[0]
        val total = values[1]
        if (lastGpuBusy < 0 || lastGpuTotal < 0 || total <= lastGpuTotal || busy < lastGpuBusy) {
            lastGpuBusy = busy
            lastGpuTotal = total
            return "GPU_SAMPLING"
        }

        val busyDelta = busy - lastGpuBusy
        val totalDelta = total - lastGpuTotal
        lastGpuBusy = busy
        lastGpuTotal = total
        if (totalDelta <= 0) return "GPU_SAMPLING"

        val percent = (busyDelta.toDouble() / totalDelta.toDouble() * 100.0)
            .coerceIn(0.0, 100.0)
        return "GPU_DEVICE_WIDE=" + ((percent * 10.0).roundToInt() / 10.0) + "% (KGSL)"
    }

    override fun takeScreenshot(): String {
        val directory = File("/sdcard/Pictures/PromaxMode")
        if (!directory.exists() && !directory.mkdirs()) {
            return "SCREENSHOT_ERROR: unable to create Pictures/PromaxMode"
        }
        val output = File(directory, "Screenshot-" + System.currentTimeMillis() + ".png")
        val result = runFixedCommand(
            listOf("/system/bin/screencap", "-p", output.absolutePath)
        )
        return if (result.exitCode == 0 && output.isFile && output.length() > 256L) {
            "SCREENSHOT_SAVED: " + output.absolutePath
        } else {
            "SCREENSHOT_ERROR: " + result.output.take(180)
        }
    }

    override fun startScreenRecording(): String {
        val existingPid = screenRecordPid
        if (existingPid != null && File("/proc/" + existingPid).exists()) {
            return "RECORDING:" + (screenRecordPath ?: "already active")
        }
        val directory = File("/sdcard/Movies/PromaxMode")
        if (!directory.exists() && !directory.mkdirs()) {
            return "RECORDING_ERROR: unable to create Movies/PromaxMode"
        }
        val output = File(directory, "Screenrecord-" + System.currentTimeMillis() + ".mp4")
        return try {
            // Start screenrecord in the background and capture its PID from the
            // Android shell. java.lang.Process.pid() is not part of Android's API.
            val command = "/system/bin/screenrecord --bit-rate 4000000 --time-limit 180 '" +
                output.absolutePath + "' >/dev/null 2>&1 & echo $!"
            val launcher = ProcessBuilder("/system/bin/sh", "-c", command).start()
            val pidText = launcher.inputStream.bufferedReader().use { it.readLine() }
            launcher.waitFor(2L, TimeUnit.SECONDS)
            val pid = pidText?.trim()?.toIntOrNull()
            if (pid == null) {
                "RECORDING_ERROR: unable to start Android screenrecord"
            } else {
                Thread.sleep(500L)
                if (!File("/proc/" + pid).exists()) {
                    "RECORDING_ERROR: Android screenrecord exited during startup"
                } else {
                    screenRecordPid = pid
                    screenRecordPath = output.absolutePath
                    "RECORDING:" + output.absolutePath
                }
            }
        } catch (error: Exception) {
            "RECORDING_ERROR: " + (error.message ?: "screenrecord unavailable")
        }
    }

    override fun stopScreenRecording(): String {
        val pid = screenRecordPid ?: return "RECORDING_NOT_ACTIVE"
        val path = screenRecordPath.orEmpty()
        try {
            Os.kill(pid, OsConstants.SIGINT)
            repeat(16) {
                Thread.sleep(250L)
                if (!File("/proc/" + pid).exists()) return@repeat
            }
        } catch (_: Exception) {
            return "RECORDING_STOP_ERROR: unable to stop screenrecord process"
        } finally {
            screenRecordPid = null
            screenRecordPath = null
        }
        val output = File(path)
        return if (output.isFile && output.length() > 1024L) {
            "RECORDING_SAVED: " + path
        } else {
            "RECORDING_STOPPED: file may be incomplete: " + path
        }
    }


    override fun listSharedDirectory(relativePath: String): Array<String> {
        val directory = resolveSharedPath(relativePath)
        if (!directory.isDirectory) throw IOException("Not a directory")
        val entries = directory.listFiles() ?: throw IOException("Directory is not readable")
        return entries.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .take(500)
            .map { file ->
                val encodedName = android.util.Base64.encodeToString(
                    file.name.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
                )
                if (file.isDirectory) "D|$encodedName"
                else "F|" + file.length() + "|" + encodedName
            }
            .toTypedArray()
    }

    override fun createDirectory(relativePath: String): Boolean {
        val target = resolveSharedPath(relativePath)
        if (target.exists()) return target.isDirectory
        return target.mkdirs()
    }

    override fun renameEntry(relativePath: String, newName: String): Boolean {
        if (newName.isBlank() || newName == "." || newName == ".." ||
            newName.contains('/') || newName.contains('\\')
        ) return false
        val source = resolveSharedPath(relativePath)
        if (source == File(STORAGE_ROOT).canonicalFile || !source.exists()) return false
        val destination = File(source.parentFile, newName).canonicalFile
        ensureInsideStorage(destination)
        if (destination.exists()) return false
        return source.renameTo(destination)
    }

    override fun deleteEntry(relativePath: String): Boolean {
        val target = resolveSharedPath(relativePath)
        if (target == File(STORAGE_ROOT).canonicalFile || !target.exists()) return false
        // Directories must be empty. Recursive deletion is deliberately absent.
        return target.delete()
    }

    override fun readTextPreview(relativePath: String, maxBytes: Int): String {
        val file = resolveSharedPath(relativePath)
        if (!file.isFile) return "ERROR: not a file"
        val safeLimit = maxBytes.coerceIn(1, 128 * 1024)
        if (file.length() > safeLimit) return "ERROR: preview limited to " + safeLimit + " bytes"
        if (file.extension.lowercase() !in setOf("txt", "json", "xml", "log", "md", "ini", "conf", "csv")) {
            return "ERROR: preview supports text files only"
        }
        return file.readText(Charsets.UTF_8)
    }

    private fun resolveSharedPath(relativePath: String): File {
        val cleaned = relativePath.trim().trim('/')
        if (cleaned.split('/').any { it == ".." || it == "." }) {
            throw SecurityException("Path traversal is not allowed")
        }
        val root = File(STORAGE_ROOT).canonicalFile
        val target = if (cleaned.isBlank()) root else File(root, cleaned).canonicalFile
        ensureInsideStorage(target)
        return target
    }

    private fun ensureInsideStorage(target: File) {
        val root = File(STORAGE_ROOT).canonicalFile
        if (target != root && !target.path.startsWith(root.path + File.separator)) {
            throw SecurityException("Path escapes shared storage")
        }
    }

    private data class CommandResult(val exitCode: Int, val output: String)

    private fun runFixedCommand(arguments: List<String>): CommandResult {
        val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText().take(2_000_000) }
        if (!process.waitFor(12, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return CommandResult(-1, "command timed out")
        }
        return CommandResult(process.exitValue(), output)
    }

    override fun destroy() {
        System.exit(0)
    }

    override fun exit() {
        destroy()
    }
}
