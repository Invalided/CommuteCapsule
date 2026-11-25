package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 取当前位置失败原因的判定 */
class LocCheckTest {
    @Test fun noPermissionComesFirst() {
        assertEquals(LocFailure.NO_PERMISSION, LocCheck.preflight(false, false, locationEnabled = true, anyProviderEnabled = true))
        assertEquals(LocFailure.NO_PERMISSION, LocCheck.preflight(false, false, locationEnabled = false, anyProviderEnabled = false))
    }

    @Test fun eitherPermissionIsEnough() {
        assertNull(LocCheck.preflight(true, false, locationEnabled = true, anyProviderEnabled = true))
        assertNull(LocCheck.preflight(false, true, locationEnabled = true, anyProviderEnabled = true))
    }

    @Test fun locationSwitchOffOrNoProvider() {
        assertEquals(LocFailure.LOCATION_OFF, LocCheck.preflight(true, true, locationEnabled = false, anyProviderEnabled = true))
        assertEquals(LocFailure.LOCATION_OFF, LocCheck.preflight(true, true, locationEnabled = true, anyProviderEnabled = false))
    }

    @Test fun grantedWhenFineOrCoarseGranted() {
        assertEquals(LocPermOutcome.GRANTED, LocCheck.afterRequest(true, true, shouldShowRationale = false))
        assertEquals(LocPermOutcome.GRANTED, LocCheck.afterRequest(false, true, shouldShowRationale = false))
        assertEquals(LocPermOutcome.GRANTED, LocCheck.afterRequest(true, false, shouldShowRationale = true))
    }

    @Test fun deniedWithRationaleCanAskAgain() {
        assertEquals(LocPermOutcome.DENIED, LocCheck.afterRequest(false, false, shouldShowRationale = true))
    }

    @Test fun deniedWithoutRationaleIsPermanent() {
        assertEquals(LocPermOutcome.DENIED_PERMANENTLY, LocCheck.afterRequest(false, false, shouldShowRationale = false))
    }
}
