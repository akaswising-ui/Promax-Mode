package com.promax.mode.monitor

import android.app.ActivityManager
import android.content.Context
import java.io.File

data class DeviceMetrics(
    val cpuPercent: Float?,
    val ramUsedMb: Long,
    val ramTotalMb: Long,
    val gpuPercent: Float? = null
)

data class CpuTimes(val total: Long, val idle: Long)

object CpuUsageMath {
    fun parse(line: String): CpuTimes? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.isEmpty() || parts[0] != "cpu" || parts.size < 5) return null
        val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 4) return null
        val idle = values[3] + (values.getOrNull(4) ?: 0L)
        return CpuTimes(values.sum(), idle)
    }

    fun percent(previous: CpuTimes?, current: CpuTimes?): Float? {
        if (previous == null || current == null) return null
        val totalDelta = current.total - previous.total
        val idleDelta = current.idle - previous.idle
        if (totalDelta <= 0) return null
        return (((totalDelta - idleDelta).toFloat() / totalDelta.toFloat()) * 100f)
            .coerceIn(0f, 100f)
    }
}

class DeviceMetricsReader(private val context: Context) {
    private var previousCpu: CpuTimes? = null

    @Synchronized
    fun read(): DeviceMetrics {
        val currentCpu = try {
            CpuUsageMath.parse(File("/proc/stat").useLines { it.first() })
        } catch (_: Exception) {
            null
        }
        val cpu = CpuUsageMath.percent(previousCpu, currentCpu)
        previousCpu = currentCpu

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memory)
        val totalMb = memory.totalMem / (1024L * 1024L)
        val usedMb = ((memory.totalMem - memory.availMem).coerceAtLeast(0L)) / (1024L * 1024L)

        // Public Android APIs do not expose a reliable cross-app GPU utilization percentage.
        return DeviceMetrics(cpu, usedMb, totalMb, null)
    }
}
