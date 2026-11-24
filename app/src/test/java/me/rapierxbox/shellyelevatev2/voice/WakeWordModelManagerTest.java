package me.rapierxbox.shellyelevatev2.voice;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;

public class WakeWordModelManagerTest {
    @Test
    public void keepsTheHighestVersionFolderPerWord() {
        assertEquals(
                new HashSet<>(Arrays.asList("microWakeWordsV6/hey_glados.tflite", "microWakeWordsV9/alexa.tflite")),
                new HashSet<>(WakeWordModelManager.newestPerStem(Arrays.asList(
                        "microWakeWordsV1/hey_glados.tflite",
                        "microWakeWordsV6/hey_glados.tflite",
                        "microWakeWordsV3/hey_glados.tflite",
                        "microWakeWordsV2/alexa.tflite",
                        "microWakeWordsV9/alexa.tflite"))));
    }

    @Test
    public void twoDigitVersionsBeatSingleDigit() {
        assertEquals(
                Arrays.asList("microWakeWordsV10/hey_glados.tflite"),
                WakeWordModelManager.newestPerStem(Arrays.asList(
                        "microWakeWordsV9/hey_glados.tflite",
                        "microWakeWordsV10/hey_glados.tflite")));
    }
}
