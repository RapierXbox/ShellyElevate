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
            .proximity().powerButton().offsets(-1.2, 7.0).io(4, 1, 2).invertRelay()),
    MAVERICK(new Config("Maverick", "Shelly Wall Display U1",  "SAWD-4A1XE10US0")
            .proximity().powerButton().io(0, 1, 1)), // TODO: not yet available
    JENNA   (new Config("Jenna",    "Shelly Wall Display X2i", "SAWD-5A1XX10EU0")
            .proximity().powerButton().io(0, 1, 2)), // TODO: not yet available
    CALLY   (new Config("Cally",    "Shelly Wall Display XLi", "SAWD-6A1XX10EU0")
            .proximity().powerButton().io(4, 1, 2)), // TODO: not yet available
    DAYNA   (new Config("Dayna",    "Shelly Wall Display D1",  "SAWD-6A0XX0EU0")
            .proximity().powerButton().io(0, 0, 0)), // TODO: not yet available
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

    private final String codename;

    DeviceModel(Config c) {
        this.codename           = c.codename;
        this.displayName        = c.displayName;
        this.sku                = c.sku;
        this.hasProximitySensor = c.hasProximitySensor;
        this.hasPowerButton     = c.hasPowerButton;
        this.temperatureOffset  = c.temperatureOffset;
        this.humidityOffset     = c.humidityOffset;
        this.buttons            = c.buttons;
        this.inputs             = c.inputs;
        this.relays             = c.relays;
        this.invertRelay        = c.invertRelay;
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

    /** Returns the /dev/input/event* path for GPIO-based proximity, or null if via SensorManager. */
    public String getGpioProximityEventPath() {
        return this == JENNA ? "/dev/input/event4" : null;
    }

    @NonNull @Override
    public String toString() {
        return sku;
    }

    static final class Config {
        final String  codename, displayName, sku;
        boolean hasProximitySensor, hasPowerButton, invertRelay;
        double  temperatureOffset, humidityOffset;
        int     buttons, inputs, relays;

        Config(String codename, String displayName, String sku) {
            this.codename    = codename;
            this.displayName = displayName;
            this.sku         = sku;
        }

        Config proximity()                             { hasProximitySensor = true; return this; }
        Config powerButton()                           { hasPowerButton = true;     return this; }
        Config invertRelay()                           { invertRelay = true;        return this; }
        Config offsets(double temp, double humidity)   { temperatureOffset = temp; humidityOffset = humidity; return this; }
        Config io(int buttons, int inputs, int relays) { this.buttons = buttons; this.inputs = inputs; this.relays = relays; return this; }
    }
}
