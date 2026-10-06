package me.rapierxbox.shellyelevatev2.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppUpdaterTest {

    @Test
    public void onlyTheCurrentVersionSchemeCounts() {
        assertTrue(AppUpdater.isCurrentScheme("3.26279.1423"));
        // early tags used other widths and would look newer than everything
        assertFalse(AppUpdater.isCurrentScheme("3.2026111.1918"));
        assertFalse(AppUpdater.isCurrentScheme("3.202604.0632"));
        assertFalse(AppUpdater.isCurrentScheme("2.4.0"));
    }

    @Test
    public void preReleaseChannelStillTakesANewerMainRelease() {
        AppUpdater.ReleaseInfo stable = new AppUpdater.ReleaseInfo("3.26280.0900", "s", false);
        AppUpdater.ReleaseInfo olderPre = new AppUpdater.ReleaseInfo("3.26279.1458", "p", true);
        AppUpdater.ReleaseInfo newerPre = new AppUpdater.ReleaseInfo("3.26281.1200", "p2", true);
        assertEquals(stable, AppUpdater.choose(stable, olderPre));
        assertEquals(newerPre, AppUpdater.choose(stable, newerPre));
        // stable channel passes no pre release
        assertEquals(stable, AppUpdater.choose(stable, null));
        assertEquals(newerPre, AppUpdater.choose(null, newerPre));
    }

    @Test
    public void newerComparesEachPart() {
        assertTrue(AppUpdater.isNewer("3.26278.2001", "3.26279.1423"));
        assertTrue(AppUpdater.isNewer("3.26279.0900", "3.26279.1423"));
        assertFalse(AppUpdater.isNewer("3.26279.1423", "3.26279.1423"));
        assertFalse(AppUpdater.isNewer("3.26279.1423", "3.26278.2001"));
    }
}
