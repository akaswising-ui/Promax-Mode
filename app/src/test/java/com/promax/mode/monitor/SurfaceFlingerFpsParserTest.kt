package com.promax.mode.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SurfaceFlingerFpsParserTest {
    @Test
    fun parsesIndentedLayerWithAverageOnFollowingLine() {
        val dump = """
            layerName = SurfaceView[com.garena.game.ffi/com.garena.game.MainActivity](BLAST)
              packageName = com.garena.game.ffi
              totalFrames = 260
              averageFPS = 59.94
        """.trimIndent()

        assertEquals("59.94", SurfaceFlingerFpsParser.parse(dump, "com.garena.game.ffi"))
    }

    @Test
    fun rejectsAnotherPackagesLayer() {
        val dump = """
            layerName = SurfaceView[com.example.other/com.example.Main](BLAST)
              packageName = com.example.other
              averageFPS = 60
        """.trimIndent()

        assertNull(SurfaceFlingerFpsParser.parse(dump, "com.garena.game.ffi"))
    }

    @Test
    fun selectsCorrectPackageWhenDumpContainsMultipleLayers() {
        val dump = """
            layerName = SurfaceView[com.example.other/com.example.Main](BLAST)
              packageName = com.example.other
              averageFPS = 30
            layerName = SurfaceView[com.garena.game.ffi/com.garena.game.MainActivity](BLAST)
              packageName = com.garena.game.ffi
              averageFPS = 58.5
        """.trimIndent()

        assertEquals("58.5", SurfaceFlingerFpsParser.parse(dump, "com.garena.game.ffi"))
    }
}
