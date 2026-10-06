package me.rapierxbox.shellyelevatev2.api;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import me.rapierxbox.shellyelevatev2.bluetooth.BleScanner;

public class BleChannelTest {

    private static BleScanner.RawAd ad(long address, int type, int rssi, byte[] data) {
        return new BleScanner.RawAd(address, type, rssi, data, null);
    }

    @Test
    public void encodesRecordLayout() {
        List<byte[]> frames = BleChannel.encode(Arrays.asList(
                ad(0xAABBCCDDEEFFL, 1, -70, new byte[]{0x02, 0x01, 0x06}),
                ad(0x112233445566L, 0, -1, new byte[0])));
        assertEquals(1, frames.size());
        byte[] expected = {
                (byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD, (byte) 0xEE, (byte) 0xFF,
                0x01, (byte) 0xBA, 0x03, 0x02, 0x01, 0x06,
                0x11, 0x22, 0x33, 0x44, 0x55, 0x66,
                0x00, (byte) 0xFF, 0x00,
        };
        assertArrayEquals(expected, frames.get(0));
    }

    @Test
    public void unknownAddressTypesBecomePublic() {
        byte[] frame = BleChannel.encode(Collections.singletonList(ad(1, 3, 0, new byte[]{0x01}))).get(0);
        assertEquals(0, frame[6]);
    }

    @Test
    public void skipsMissingAndOversizedData() {
        List<byte[]> frames = BleChannel.encode(Arrays.asList(
                ad(1, 0, 0, null),
                ad(2, 0, 0, new byte[256]),
                ad(3, 0, 0, new byte[]{0x05})));
        assertEquals(1, frames.size());
        assertEquals(10, frames.get(0).length);
        assertEquals(3, frames.get(0)[5]);
    }

    @Test
    public void splitsLargeBatches() {
        List<BleScanner.RawAd> ads = new ArrayList<>();
        // 264 bytes per record so 31 fit into one 8 KB frame
        for (int i = 0; i < 40; i++) ads.add(ad(i, 0, -50, new byte[255]));
        List<byte[]> frames = BleChannel.encode(ads);
        assertEquals(2, frames.size());
        int records = 0;
        for (byte[] frame : frames) {
            assertTrue(frame.length <= BleChannel.MAX_FRAME_BYTES);
            assertEquals(0, frame.length % 264);
            records += frame.length / 264;
        }
        assertEquals(40, records);
    }

    @Test
    public void emptyBatchHasNoFrames() {
        assertTrue(BleChannel.encode(Collections.emptyList()).isEmpty());
    }
}
