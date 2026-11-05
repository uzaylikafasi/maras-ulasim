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
}
