package me.rapierxbox.shellyelevatev2.stes;

public enum StesCommand {
    GET_VERSION(0x30),
    WRITE_VPORT(0x40),
    READ_VPORT(0x41),
    SET_LRELAY(0x50),
    RESET_LRELAY(0x51),
    READ_LRELAY(0x52),
    GET_STATUS(0x60),
    SET_DIMMER(0x61),
    SET_DIMMER_CLR(0x62),
    GET_CONFIG(0x63),
    SET_CONFIG(0x64),
    POWER_METER(0x65),
    CALIBRATE(0x66),
    RESET_MCU(0x76);

    public final byte value;

    StesCommand(int v) {
        this.value = (byte) v;
    }
}
