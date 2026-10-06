package me.rapierxbox.shellyelevatev2.helper;

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
    public void newerComparesEachPart() {
        assertTrue(AppUpdater.isNewer("3.26278.2001", "3.26279.1423"));
        assertTrue(AppUpdater.isNewer("3.26279.0900", "3.26279.1423"));
        assertFalse(AppUpdater.isNewer("3.26279.1423", "3.26279.1423"));
        assertFalse(AppUpdater.isNewer("3.26279.1423", "3.26278.2001"));
    }
}
