package com.promax.mode.monitor

/**
 * Parses SurfaceFlinger's timestats dump without assuming that every field is
 * on the same line or that layer labels have no indentation.
 */
object SurfaceFlingerFpsParser {
    private val fpsPattern = Regex("""(?i)\\baverageFPS\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)""")
    private val layerHeaderPattern = Regex("""(?im)^\\s*layerName\\s*=""")

    fun parse(output: String, packageName: String): String? {
        val target = packageName.trim()
        if (target.isEmpty()) return null

        val starts = layerHeaderPattern.findAll(output).map { it.range.first }.toList()
        val blocks = if (starts.isEmpty()) {
            listOf(output)
        } else {
            starts.mapIndexed { index, start ->
                val end = starts.getOrNull(index + 1) ?: output.length
                output.substring(start, end)
            }
        }

        for (block in blocks) {
            val packageFieldMatches = Regex(
                """(?im)^\\s*packageName\\s*=\\s*["']?""" + Regex.escape(target) + """["']?\\s*$"""
            ).containsMatchIn(block)
            val layerMatches = block.contains(target, ignoreCase = true)
            if (!packageFieldMatches && !layerMatches) continue

            val fps = fpsPattern.findAll(block).lastOrNull()?.groupValues?.getOrNull(1)
            if (fps != null) {
                val parsed = fps.toDoubleOrNull() ?: continue
                if (parsed in 0.0..1000.0) return fps
            }
        }

        // Some Android builds print packageName and the average on a single
        // non-layer block. Accept it only when both are present together.
        val hasPackage = Regex(
            """(?im)^\\s*packageName\\s*=\\s*["']?""" + Regex.escape(target) + """["']?\\s*$"""
        ).containsMatchIn(output)
        if (hasPackage) {
            val fps = fpsPattern.findAll(output).lastOrNull()?.groupValues?.getOrNull(1)
            val parsed = fps?.toDoubleOrNull()
            if (fps != null && parsed != null && parsed in 0.0..1000.0) return fps
        }
        return null
    }
}
