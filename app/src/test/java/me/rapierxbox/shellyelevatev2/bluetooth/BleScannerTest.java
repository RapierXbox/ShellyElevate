package me.rapierxbox.shellyelevatev2.bluetooth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.bluetooth.le.ScanSettings;

import org.junit.Test;

public class BleScannerTest {
    @Test
    public void steadyStateIsBalancedAndTheBurstIsLowLatency() {
        assertEquals(ScanSettings.SCAN_MODE_BALANCED, BleScanner.scanModeFor(false, false));
        assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY, BleScanner.scanModeFor(false, true));
    }

    @Test
    public void lowPowerWinsOverTheBurst() {
        assertEquals(ScanSettings.SCAN_MODE_LOW_POWER, BleScanner.scanModeFor(true, true));
        assertEquals(ScanSettings.SCAN_MODE_LOW_POWER, BleScanner.scanModeFor(true, false));
    }

    // a lower duty cycle hears a quiet room less often so it may stay silent longer
    @Test
    public void silenceToleranceGrowsAsTheDutyCycleDrops() {
        long lowLatency = BleScanner.silentRestartMs(ScanSettings.SCAN_MODE_LOW_LATENCY);
        long balanced = BleScanner.silentRestartMs(ScanSettings.SCAN_MODE_BALANCED);
        long lowPower = BleScanner.silentRestartMs(ScanSettings.SCAN_MODE_LOW_POWER);
        assertEquals(45_000, lowLatency);
        assertTrue(balanced > lowLatency);
        assertTrue(lowPower > balanced);
        // the watchdog ticks every 15 s so the burst ends long before it could call a scan silent
        assertTrue(BleScanner.SCAN_BURST_MS < lowLatency);
    }
}
