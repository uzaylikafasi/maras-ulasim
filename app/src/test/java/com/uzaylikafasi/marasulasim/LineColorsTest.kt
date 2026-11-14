package com.uzaylikafasi.marasulasim

import com.uzaylikafasi.marasulasim.data.*
import org.junit.Assert.*
import org.junit.Test

class LineColorsTest {
    @Test fun lightPaletteStaysUnchanged() {
        assertEquals(listOf("#2563EB", "#EA580C", "#9333EA", "#DB2777", "#16A34A", "#0891B2"), lineColors)
        assertEquals("#2563EB", lineColorFor("H/10", listOf("H/10")))
    }

    @Test fun darkPaletteStartsWithAmberAndHasNoBlueOrCyan() {
        assertEquals(6, darkLineColors.distinct().size)
        assertEquals("#FFC107", darkLineColors.first())
        darkLineColors.forEach { color ->
            val rgb = color.removePrefix("#").toInt(16)
            val r = (rgb shr 16) and 255
            val g = (rgb shr 8) and 255
            val b = rgb and 255
            val max = maxOf(r, g, b)
            val delta = (max - minOf(r, g, b)).toDouble()
            assertTrue("Ayırt edilebilir renk: $color", delta > 0)
            val hue = ((when (max) {
                r -> (g - b) / delta
                g -> (b - r) / delta + 2
                else -> (r - g) / delta + 4
            } * 60) + 360) % 360
            assertFalse("Mavi/camgöbeği olmamalı: $color", hue in 160.0..260.0)
            assertEquals("#000000", lineTextColorFor(color))
        }
    }

    @Test fun routesAndBadgesUseTheChosenPalette() {
        val codes = (0..12).map { "H/$it" }
        listOf(false, true).forEach { dark ->
            val colors = if (dark) darkLineColors else lineColors
            codes.forEachIndexed { index, code ->
                assertEquals(colors[index % colors.size], lineColorFor(code, codes, dark))
                assertEquals(colors[Math.floorMod(code.hashCode(), colors.size)], badgeColorFor(code, dark))
            }
        }
        assertEquals("#FFC107", lineColorFor("H/10", listOf("H/10"), dark = true))
    }

    @Test fun textChoosesTheHigherContrastColor() {
        assertEquals("#000000", lineTextColorFor("#FFFFFF"))
        assertEquals("#FFFFFF", lineTextColorFor("#000000"))
        assertEquals("#FFFFFF", lineTextColorFor("#2563EB"))
    }
}
