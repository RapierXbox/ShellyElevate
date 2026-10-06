package me.rapierxbox.shellyelevatev2.display

import me.rapierxbox.shellyelevatev2.display.ScreenSaverReturn.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class FrontRulesTest {

    private val host = "app.MainActivity"
    private val settings = "app.SettingsActivity"
    private val clock = "app.ClockActivity"
    private val switcher = "app.SwitcherActivity"

    private fun decide(
        resumed: String?,
        start: String? = null,
        usable: Boolean = true,
        externalForMs: Long = ScreenSaverReturn.OWN_SETTLE_MS,
        waitedMs: Long = 1_000L,
        keepsInFront: Boolean = true,
        moduleInFront: Boolean? = false,
    ) = ScreenSaverReturn.decide(
        resumedClass = resumed,
        startClass = start,
        hostClass = host,
        saverClasses = setOf(clock),
        switcherClass = switcher,
        screenUsable = usable,
        externalForMs = externalForMs,
        waitedMs = waitedMs,
        keepsInFront = keepsInFront,
        moduleInFront = { moduleInFront },
    )

    @Test
    fun frontComesFromOurResumedActivityAndTheScreen() {
        assertEquals(Front.HOST, Front.of(host, host, screenUsable = true))
        assertEquals(Front.OWN_UI, Front.of(settings, host, screenUsable = true))
        assertEquals(Front.EXTERNAL, Front.of(null, host, screenUsable = true))
        assertEquals(Front.UNKNOWN, Front.of(null, host, screenUsable = false))
    }

    @Test
    fun visibleHostNeedsNothing() {
        assertEquals(Action.DONE, decide(resumed = host))
        // a host the clock saver revived over an external app counts as well
        assertEquals(Action.DONE, decide(resumed = host, start = null))
    }

    @Test
    fun otherAppInFrontAfterTheSaverIsReplaced() {
        // the bug. an app opened from the switcher stayed in front because our task top still said host
        assertEquals(Action.BRING_BACK, decide(resumed = null, start = null, externalForMs = ScreenSaverReturn.SETTLE_MS))
        // nothing can tell which app is in front so the module is relaunched anyway
        assertEquals(Action.BRING_BACK, decide(resumed = null, moduleInFront = null))
    }

    @Test
    fun externalModuleAppAlreadyInFrontStays() {
        assertEquals(Action.DONE, decide(resumed = null, moduleInFront = true))
    }

    @Test
    fun externalFrontIsGivenTimeToSettle() {
        assertEquals(Action.WAIT, decide(resumed = null, start = null, externalForMs = 0L))
        // our own screen was in front before the saver so a slow resume after wake is waited for longer
        assertEquals(Action.WAIT, decide(resumed = null, start = settings, externalForMs = ScreenSaverReturn.SETTLE_MS))
        assertEquals(Action.BRING_BACK, decide(resumed = null, start = settings, externalForMs = ScreenSaverReturn.OWN_SETTLE_MS))
    }

    @Test
    fun asleepOrLockedScreenIsWaitedForThenLeftAlone() {
        assertEquals(Action.WAIT, decide(resumed = null, usable = false, waitedMs = 2_000L))
        assertEquals(Action.DONE, decide(resumed = null, usable = false, waitedMs = ScreenSaverReturn.MAX_WAIT_MS))
    }

    @Test
    fun settingsTheUserWasInStay() {
        assertEquals(Action.DONE, decide(resumed = settings, start = settings))
    }

    @Test
    fun settingsRevivedByTheClockSaverGiveWayToTheModule() {
        assertEquals(Action.BRING_BACK, decide(resumed = settings, start = null))
    }

    @Test
    fun switcherOpenedAfterWakeStays() {
        assertEquals(Action.DONE, decide(resumed = switcher, start = null))
    }

    @Test
    fun finishingSaverActivityIsWaitedFor() {
        assertEquals(Action.WAIT, decide(resumed = clock))
        assertEquals(Action.DONE, decide(resumed = clock, waitedMs = ScreenSaverReturn.MAX_WAIT_MS))
    }

    @Test
    fun moduleThatOptedOutIsNeverForced() {
        assertEquals(Action.DONE, decide(resumed = null, keepsInFront = false))
        assertEquals(Action.DONE, decide(resumed = settings, keepsInFront = false))
    }

    @Test
    fun detectorIsOnlyAskedForAnExternalFront() {
        val result = ScreenSaverReturn.decide(
            resumedClass = host,
            startClass = null,
            hostClass = host,
            saverClasses = setOf(clock),
            switcherClass = switcher,
            screenUsable = true,
            externalForMs = 0L,
            waitedMs = 0L,
            keepsInFront = true,
            moduleInFront = { fail("detector asked while our host is resumed"); null },
        )
        assertEquals(Action.DONE, result)
    }
}
