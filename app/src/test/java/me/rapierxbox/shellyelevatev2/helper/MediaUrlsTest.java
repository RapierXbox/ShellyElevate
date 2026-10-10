package me.rapierxbox.shellyelevatev2.helper;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MediaUrlsTest {
    private static final String TTS = "https://10.10.0.20:8123/api/tts_proxy/abc.mp3?authSig=x";

    @Test
    public void dashboardSchemeWinsOnSameHostAndPort() {
        assertEquals("http://10.10.0.20:8123/api/tts_proxy/abc.mp3?authSig=x",
                MediaUrls.matchScheme(TTS, "http://10.10.0.20:8123/lovelace/0"));
    }

    @Test
    public void httpsDashboardUpgradesHttpMedia() {
        assertEquals("https://ha.local:8123/a.mp3",
                MediaUrls.matchScheme("http://ha.local:8123/a.mp3", "https://HA.local:8123"));
    }

    @Test
    public void otherHostStaysUntouched() {
        assertEquals(TTS, MediaUrls.matchScheme(TTS, "http://10.10.0.21:8123"));
    }

    @Test
    public void otherPortStaysUntouched() {
        String url = "https://ha.example.com/a.mp3";
        assertEquals(url, MediaUrls.matchScheme(url, "http://ha.example.com"));
    }

    @Test
    public void sameSchemeOrNoDashboardStaysUntouched() {
        assertEquals(TTS, MediaUrls.matchScheme(TTS, "https://10.10.0.20:8123"));
        assertEquals(TTS, MediaUrls.matchScheme(TTS, ""));
        assertEquals("file:///a.mp3", MediaUrls.matchScheme("file:///a.mp3", "http://10.10.0.20:8123"));
    }
}
