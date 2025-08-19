package me.rapierxbox.shellyelevatev2;

import static me.rapierxbox.shellyelevatev2.Constants.SP_DEVICE;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.Arrays;

public enum DeviceModel {
    STARGATE("Stargate", "SAWD-0A1XX10EU1", false, -2.7d, 7.0d), // Old One
    ATLANTIS("Atlantis", "SAWD-1A1XX10EU1", false, -1.1d, 3.0d), // New One
    PEGASUS("Pegasus", "SAWD-2A1XX10EU1", true, -2.6d, 8.0d);

    private final String codeName;
    final String boardName;
    public final boolean hasProximitySensor;
    public final double temperatureOffset;
    public final double humidityOffset;

    DeviceModel(String codeName, String boardName, boolean hasProximitySensor, double temperatureOffset, double humidityOffset) {
        this.codeName = codeName;
        this.boardName = boardName;
        this.hasProximitySensor = hasProximitySensor;
        this.temperatureOffset = temperatureOffset;
        this.humidityOffset = humidityOffset;
    }

    public static DeviceModel getDevice(@Nullable String codename) {
        return Arrays.stream(DeviceModel.values()).filter(deviceType -> deviceType.boardName.equals(codename)).findFirst().orElse(DeviceModel.STARGATE);
    }

    public static DeviceModel getDevice(SharedPreferences sharedPreferences) {
        return getDevice(sharedPreferences.getString(SP_DEVICE, ""));
    }
}
