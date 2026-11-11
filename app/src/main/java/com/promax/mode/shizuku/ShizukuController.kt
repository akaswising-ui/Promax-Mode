package com.promax.mode.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

data class ShizukuFileEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long
)

object ShizukuController {
    private const val REQUEST_CODE = 4711
    private var appContext: Context? = null
    @Volatile private var remote: IShizukuBridge? = null
    @Volatile private var lastError: String? = null
    @Volatile private var serviceBound = false
    private var permissionCallback: (() -> Unit)? = null

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        lastError = null
        permissionCallback?.invoke()
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        remote = null
        serviceBound = false
        lastError = "Shizuku service disconnected"
        permissionCallback?.invoke()
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, result ->
        if (requestCode == REQUEST_CODE && result != PackageManager.PERMISSION_GRANTED) {
            lastError = "Shizuku permission denied"
        } else if (requestCode == REQUEST_CODE) {
            lastError = "Permission granted. Connect the Promax helper."
        }
        permissionCallback?.invoke()
    }

    private val args: Shizuku.UserServiceArgs
        get() {
            val context = appContext ?: throw IllegalStateException("Promax context not initialized")
            return Shizuku.UserServiceArgs(
                ComponentName(context.packageName, PromaxShellService::class.java.name)
            )
                .daemon(false)
                .tag("promax-shell-diagnostics-v1")
                .processNameSuffix("promax_shell")
                .version(1)
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            remote = IShizukuBridge.Stub.asInterface(binder)
            serviceBound = true
            lastError = null
            permissionCallback?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remote = null
            serviceBound = false
            lastError = "Promax Shizuku helper disconnected"
            permissionCallback?.invoke()
        }
    }

    fun initialize(context: Context, onStateChanged: (() -> Unit)? = null) {
        appContext = context.applicationContext
        permissionCallback = onStateChanged
        runCatching {
            Shizuku.removeBinderReceivedListener(binderReceived)
            Shizuku.removeBinderDeadListener(binderDead)
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        }.onFailure { lastError = it.message }
    }

    fun dispose() {
        runCatching {
            if (serviceBound) Shizuku.unbindUserService(args, connection, true)
            Shizuku.removeBinderReceivedListener(binderReceived)
            Shizuku.removeBinderDeadListener(binderDead)
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        }
        remote = null
        serviceBound = false
        appContext = null
    }

    fun binderAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun permissionGranted(): Boolean = try {
        binderAvailable() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun requestPermission() {
        if (!binderAvailable()) {
            lastError = "Start Shizuku first"
            permissionCallback?.invoke()
            return
        }
        if (Shizuku.isPreV11()) {
            lastError = "Shizuku API v11 or later is required"
            permissionCallback?.invoke()
            return
        }
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (error: Throwable) {
            lastError = error.message ?: "Unable to request Shizuku permission"
            permissionCallback?.invoke()
        }
    }

    fun connect() {
        if (!binderAvailable()) {
            lastError = "Shizuku is not running"
            permissionCallback?.invoke()
            return
        }
        if (!permissionGranted()) {
            requestPermission()
            return
        }
        if (remote != null || serviceBound) return
        try {
            Shizuku.bindUserService(args, connection)
            serviceBound = true
            lastError = "Connecting to Shizuku..."
        } catch (error: Throwable) {
            serviceBound = false
            lastError = error.message ?: "Unable to bind Shizuku service"
        }
        permissionCallback?.invoke()
    }

    fun stateLabel(): String {
        if (!binderAvailable()) return "Shizuku service not running"
        if (!permissionGranted()) return "Waiting for Promax permission"
        if (remote == null) return lastError ?: "Shizuku connected, helper not bound"
        return runCatching { remote?.getIdentity() ?: "Disconnected" }
            .getOrElse { "Connected, identity unavailable: " + it.message }
    }

    fun errorMessage(): String? = lastError

    private fun service(): IShizukuBridge =
        remote ?: throw IllegalStateException("Connect Shizuku before using this feature")

    fun startFrameStats(packageName: String): String = service().startFrameStats(packageName)
    fun dumpFrameStats(packageName: String): String = service().dumpFrameStats(packageName)
    fun getGpuUsage(): String = service().getGpuUsage()
    fun takeScreenshot(): String = service().takeScreenshot()
    fun startScreenRecording(): String = service().startScreenRecording()
    fun stopScreenRecording(): String = service().stopScreenRecording()
    fun createDirectory(path: String): Boolean = service().createDirectory(path)
    fun renameEntry(path: String, newName: String): Boolean = service().renameEntry(path, newName)
    fun deleteEntry(path: String): Boolean = service().deleteEntry(path)
    fun readTextPreview(path: String, maxBytes: Int = 64 * 1024): String =
        service().readTextPreview(path, maxBytes)

    fun listDirectory(path: String): List<ShizukuFileEntry> {
        return service().listSharedDirectory(path).mapNotNull { encoded ->
            val parts = encoded.split('|')
            if (parts.size < 2) return@mapNotNull null
            val isDirectory = parts[0] == "D"
            val size = if (isDirectory) 0L else parts.getOrNull(1)?.toLongOrNull() ?: 0L
            val encodedName = if (isDirectory) parts[1] else parts.getOrNull(2) ?: return@mapNotNull null
            val name = runCatching {
                String(
                    android.util.Base64.decode(
                        encodedName,
                        android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
                    ),
                    Charsets.UTF_8
                )
            }.getOrNull() ?: return@mapNotNull null
            ShizukuFileEntry(name, isDirectory, size)
        }
    }
}
