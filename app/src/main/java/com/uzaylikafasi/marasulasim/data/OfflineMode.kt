package com.uzaylikafasi.marasulasim.data

/** Ayarlar > Çevrimdışı mod: tek seçim. */
enum class OfflineMode(val key: String, val title: String, val subtitle: String) {
    BUTTON("button", "Düğme", "Sağ üstteki düğmeyle çevrimdışı tahmini kendin aç/kapat; bağlantı kötüyse düğme büyür"),
    AUTO("auto", "Otomatik", "Ağ ve kayıtlı kopya yoksa çevrimdışı tahmin kendiliğinden kullanılır"),
    OFF("off", "Kapalı", "Çevrimdışı mod yok; uygulama eskisi gibi çalışır");
    companion object { fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: BUTTON }
}

data class OfflineConfig(val mode: OfflineMode = OfflineMode.BUTTON, val manualOn: Boolean = false)

/** Sağ üst düğmenin durumu. */
data class OfflineButtonState(val visible: Boolean, val on: Boolean, val enlarged: Boolean, val clickable: Boolean)

/** Saf karar mantığı: hangi kipte, hangi bağlantı durumunda çevrimdışı tahmin kullanılır, düğme nasıl görünür. */
object OfflinePolicy {
    /** Düğme kipinde kullanıcı açtıysa ağa hiç sorulmadan çevrimdışı tahminle yanıt verilir. */
    fun serveBeforeNetwork(config: OfflineConfig) = config.mode == OfflineMode.BUTTON && config.manualOn

    /** Otomatik kipte, yalnız bağlantı/servis kesintisinde (anahtar ve hız sınırı hatalarında değil). */
    fun serveAfterFailure(config: OfflineConfig, kind: FailureKind) = config.mode == OfflineMode.AUTO && kind.offlineEligible

    /** Bu yapılandırmada çevrimdışı yanıtlar bir yerde mümkün mü (arka plan model indirmesi için de). */
    fun enabled(config: OfflineConfig) = config.mode != OfflineMode.OFF

    /**
     * [connectionBad]: ViewModel'in ardışık hata sinyali. [autoServing]: otomatik kipte son yanıt çevrimdışıydı.
     * Düğme kipi: kullanıcı açıksa "açık"; bağlantı kötüyken ve kapalıyken büyür (dikkat çeker).
     * Otomatik kip: yalnız durum göstergesi (dokununca açıklama); büyümez.
     */
    fun button(config: OfflineConfig, connectionBad: Boolean, autoServing: Boolean = false): OfflineButtonState = when (config.mode) {
        OfflineMode.OFF -> OfflineButtonState(visible = false, on = false, enlarged = false, clickable = false)
        OfflineMode.BUTTON -> OfflineButtonState(true, config.manualOn, enlarged = connectionBad && !config.manualOn, clickable = true)
        OfflineMode.AUTO -> OfflineButtonState(true, autoServing, enlarged = false, clickable = true)
    }
}
