package me.rapierxbox.shellyelevatev2;

import android.os.Build;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.Locale;

public enum DeviceModel {

    // V1
    STARGATE(new Config("Stargate", "Shelly Wall Display",    "SAWD-0A1XX10EU1")
            .offsets(-2.7, 7.0).io(0, 1, 1)),
    ATLANTIS(new Config("Atlantis", "Shelly Wall Display 2",  "SAWD-1A1XX10EU1")
            .proximity().offsets(-1.1, 3.0).io(0, 1, 1)),
    PEGASUS (new Config("Pegasus",  "Shelly Wall Display X2", "SAWD-2A1XX10EU1")
            .proximity().offsets(-2.6, 8.0).io(0, 1, 2)),

    // V2
    BLAKE   (new Config("Blake",    "Shelly Wall Display XL",  "SAWD-3A1XE10EU2")
            .proximity().powerButton().offsets(-1.2, 7.0).io(4, 1, 2).invertRelay()
            .initRelay("cloud.shelly.blake.relay")
            .inputEvents("/dev/input/event3", "/dev/input/event5", "/dev/input/event0", "/dev/input/event4")),
    MAVERICK(new Config("Maverick", "Shelly Wall Display U1",  "SAWD-4A1XE10US0")
            .proximity().powerButton().io(0, 1, 1).panelMinBacklight(5)
            .initRelay("cloud.shelly.maverick.relay1", "cloud.shelly.maverick.relay2")),
    JENNA   (new Config("Jenna",    "Shelly Wall Display X2i", "SAWD-5A1XX10EU0")
            .proximity().invertProximity().powerButton().io(0, 1, 2).panelMinBacklight(3)
            // X2i (SKU SAWD-5A1XX10EU0): direct sysfs writes to the backlight node are denied
            // with EACCES; brightness is controlled via Android Settings.System instead.
            // Android PowerManager is used for sleep/wake because keyevent-26 is more reliable
            // than brightness=0 for actually blanking the panel on this hardware.
            .androidBrightness().androidPowerManager()
            .initRelay("cloud.shelly.jenna.relay1", "cloud.shelly.jenna.relay2")
            // event4 is JENNA's proximity gpio_keys node; event5/event7 carry the regular keys.
            // NOTE: JENNA's proximity sensor reports ~1 cm when an object is detected and 0 cm
            // when no object is detected, which is the inverse of the standard convention
            // (small value = near).  The invertProximity flag causes DeviceSensorManager to
            // mirror the raw value (normalized = max - raw) before broadcasting so all
            // downstream consumers see the standard near-is-small, far-is-large semantics.
            .inputEvents("/dev/input/event4", "/dev/input/event5", "/dev/input/event7")),
    CALLY   (new Config("Cally",    "Shelly Wall Display XLi", "SAWD-6A1XX10EU0")
            .proximity().powerButton().io(4, 1, 2).panelMinBacklight(3)
            .initRelay("cloud.shelly.cally.relay1", "cloud.shelly.cally.relay2")
            .inputEvents("/dev/input/event3", "/dev/input/event5")),
    DAYNA   (new Config("Dayna",    "Shelly Wall Display D1",  "SAWD-6A0XX0EU0")
            .proximity().powerButton().io(0, 0, 0).panelMinBacklight(3)),
    ;

    public final String  displayName;
    public final String  sku;
    public final boolean hasProximitySensor;
    public final boolean hasPowerButton;
    public final double  temperatureOffset;
    public final double  humidityOffset;
    public final int     buttons;
    public final int     inputs;
    public final int     relays;
    public final boolean invertRelay;
    /**
     * When true the hardware reports proximity with inverted polarity: the raw
     * sensor value is large when an object is near and small (0) when no object
     * is detected.  DeviceSensorManager normalizes the raw reading to the
     * standard convention (0 = near, max = far) before broadcasting so all
     * downstream consumers (ScreenSaverManager, MQTT, JS interface, HTTP API)
     * do not need to be aware of the hardware quirk.
     */
    public final boolean invertProximity;
    public final String[] initRelayScripts;
    public final String[] inputEventPaths;
    // lowest 0..255 backlight value at which the panel stays lit
    public final int     panelMinBacklight;
    /**
     * When true, brightness is controlled via Android's {@code Settings.System.SCREEN_BRIGHTNESS}
     * API rather than a direct sysfs write.  Required on devices where the backlight sysfs node
     * is not accessible to the app (e.g. X2i / JENNA, SKU SAWD-5A1XX10EU0).
     */
    public final boolean usesAndroidBrightness;
    /**
     * When true, screen sleep and wake are driven through Android's {@code PowerManager}
     * (or a root shell fallback) rather than relying solely on brightness=0.  Required on
     * the X2i / JENNA where setting brightness to 0 alone does not reliably blank the panel.
     */
    public final boolean usesAndroidPowerManager;

    private final String codename;

    DeviceModel(Config c) {
        this.codename              = c.codename;
        this.displayName           = c.displayName;
        this.sku                   = c.sku;
        this.hasProximitySensor    = c.hasProximitySensor;
        this.hasPowerButton        = c.hasPowerButton;
        this.temperatureOffset     = c.temperatureOffset;
        this.humidityOffset        = c.humidityOffset;
        this.buttons               = c.buttons;
        this.inputs                = c.inputs;
        this.relays                = c.relays;
        this.invertRelay           = c.invertRelay;
        this.invertProximity       = c.invertProximity;
        this.initRelayScripts      = c.initRelayScripts;
        this.inputEventPaths       = c.inputEventPaths;
        this.panelMinBacklight     = c.panelMinBacklight;
        this.usesAndroidBrightness = c.usesAndroidBrightness;
        this.usesAndroidPowerManager = c.usesAndroidPowerManager;
    }

    public boolean usesInitScriptRelay() {
        return initRelayScripts != null && initRelayScripts.length > 0;
    }

    public static DeviceModel getReportedDevice() {
        String reportedModel   = normalize(Build.MODEL);
        String reportedDevice  = normalize(Build.DEVICE);
        String reportedProduct = normalize(Build.PRODUCT);

        return Arrays.stream(DeviceModel.values())
                .filter(d -> matches(d, reportedModel, reportedDevice, reportedProduct))
                .findFirst()
                .orElse(DeviceModel.STARGATE);
    }

    private static boolean matches(DeviceModel d, String model, String device, String product) {
        String name = normalize(d.codename);
        String sku  = normalize(d.sku);
        return name.equals(model)
                || sku.equals(model)
                || (!device.isEmpty()  && sku.equals(device))
                || (!product.isEmpty() && sku.equals(product));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public String[] getInputEventPaths() {
        return inputEventPaths != null ? inputEventPaths : new String[0];
    }

    /** @deprecated use getInputEventPaths() */
    @Deprecated
    public String getGpioProximityEventPath() {
        return inputEventPaths != null && inputEventPaths.length > 0 ? inputEventPaths[0] : null;
    }

    @NonNull @Override
    public String toString() {
        return sku;
    }

    static final class Config {
        final String  codename, displayName, sku;
        boolean hasProximitySensor, hasPowerButton, invertRelay, invertProximity;
        boolean usesAndroidBrightness, usesAndroidPowerManager;
        double  temperatureOffset, humidityOffset;
        int     buttons, inputs, relays;
        String[] initRelayScripts;
        String[] inputEventPaths;
        int     panelMinBacklight = 1;

        Config(String codename, String displayName, String sku) {
            this.codename    = codename;
            this.displayName = displayName;
            this.sku         = sku;
        }

        Config proximity()                             { hasProximitySensor = true; return this; }
        Config invertProximity()                       { invertProximity = true;    return this; }
        Config powerButton()                           { hasPowerButton = true;     return this; }
        Config invertRelay()                           { invertRelay = true;        return this; }
        Config offsets(double temp, double humidity)   { temperatureOffset = temp; humidityOffset = humidity; return this; }
        Config io(int buttons, int inputs, int relays) { this.buttons = buttons; this.inputs = inputs; this.relays = relays; return this; }
        Config initRelay(String... scripts)            { this.initRelayScripts = scripts; return this; }
        Config inputEvents(String... paths)            { this.inputEventPaths = paths;    return this; }
        Config panelMinBacklight(int v)                { this.panelMinBacklight = v;      return this; }
        Config androidBrightness()                     { this.usesAndroidBrightness = true;     return this; }
        Config androidPowerManager()                   { this.usesAndroidPowerManager = true;   return this; }
    }
}
