package com.busnotice.commute

import com.busnotice.commute.ui.CoachFlow
import com.busnotice.commute.ui.CoachPlacement
import com.busnotice.commute.ui.CoachProgress
import com.busnotice.commute.ui.CoachStep
import com.busnotice.commute.ui.CoachStore
import com.busnotice.commute.ui.NotifyAsk
import com.busnotice.commute.ui.OnboardingGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 首页聚光引导的步骤、进度与气泡位置 */
class CoachFlowTest {
    private class MapStore : CoachStore {
        val map = mutableMapOf<String, Any?>()
        override fun getBoolean(key: String) = map[key] as? Boolean ?: false
        override fun getString(key: String) = map[key] as? String
        override fun put(values: Map<String, Any?>) {
            values.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
        }
    }

    private val all: (CoachStep) -> Boolean = { true }

    @Test fun stepsAdvanceInOrderAndEndAfterLast() {
        assertEquals(CoachStep.PLACES, CoachFlow.next(CoachStep.NEW_TRIP))
        assertEquals(CoachStep.CHECKS, CoachFlow.next(CoachStep.PLACES))
        assertEquals(CoachStep.MENU, CoachFlow.next(CoachStep.CHECKS))
        assertNull(CoachFlow.next(CoachStep.MENU))
        assertFalse(CoachFlow.isLast(CoachStep.CHECKS, all))
        assertTrue(CoachFlow.isLast(CoachStep.MENU, all))
    }

    @Test fun missingTargetsAreSkipped() {
        val noPlaces: (CoachStep) -> Boolean = { it != CoachStep.PLACES }
        assertEquals(CoachStep.CHECKS, CoachFlow.firstAvailable(CoachFlow.next(CoachStep.NEW_TRIP), noPlaces))
        val onlyFirst: (CoachStep) -> Boolean = { it == CoachStep.NEW_TRIP }
        assertTrue(CoachFlow.isLast(CoachStep.NEW_TRIP, onlyFirst))
        assertNull(CoachFlow.firstAvailable(CoachFlow.next(CoachStep.NEW_TRIP), onlyFirst))
        assertNull(CoachFlow.firstAvailable(null, all))
    }

    @Test fun lastVisibleStepEndsFlowEvenIfLaterStepsAreHidden() {
        val noMenu: (CoachStep) -> Boolean = { it != CoachStep.MENU }
        assertTrue(CoachFlow.isLast(CoachStep.CHECKS, noMenu))
        assertNull(CoachFlow.firstAvailable(CoachFlow.next(CoachStep.CHECKS), noMenu))
    }

    @Test fun dotsCountOnlyVisibleSteps() {
        assertEquals(0 to 4, CoachFlow.dots(CoachStep.NEW_TRIP, all))
        assertEquals(3 to 4, CoachFlow.dots(CoachStep.MENU, all))
        val noPlaces: (CoachStep) -> Boolean = { it != CoachStep.PLACES }
        assertEquals(1 to 3, CoachFlow.dots(CoachStep.CHECKS, noPlaces))
        // 当前步总是计入
        assertEquals(0 to 1, CoachFlow.dots(CoachStep.PLACES) { false })
    }

    @Test fun progressResumesFromSavedStep() {
        val store = MapStore()
        val p = CoachProgress(store)
        assertEquals(CoachStep.NEW_TRIP, p.step)
        p.start()
        p.save(CoachStep.CHECKS)
        // 进程重启后重新读取
        val again = CoachProgress(store)
        assertTrue(again.started)
        assertFalse(again.done)
        assertEquals(CoachStep.CHECKS, again.step)
        assertTrue(OnboardingGate.shouldShow(again.done, again.started, hasUserData = true))
    }

    @Test fun unknownSavedStepStartsFromFirst() {
        val store = MapStore().apply { map[CoachProgress.KEY_STEP] = "TRIP" }
        assertEquals(CoachStep.NEW_TRIP, CoachProgress(store).step)
    }

    @Test fun finishOrSkipIsPersisted() {
        val store = MapStore()
        CoachProgress(store).apply { start(); save(CoachStep.PLACES); finish() }
        val p = CoachProgress(store)
        assertTrue(p.done)
        assertNull(store.map[CoachProgress.KEY_STEP])
        assertFalse(OnboardingGate.shouldShow(p.done, p.started, hasUserData = false))
    }

    @Test fun replayStartsAgainFromFirstStep() {
        val store = MapStore()
        CoachProgress(store).apply { start(); save(CoachStep.MENU); finish() }
        CoachProgress(store).replay()
        val p = CoachProgress(store)
        assertFalse(p.done)
        assertEquals(CoachStep.NEW_TRIP, p.step)
        assertTrue(OnboardingGate.shouldShow(p.done, p.started, hasUserData = true))
    }

    @Test fun notificationPermissionIsRequestedOnceOnAndroid13AndAbove() {
        assertTrue(NotifyAsk.shouldAsk(sdkInt = 33, granted = false, alreadyAsked = false))
        assertFalse(NotifyAsk.shouldAsk(sdkInt = 32, granted = false, alreadyAsked = false))
        assertFalse(NotifyAsk.shouldAsk(sdkInt = 34, granted = true, alreadyAsked = false))
        assertFalse(NotifyAsk.shouldAsk(sdkInt = 34, granted = false, alreadyAsked = true))
        val store = MapStore()
        val p = CoachProgress(store)
        assertFalse(p.notifyAsked)
        p.markNotifyAsked()
        assertTrue(CoachProgress(store).notifyAsked)
    }

    @Test fun bubblePrefersBelowThenAbove() {
        val below = CoachPlacement.place(100f, 150f, 200f, areaTop = 40f, areaBottom = 1000f, gap = 10f)
        assertTrue(below.below); assertEquals(160f, below.y, 0.01f)
        val above = CoachPlacement.place(800f, 900f, 300f, areaTop = 40f, areaBottom = 1000f, gap = 10f)
        assertFalse(above.below); assertEquals(490f, above.y, 0.01f)
    }

    @Test fun bubbleStaysInsideAreaWhenNeitherSideFits() {
        val r = CoachPlacement.place(300f, 400f, 500f, areaTop = 40f, areaBottom = 800f, gap = 10f)
        assertTrue(r.y >= 40f)
        assertTrue(r.y + 500f <= 800f)
        val a = CoachPlacement.place(500f, 700f, 600f, areaTop = 40f, areaBottom = 800f, gap = 10f)
        assertFalse(a.below)
        assertEquals(40f, a.y, 0.01f)
    }

    @Test fun arrowFollowsTargetWithinBubble() {
        assertEquals(300f, CoachPlacement.arrowX(300f, 16f, 600f, 28f), 0.01f)
        assertEquals(572f, CoachPlacement.arrowX(690f, 16f, 600f, 28f), 0.01f)
        assertEquals(44f, CoachPlacement.arrowX(0f, 16f, 600f, 28f), 0.01f)
    }
}
