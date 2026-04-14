package me.rapierxbox.shellyelevatev2;

import android.os.Build;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.Locale;

public enum DeviceModel {
    //V1
    STARGATE("Stargate", "Shelly Wall Display", "SAWD-0A1XX10EU1", false, false, -2.7d, 7.0d, 0, 1, 1), // Old One
    ATLANTIS("Atlantis", "Shelly Wall Display 2", "SAWD-1A1XX10EU1", true, false, -1.1d, 3.0d, 0, 1, 1), // New One
    PEGASUS("Pegasus",  "Shelly Wall Display X2", "SAWD-2A1XX10EU1", true, false, -2.6d, 8.0d, 0, 1, 2),

    //V2
    BLAKE("Blake", "Shelly Wall Display XL","SAWD-3A1XE10EU2", true, true, -1.2d, 10.0d, 4, 1, 2),
    MAVERICK("Maverick", "Shelly Wall Display U1", "SAWD-4A1XE10US0", true, true, 0d, 0.0d, 0, 1, 1), // TODO: not yet available
    JENNA("Jenna", "Shelly Wall Display X2i", "SAWD-5A1XX10EU0", true, true, 0d, 0.0d, 0, 1, 2), // TODO: not yet available
    CALLY("Cally","Shelly Wall Display XLi", "SAWD-6A1XX10EU0", true, true, 0d, 0.0d, 4, 1, 2), // TODO: not yet available
    ;

    private final String model;
    public final String modelName;
    public final String friendlyName;
    public final boolean hasProximitySensor;
    public final boolean hasPowerButton;
    public final double temperatureOffset;
    public final double humidityOffset;
    public final int buttons;
    public final int inputs;
    public final int relays;

    DeviceModel(String model, String friendlyName, String modelName, boolean hasProximitySensor, boolean hasPowerButton, double temperatureOffset, double humidityOffset, int buttons, int inputs, int relays) {
        this.model = model;
        this.modelName = modelName;
        this.friendlyName = friendlyName;
        this.hasProximitySensor = hasProximitySensor;
        this.hasPowerButton = hasPowerButton;
        this.temperatureOffset = temperatureOffset;
        this.humidityOffset = humidityOffset;
        this.buttons = buttons;
        this.inputs = inputs;
        this.relays = relays;
    }

    public static DeviceModel getReportedDevice(){
        final String reportedModel = normalize(Build.MODEL);
        final String reportedDevice = normalize(Build.DEVICE);
        final String reportedProduct = normalize(Build.PRODUCT);

        return Arrays.stream(DeviceModel.values())
                .filter(deviceType -> matches(deviceType, reportedModel, reportedDevice, reportedProduct))
                .findFirst()
                .orElse(DeviceModel.STARGATE);
    }

    private static boolean matches(DeviceModel deviceType, String reportedModel, String reportedDevice, String reportedProduct) {
        String enumModel = normalize(deviceType.model);
        String enumModelName = normalize(deviceType.modelName);

        return enumModel.equals(reportedModel)
                || enumModelName.equals(reportedModel)
                || (!reportedDevice.isEmpty() && enumModelName.equals(reportedDevice))
                || (!reportedProduct.isEmpty() && enumModelName.equals(reportedProduct));
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the /dev/input/event* device path used for gpio-based proximity events, or null
     * if this model exposes proximity via the standard Android SensorManager.
     */
    public String getGpioProximityEventPath() {
        switch (this) {
            case JENNA: return "/dev/input/event4";
            default: return null;
        }
    }

    @NonNull
    @Override
    public String toString() {
        //We are using this with the adapter in SettingsActivity
        return modelName;
    }
}
