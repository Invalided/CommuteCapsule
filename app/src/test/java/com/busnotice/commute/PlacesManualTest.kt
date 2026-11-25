package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 手动设置的家 / 公司的存储格式（数值为虚构） */
class PlacesManualTest {
    @Test fun roundTrip() {
        val raw = Places.formatManual(1.25, -2.5)
        assertEquals(1.25 to -2.5, Places.parseManual(raw))
    }

    @Test fun malformedIsIgnored() {
        assertNull(Places.parseManual(""))
        assertNull(Places.parseManual("1.0"))
        assertNull(Places.parseManual("a,b"))
        assertNull(Places.parseManual("NaN,1.0"))
        assertNull(Places.parseManual("1.0,2.0,3.0"))
    }
}
