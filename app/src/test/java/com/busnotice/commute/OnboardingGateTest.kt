package com.busnotice.commute

import com.busnotice.commute.ui.OnboardingGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 首次引导的判定（数值为虚构） */
class OnboardingGateTest {
    private val schedulingOnly = mapOf<String, Map<String, Any?>>("places" to mapOf("nextSample" to 1_700_000_000_000L))

    @Test fun freshInstallWithSchedulingKeysOnlyNeedsOnboarding() {
        assertFalse(OnboardingGate.hasUserData(hasTrips = false, prefs = schedulingOnly))
        assertFalse(OnboardingGate.hasUserData(hasTrips = false, prefs = emptyMap()))
        assertTrue(OnboardingGate.shouldShow(done = false, started = false, hasUserData = false))
    }

    @Test fun existingTripsSkipOnboarding() {
        val has = OnboardingGate.hasUserData(hasTrips = true, prefs = schedulingOnly)
        assertTrue(has)
        assertFalse(OnboardingGate.shouldShow(done = false, started = false, hasUserData = has))
    }

    @Test fun learnedPlacesSkipOnboarding() {
        val prefs = mapOf<String, Map<String, Any?>>("places" to mapOf(
            "nextSample" to 1L,
            "clusters" to """[{"k":"HOME","lat":1.23,"lng":2.34,"w":0.1,"v":3,"days":[1,2]}]""",
        ))
        assertTrue(OnboardingGate.hasUserData(hasTrips = false, prefs = prefs))
    }

    @Test fun manualPlaceSkipsOnboarding() {
        val prefs = mapOf<String, Map<String, Any?>>("places" to mapOf("manual_HOME" to "1.23,2.34"))
        assertTrue(OnboardingGate.hasUserData(hasTrips = false, prefs = prefs))
    }

    @Test fun locationSamplesCountAsUserData() {
        val prefs = mapOf<String, Map<String, Any?>>("places" to mapOf("samples" to "[[1.23,2.34,20.0,1]]"))
        assertTrue(OnboardingGate.hasUserData(hasTrips = false, prefs = prefs))
    }

    @Test fun emptyLearningContainersAreIgnored() {
        val prefs = mapOf<String, Map<String, Any?>>(
            "places" to mapOf("clusters" to "[]", "samples" to " [] ", "nightDay" to 20000L, "nextSample" to 1L),
            "learn" to mapOf("v3:t1" to ""),
        )
        assertFalse(OnboardingGate.hasUserData(hasTrips = false, prefs = prefs))
    }

    @Test fun learnedTimingCountsAsUserData() {
        assertTrue(OnboardingGate.hasUserData(false, mapOf("learn" to mapOf("v3:t1" to "20000:480"))))
        assertTrue(OnboardingGate.hasUserData(false, mapOf("learn_ride2" to mapOf("t1#0" to "12,14,13"))))
        assertTrue(OnboardingGate.hasUserData(false, mapOf("learn_bus_speed" to mapOf("t1#0" to 250f))))
    }

    @Test fun runStateAndUnknownFilesAreIgnored() {
        val prefs = mapOf<String, Map<String, Any?>>("run_state" to mapOf("trip" to "t1", "deadline" to 1L))
        assertFalse(OnboardingGate.hasUserData(hasTrips = false, prefs = prefs))
    }

    @Test fun startedOnboardingResumesUntilFinished() {
        assertTrue(OnboardingGate.shouldShow(done = false, started = true, hasUserData = true))
        assertFalse(OnboardingGate.shouldShow(done = true, started = true, hasUserData = false))
    }

    @Test fun autoSkipWithoutUserDataIsReconsidered() {
        assertTrue(OnboardingGate.shouldShow(done = true, started = false, hasUserData = false))
        assertFalse(OnboardingGate.shouldShow(done = true, started = false, hasUserData = true))
    }
}
