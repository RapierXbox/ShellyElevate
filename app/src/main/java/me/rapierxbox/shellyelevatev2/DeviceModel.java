package me.rapierxbox.shellyelevatev2;

import static me.rapierxbox.shellyelevatev2.Constants.SP_DEVICE;

import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.Nullable;

import java.util.Arrays;

public enum DeviceModel {
    //V1
    STARGATE("Stargate", "SAWD-0A1XX10EU1", false, -2.7d, 7.0d), // Old One
    ATLANTIS("Atlantis", "SAWD-1A1XX10EU1", true, -1.1d, 3.0d), // New One
    PEGASUS("Pegasus", "SAWD-2A1XX10EU1", true, -2.6d, 8.0d),

    //V2
    //BLAKE("Blake", "SAWD-3A1XE10EU2", true, -2.6d, 10.0d),
    //MAVERICK("Maverick", "SAWD-4A1XE10US0", true, 0d, 0.0d),
    //JENNA("Jenna", "SAWD-5A1XX10EU0", true, 0d, 0.0d),
    ;

    private final String model;
    final String modelName;
    public final boolean hasProximitySensor;
    public final double temperatureOffset;
    public final double humidityOffset;

    DeviceModel(String model, String modelName, boolean hasProximitySensor, double temperatureOffset, double humidityOffset) {
        this.model = model;
        this.modelName = modelName;
        this.hasProximitySensor = hasProximitySensor;
        this.temperatureOffset = temperatureOffset;
        this.humidityOffset = humidityOffset;
    }

    public static DeviceModel getReportedDevice(){
        return Arrays.stream(DeviceModel.values()).filter(deviceType -> deviceType.model.equals(Build.MODEL)).findFirst().orElse(DeviceModel.STARGATE);
    }

    @Deprecated
    public static DeviceModel getDevice(@Nullable String codename) {
        return Arrays.stream(DeviceModel.values()).filter(deviceType -> deviceType.modelName.equals(codename)).findFirst().orElse(getReportedDevice());
    }

    @Deprecated
    public static DeviceModel getDevice(SharedPreferences sharedPreferences) {
        return getDevice(sharedPreferences.getString(SP_DEVICE, ""));
    }
}
