package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 保活体检：品牌识别与各品牌的设置项 */
class KeepaliveTest {
    @Test fun brandFromManufacturer() {
        assertEquals(Keepalive.Brand.OPPO, Keepalive.brand("OPPO", "OPPO"))
        assertEquals(Keepalive.Brand.OPPO, Keepalive.brand("OnePlus", "OnePlus"))
        assertEquals(Keepalive.Brand.OPPO, Keepalive.brand("realme", "realme"))
        assertEquals(Keepalive.Brand.XIAOMI, Keepalive.brand("Xiaomi", "Redmi"))
        assertEquals(Keepalive.Brand.HONOR, Keepalive.brand("HONOR", "HONOR"))
        assertEquals(Keepalive.Brand.HUAWEI, Keepalive.brand("HUAWEI", "HUAWEI"))
        assertEquals(Keepalive.Brand.VIVO, Keepalive.brand("vivo", "iQOO"))
        assertEquals(Keepalive.Brand.SAMSUNG, Keepalive.brand("samsung", "samsung"))
        assertEquals(Keepalive.Brand.OTHER, Keepalive.brand("Google", "google"))
    }

    @Test fun everyBrandHasSteps() {
        Keepalive.Brand.values().forEach { b ->
            val ids = mutableSetOf<String>()
            val steps = Keepalive.steps(b)
            assertTrue(b.name, steps.isNotEmpty())
            steps.forEach { s ->
                assertTrue("duplicate ${s.id}", ids.add(s.id))
                assertTrue(s.search.isNotBlank())
            }
        }
    }

    @Test fun oppoStartsWithFullBackgroundBehaviour() {
        val first = Keepalive.steps(Keepalive.Brand.OPPO).first()
        assertEquals("oppo_bg", first.id)
        assertTrue(first.targets.single().packageUri)
    }
}
