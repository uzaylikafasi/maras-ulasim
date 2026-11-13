package com.uzaylikafasi.marasulasim

import com.uzaylikafasi.marasulasim.data.*
import org.junit.Assert.*
import org.junit.Test

/** Çevrimdışı düğmesinin "bağlantı zayıf" kararı: tek başarı kötüyü silmez, yavaş yanıt da zayıf sayılır. */
class ConnectionQualityTest {
    @Test fun noSamplesIsGood() = assertFalse(ConnectionQuality().isBad(0))

    @Test fun twoFailuresInARowAreBad() {
        val q = ConnectionQuality()
        q.record(false, 5_000, 1_000); q.record(false, 5_000, 2_000)
        assertTrue(q.isBad(3_000))
    }

    @Test fun singleFailureIsNotBad() {
        val q = ConnectionQuality()
        q.record(true, 300, 1_000); q.record(false, 5_000, 2_000)
        assertFalse(q.isBad(3_000))
    }

    @Test fun oneSuccessAfterFailuresDoesNotClearBad() {
        val q = ConnectionQuality()
        q.record(false, 5_000, 1_000); q.record(false, 5_000, 2_000); q.record(false, 5_000, 3_000)
        q.record(true, 300, 4_000)
        assertTrue("tek başarılı istek kötü bağlantıyı hemen silmemeli", q.isBad(5_000))
    }

    @Test fun slowButSuccessfulRequestsAreWeak() {
        val q = ConnectionQuality()
        q.record(true, 6_500, 1_000); q.record(true, 7_000, 2_000); q.record(true, 5_000, 3_000)
        assertTrue(q.isBad(4_000))
    }

    @Test fun threeFastSuccessesRecover() {
        val q = ConnectionQuality()
        q.record(false, 5_000, 1_000); q.record(false, 5_000, 2_000)
        q.record(true, 400, 3_000); q.record(true, 500, 4_000); q.record(true, 450, 5_000)
        assertFalse(q.isBad(6_000))
    }

    @Test fun fastTrafficIsNeverBad() {
        val q = ConnectionQuality()
        repeat(10) { q.record(true, 300, it * 1_000L) }
        assertFalse(q.isBad(11_000))
    }

    @Test fun deviceNetworkStateDecidesWeakLink() {
        assertTrue("ağ yok (uçak modu)", ConnectionQuality.weakLink(connected = false, validated = false, downKbps = 0))
        assertTrue("bağlı ama internet doğrulanmadı", ConnectionQuality.weakLink(true, false, 50_000))
        assertTrue("çok düşük bant genişliği", ConnectionQuality.weakLink(true, true, 300))
        assertFalse("iyi Wi-Fi", ConnectionQuality.weakLink(true, true, 50_000))
        assertFalse("bant genişliği bilinmiyor (0) ve doğrulanmış", ConnectionQuality.weakLink(true, true, 0))
    }

    @Test fun badSurvivesPollBackoffButExpires() {
        val q = ConnectionQuality()
        q.record(false, 5_000, 0); q.record(false, 5_000, 30_000)
        assertTrue("120 sn'lik yoklama aralığında hâlâ kötü", q.isBad(150_000))
        assertFalse("pencere (180 sn) dolunca eski örnekler sayılmaz", q.isBad(300_000))
    }
}
