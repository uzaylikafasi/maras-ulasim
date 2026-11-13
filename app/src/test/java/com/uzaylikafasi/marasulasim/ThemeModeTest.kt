package com.uzaylikafasi.marasulasim

import com.uzaylikafasi.marasulasim.data.*
import org.junit.Assert.*
import org.junit.Test

class ThemeModeTest {
    @Test fun systemFollowsDeviceTheme() { assertTrue(ThemeMode.isDark(ThemeMode.SYSTEM, true)); assertFalse(ThemeMode.isDark(ThemeMode.SYSTEM, false)) }
    @Test fun manualChoiceIgnoresDeviceTheme() {
        assertTrue(ThemeMode.isDark(ThemeMode.DARK, false)); assertFalse(ThemeMode.isDark(ThemeMode.LIGHT, true))
    }
    @Test fun unknownKeyFallsBackToSystem() { assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey("bozuk")); assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey(null)) }
    @Test fun darkMapIsOnByDefault() { assertTrue(Settings().darkMap); assertEquals(ThemeMode.SYSTEM, Settings().themeMode) }
}
