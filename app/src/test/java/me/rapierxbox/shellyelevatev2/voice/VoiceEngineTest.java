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
    public void phraseOfDropsTheRepoFolder() {
        assertEquals("Hey Glados", VoiceEngine.phraseOf("hey_glados_microWakeWordsV6"));
        assertEquals("Okay Nabu", VoiceEngine.phraseOf("okay_nabu_models_v2"));
        assertEquals("Okay Computer", VoiceEngine.phraseOf("okay_computer_models_v2_experiments"));
    }

    @Test
    public void phraseOfKeepsIdWithoutWords() {
        assertEquals("v2", VoiceEngine.phraseOf("v2"));
    }
}
