package com.uzaylikafasi.marasulasim.data

/**
 * Bağlantı kalitesi ölçümü (çevrimdışı düğmesinin "büyüme" kararı). Tek bir başarılı isteğin kötü bağlantıyı hemen "iyi" yapmaması
 * ve yavaş ama başarılı yanıtların da zayıf sayılması için son örneklerin penceresine bakar. Saf mantık: zaman dışarıdan verilir.
 */
class ConnectionQuality(private val windowMs: Long = 180_000, private val maxSamples: Int = 8, private val slowMs: Long = 4_000) {
    private class Sample(val at: Long, val ok: Boolean, val millis: Long)
    private val samples = ArrayDeque<Sample>()

    /** [ok]: ağdan taze yanıt geldi (eski kayıtla yanıtlama ya da hata değil). [millis]: isteğin süresi. */
    @Synchronized fun record(ok: Boolean, millis: Long, now: Long) {
        samples.addLast(Sample(now, ok, millis))
        while (samples.size > maxSamples) samples.removeFirst()
    }

    @Synchronized fun isBad(now: Long): Boolean {
        val recent = samples.filter { now - it.at <= windowMs }
        if (recent.isEmpty()) return false
        fun fast(s: Sample) = s.ok && s.millis < slowMs
        if (recent.size >= 3 && recent.takeLast(3).all(::fast)) return false
        if (recent.size >= 2 && recent.takeLast(2).none { it.ok }) return true
        return recent.size >= 3 && recent.count { !fast(it) } * 2 >= recent.size
    }

    @Synchronized fun reset() = samples.clear()

    companion object {
        /** Bu hızın altındaki indirme tahmini (kbps) zayıf bağlantı sayılır. */
        const val WEAK_KBPS = 600
        /** Cihazın ağ durumu: bağlı değil, doğrulanmamış (internet yok) ya da çok düşük bant genişliği. Hiç istek yapılmadan da düğmeyi büyütür. */
        fun weakLink(connected: Boolean, validated: Boolean, downKbps: Int) = !connected || !validated || downKbps in 1..WEAK_KBPS
    }
}
