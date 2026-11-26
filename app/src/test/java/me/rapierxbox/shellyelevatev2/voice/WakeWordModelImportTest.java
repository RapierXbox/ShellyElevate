package me.rapierxbox.shellyelevatev2.voice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class WakeWordModelImportTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void storesBothFilesUnderASafeName() throws IOException {
        File dir = tmp.newFolder();
        byte[] tflite = {1, 2, 3};
        byte[] json = "{}".getBytes();

        String id = WakeWordModelManager.importModelBytes(dir, "Hey GLaDOS.tflite", tflite, json);

        assertEquals("hey_glados", id);
        assertArrayEquals(tflite, Files.readAllBytes(new File(dir, "hey_glados.tflite").toPath()));
        assertArrayEquals(json, Files.readAllBytes(new File(dir, "hey_glados.json").toPath()));
    }

    @Test
    public void jsonIsOptional() throws IOException {
        File dir = tmp.newFolder();

        assertEquals("alexa", WakeWordModelManager.importModelBytes(dir, "alexa", new byte[]{9}, null));
        assertFalse(new File(dir, "alexa.json").exists());
    }

    @Test(expected = IOException.class)
    public void theVadNameIsRefused() throws IOException {
        WakeWordModelManager.importModelBytes(tmp.newFolder(), WakeWordDetector.VAD_MODEL_NAME + ".tflite", new byte[]{1}, null);
    }
}
