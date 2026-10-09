package me.rapierxbox.shellyelevatev2.helper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AdbHelperTest {
    // captured on the stargate wall display with adbd on port 5555 and one adb client
    private static final String TCP6 =
            "  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"
            + "   0: 00000000000000000000000000000000:15B3 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000  2000        0 9972 1 00000000 100 0 0 10 0\n"
            + "   2: 0000000000000000FFFF0000BA1E000A:15B3 0000000000000000FFFF00001B14000A:EE91 01 00000BCC:00000000 00:00000000 00000000  2000        0 204567 3 00000000 31 4 20 3 2\n";

    @Test
    public void listeningSocketCounts() {
        assertTrue(AdbHelper.listensOn(TCP6, 5555));
    }

    @Test
    public void connectedSocketAloneDoesNotCount() {
        String connectedOnly = TCP6.replace(" 0A ", " 06 ");
        assertFalse(AdbHelper.listensOn(connectedOnly, 5555));
    }

    @Test
    public void otherPortDoesNotCount() {
        assertFalse(AdbHelper.listensOn(TCP6, 8443));
        assertFalse(AdbHelper.listensOn("", 5555));
    }

    @Test
    public void servicePortWinsOverPersist() {
        assertTrue(AdbHelper.portEnabled("5555", ""));
        assertTrue(AdbHelper.portEnabled("", "5555"));
        assertFalse(AdbHelper.portEnabled("-1", "5555"));
        assertFalse(AdbHelper.portEnabled("0", "5555"));
        assertTrue(AdbHelper.portEnabled(" 5555 ", "-1"));
    }

    @Test
    public void unsetOrJunkPortIsOff() {
        assertFalse(AdbHelper.portEnabled("", ""));
        assertFalse(AdbHelper.portEnabled("", "abc"));
    }
}
