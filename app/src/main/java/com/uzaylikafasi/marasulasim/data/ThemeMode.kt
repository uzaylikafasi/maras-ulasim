package com.uzaylikafasi.marasulasim.data

/** Ayarlar > Tema: Sistem (varsayılan), Açık, Koyu (siyah zemin, sarı vurgu, gri yüzeyler). */
enum class ThemeMode(val key: String, val title: String, val subtitle: String) {
    SYSTEM("system", "Sistem", "Telefonun açık/koyu ayarını izler"),
    LIGHT("light", "Açık", "Beyaz yüzeyler, mavi vurgu"),
    DARK("dark", "Koyu", "Siyah zemin, sarı vurgu, gri yüzeyler");
    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: SYSTEM
        fun isDark(mode: ThemeMode, systemDark: Boolean) = when (mode) { SYSTEM -> systemDark; LIGHT -> false; DARK -> true }
    }
}
