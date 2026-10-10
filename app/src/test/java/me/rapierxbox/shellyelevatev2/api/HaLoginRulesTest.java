package me.rapierxbox.shellyelevatev2.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HaLoginRulesTest {

    @Test
    public void originMatchesLocationOrigin() {
        assertEquals("http://10.0.30.5:8123", HaLoginRules.originOf("http://10.0.30.5:8123/lovelace/0?kiosk"));
        assertEquals("https://ha.example.org", HaLoginRules.originOf("HTTPS://HA.Example.org:443/"));
        assertEquals("http://ha.local", HaLoginRules.originOf("http://ha.local:80"));
        assertEquals("http://[fd00::1]:8123", HaLoginRules.originOf("http://[fd00::1]:8123/"));
    }

    @Test
    public void originRejectsOtherSchemes() {
        assertNull(HaLoginRules.originOf(null));
        assertNull(HaLoginRules.originOf(""));
        assertNull(HaLoginRules.originOf("file:///android_asset/offline.html"));
        assertNull(HaLoginRules.originOf("javascript:alert(1)"));
        assertNull(HaLoginRules.originOf("not a url"));
    }

    @Test
    public void clientIdHasTrailingSlash() {
        assertEquals("http://10.0.30.5:8123/", HaLoginRules.clientIdFor("http://10.0.30.5:8123"));
    }

    @Test
    public void authorizePageOnlyOnTheOrigin() {
        String origin = "http://10.0.30.5:8123";
        assertTrue(HaLoginRules.isAuthorizePage(
                "http://10.0.30.5:8123/auth/authorize?response_type=code&client_id=x", origin));
        assertFalse(HaLoginRules.isAuthorizePage("http://10.0.30.5:8123/lovelace/0", origin));
        assertFalse(HaLoginRules.isAuthorizePage("http://10.0.30.6:8123/auth/authorize", origin));
        assertFalse(HaLoginRules.isAuthorizePage("http://10.0.30.5:8124/auth/authorize", origin));
        assertFalse(HaLoginRules.isAuthorizePage("http://10.0.30.5:8123/auth/authorize", null));
    }

    @Test
    public void refusedOnlyRightAfterAHandOver() {
        assertFalse(HaLoginRules.refused(0, 5_000));
        assertTrue(HaLoginRules.refused(10_000, 12_000));
        assertFalse(HaLoginRules.refused(10_000, 10_000 + HaLoginRules.REFUSED_WINDOW_MS));
    }

    @Test
    public void jsStringEscapesEverythingThatCouldBreakOut() {
        assertEquals("\"a\\\"b\\\\c\\n\\u003c/script\\u003e\\u2028\"",
                HaLoginRules.jsString("a\"b\\c\n</script> "));
    }

    @Test
    public void loginScriptChecksTheOriginAndStoresTheTokens() {
        String script = HaLoginRules.loginScript("http://ha:8123", "http://ha:8123/", "tok\"en", "http://ha:8123/lovelace/0");
        assertTrue(script.contains("if(location.protocol+'//'+location.host!==\"http://ha:8123\")return;"));
        assertTrue(script.contains("hassUrl:\"http://ha:8123\""));
        assertTrue(script.contains("clientId:\"http://ha:8123/\""));
        assertTrue(script.contains("refresh_token:\"tok\\\"en\""));
        assertTrue(script.contains("expires:0"));
        assertTrue(script.contains("location.replace(\"http://ha:8123/lovelace/0\")"));
    }

    @Test
    public void logoutScriptRemovesTheTokens() {
        String script = HaLoginRules.logoutScript("http://ha:8123");
        assertTrue(script.contains("localStorage.removeItem('hassTokens')"));
        assertTrue(script.contains("!==\"http://ha:8123\")return;"));
    }
}
