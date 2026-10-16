package me.rapierxbox.shellyelevatev2.voice.speaker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

public class SpeakerMatcherTest {
    private static float[] v(float... x) {
        return SpeakerMatcher.normalize(x);
    }

    @Test
    public void nobodyEnrolled() {
        assertNull(SpeakerMatcher.best(Collections.emptyMap(), v(1, 0), 0.5f));
    }

    @Test
    public void bestSpeakerWins() {
        Map<String, List<float[]>> s = new LinkedHashMap<>();
        s.put("a", Arrays.asList(v(1, 0, 0), v(0.9f, 0.1f, 0)));
        s.put("b", Arrays.asList(v(0, 1, 0)));
        SpeakerMatcher.Match m = SpeakerMatcher.best(s, v(1, 0.05f, 0), 0.5f);
        assertEquals("a", m.id);
        assertTrue(m.known);
    }

    @Test
    public void belowThresholdIsUnknownWithAGuess() {
        Map<String, List<float[]>> s = new LinkedHashMap<>();
        s.put("a", Arrays.asList(v(1, 0)));
        SpeakerMatcher.Match m = SpeakerMatcher.best(s, v(1, 1.5f), 0.7f);
        assertEquals("a", m.id);
        assertFalse(m.known);
    }

    @Test
    public void twoCloseSpeakersAreUnknown() {
        Map<String, List<float[]>> s = new LinkedHashMap<>();
        s.put("a", Arrays.asList(v(1, 0.02f)));
        s.put("b", Arrays.asList(v(1, -0.02f)));
        assertFalse(SpeakerMatcher.best(s, v(1, 0), 0.5f).known);
    }

    @Test
    public void scoreIsTheMeanOfTheBestThree() {
        List<float[]> samples = Arrays.asList(v(1, 0), v(1, 0), v(1, 0), v(0, 1), v(0, 1));
        assertEquals(1f, SpeakerMatcher.score(samples, v(1, 0)), 1e-5);
        assertEquals(0.5f, SpeakerMatcher.score(Arrays.asList(v(1, 0), v(0, 1)), v(1, 0)), 1e-5);
    }
}
