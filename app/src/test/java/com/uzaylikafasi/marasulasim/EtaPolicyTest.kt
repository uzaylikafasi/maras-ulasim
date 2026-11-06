package com.uzaylikafasi.marasulasim

import com.uzaylikafasi.marasulasim.data.*
import org.junit.Assert.*
import org.junit.Test

class EtaPolicyTest {
    @Test fun unvalidatedPaddingRequiresAnExplicitChoice() {
        assertFalse(Settings().stopPad); assertFalse(Settings().lightPad)
        assertEquals(0,padSeconds(5,false,4,false))
        assertEquals(75,padSeconds(5,true,4,false))
        assertEquals(80,padSeconds(5,false,4,true))
    }
    @Test fun missingTimeCannotBecomePaddedEta() {
        val a = Arrival("H/1","46 H 1","Kalkış bekleniyor","Otogar")
        assertNull(secondsOf(a)?.plus(padSeconds(5,true,4,true)))
    }
    @Test fun sourceDurationIsIndependentFromStopAndLightPads() {
        // Her ikisi de kapalıyken (varsayılan) kaynak süresi hala gösterilsin; eskiden ikisi birden aynı kosula bagliydi.
        assertEquals(listOf("Kaynak 5 dk"), arrivalSourceParts("5 dk", hasBase = true, showSourceTime = true, stopsBetween = 3, stopPad = false, lightsAhead = 2, lightPad = false))
        // Kaynak süresi kapatılınca, pay bilgisi hala görünür.
        assertEquals(listOf("3 durak × $SECONDS_PER_STOP sn"), arrivalSourceParts("5 dk", hasBase = true, showSourceTime = false, stopsBetween = 3, stopPad = true, lightsAhead = 0, lightPad = false))
        assertEquals(listOf("2 ışık × $LIGHT_WAIT_SECONDS sn"), arrivalSourceParts("5 dk", hasBase = true, showSourceTime = false, stopsBetween = null, stopPad = false, lightsAhead = 2, lightPad = true))
        // Üçü birden açıksa üçü de gelir, bu sırada.
        assertEquals(listOf("Kaynak 5 dk","3 durak × $SECONDS_PER_STOP sn","2 ışık × $LIGHT_WAIT_SECONDS sn"),
            arrivalSourceParts("5 dk", hasBase = true, showSourceTime = true, stopsBetween = 3, stopPad = true, lightsAhead = 2, lightPad = true))
        // Kaynak süresi henüz okunamadıysa (hasBase=false) hiçbir şey eklenmez; hiçbir ayar açık değilse bos liste.
        assertTrue(arrivalSourceParts("Kalkış bekleniyor", hasBase = false, showSourceTime = true, stopsBetween = 3, stopPad = true, lightsAhead = 2, lightPad = true).isEmpty())
        assertTrue(arrivalSourceParts("5 dk", hasBase = true, showSourceTime = false, stopsBetween = 0, stopPad = true, lightsAhead = 0, lightPad = true).isEmpty())
    }
}
