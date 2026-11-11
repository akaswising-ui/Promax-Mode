package com.promax.mode.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CpuUsageMathTest {
    @Test
    fun parsesLinuxProcStatCpuLine() {
        val parsed = CpuUsageMath.parse("cpu  100 10 20 50 5 0 0 0")
        assertNotNull(parsed)
        assertEquals(185L, parsed?.total)
        assertEquals(55L, parsed?.idle)
    }

    @Test
    fun computesUsageFromTwoSamples() {
        val before = CpuTimes(total = 100, idle = 50)
        val after = CpuTimes(total = 200, idle = 90)
        assertEquals(60f, CpuUsageMath.percent(before, after) ?: -1f, 0.01f)
    }

    @Test
    fun firstSampleHasNoPercentage() {
        assertEquals(null, CpuUsageMath.percent(null, CpuTimes(100, 40)))
    }
}
