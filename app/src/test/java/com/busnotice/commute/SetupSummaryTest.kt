package com.busnotice.commute

import com.busnotice.commute.ui.PermGroup
import com.busnotice.commute.ui.SetupSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设置检查：分组计数与「一次开启」的权限清单 */
class SetupSummaryTest {
    private fun counts(vararg items: Pair<PermGroup, Boolean>) = SetupSummary.counts(items.toList())

    @Test fun infoItemsAreNotCounted() {
        val c = counts(PermGroup.REQUIRED to true, PermGroup.INFO to false, PermGroup.INFO to true)
        assertEquals(1, c.ok)
        assertEquals(1, c.total)
        assertEquals(0, c.missing)
        assertFalse(c.urgent)
    }

    @Test fun vendorItemsCountAsMissingButAreNotUrgent() {
        val c = counts(PermGroup.REQUIRED to true, PermGroup.VENDOR to false, PermGroup.VENDOR to true)
        assertEquals(2, c.ok)
        assertEquals(3, c.total)
        assertEquals(1, c.missing)
        assertFalse(c.urgent)
    }

    @Test fun optionalItemsDoNotCountAsMissing() {
        val c = counts(PermGroup.REQUIRED to true, PermGroup.OPTIONAL to false, PermGroup.OPTIONAL to false)
        assertEquals(1, c.ok)
        assertEquals(3, c.total)
        assertEquals(0, c.missing)
    }

    @Test fun missingRequiredIsUrgent() {
        val c = counts(PermGroup.REQUIRED to false, PermGroup.REQUIRED to true, PermGroup.VENDOR to false, PermGroup.OPTIONAL to false)
        assertEquals(2, c.missing)
        assertTrue(c.urgent)
    }

    @Test fun batchContainsOnlyMissingForegroundPermissions() {
        val none = SetupSummary.batch({ false }, sdk = 36)
        assertEquals(listOf(SetupSummary.FINE, SetupSummary.COARSE, SetupSummary.NOTIFY, SetupSummary.ACTIVITY), none)
        assertTrue(SetupSummary.batch({ true }, sdk = 36).isEmpty())
        val onlyActivity = SetupSummary.batch({ it != SetupSummary.ACTIVITY }, sdk = 36)
        assertEquals(listOf(SetupSummary.ACTIVITY), onlyActivity)
    }

    @Test fun batchNeverContainsSeparatelyGrantedPermissions() {
        val all = SetupSummary.batch({ false }, sdk = 36)
        assertFalse(all.contains("android.permission.ACCESS_BACKGROUND_LOCATION"))
        assertFalse(all.contains("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
    }

    @Test fun notificationPermissionOnlyOnAndroid13AndLater() {
        assertFalse(SetupSummary.batch({ false }, sdk = 32).contains(SetupSummary.NOTIFY))
        assertTrue(SetupSummary.batch({ false }, sdk = 33).contains(SetupSummary.NOTIFY))
    }

    @Test fun coarseOnlyStillRequestsFineLocation() {
        val b = SetupSummary.batch({ it == SetupSummary.COARSE }, sdk = 36)
        assertTrue(b.contains(SetupSummary.FINE))
        assertTrue(b.contains(SetupSummary.COARSE))
    }
}
