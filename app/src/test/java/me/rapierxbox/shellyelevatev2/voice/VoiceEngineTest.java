package me.rapierxbox.shellyelevatev2.voice;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VoiceEngineTest {

    @Test
    public void phraseOfModelIds() {
        assertEquals("Okay Nabu", VoiceEngine.phraseOf("okay_nabu"));
        assertEquals("Hey Jarvis", VoiceEngine.phraseOf("hey_jarvis_v2"));
        assertEquals("Alexa", VoiceEngine.phraseOf("alexa"));
    }

    @Test
    public void phraseOfKeepsIdWithoutWords() {
        assertEquals("v2", VoiceEngine.phraseOf("v2"));
    }
}
