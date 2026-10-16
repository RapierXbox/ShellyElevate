package me.rapierxbox.shellyelevatev2.voice.speaker;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpeakerStoreTest {
    @Test
    public void plainIdsPass() {
        assertTrue(SpeakerStore.validId("6f1c2b8e-1d2a-4c1e-9b7a-0a1b2c3d4e5f"));
        assertTrue(SpeakerStore.validId("a1b2c3d4"));
    }

    @Test
    public void pathsAreRefused() {
        assertFalse(SpeakerStore.validId("../../shared_prefs"));
        assertFalse(SpeakerStore.validId("a/b"));
        assertFalse(SpeakerStore.validId(""));
        assertFalse(SpeakerStore.validId(null));
    }
}
