package me.rapierxbox.shellyelevatev2;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class DeviceHelper {

    public static boolean getRelay() {
        return readFileContent("/sys/devices/platform/leds/red_enable").contains("1");
    }
    public static void setRelay(boolean state) {
        writeFileContent("/sys/devices/platform/leds/green_enable", state ? "1" : "0");
        writeFileContent("/sys/devices/platform/leds/red_enable", state ? "1" : "0");
    }
    public static double getTemperature() {
        String[] tempSplit = readFileContent("/sys/devices/platform/sht3x-user/sht3x_access").split(":");
        double temp = (((Double.parseDouble(tempSplit[1]) * 175.0) / 65535.0) - 45.0) - 1.1;
        return Math.round(temp * 10.0) / 10.0;
    }
    public static double getHumidity() {
        String[] humiditySplit = readFileContent("/sys/devices/platform/sht3x-user/sht3x_access").split(":");
        double humidity = ((Double.parseDouble(humiditySplit[0]) * 100.0) / 65535.0) + 18.0;
        return Math.round(humidity);
    }
    private static String readFileContent(String filePath) {
        StringBuilder content = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                content.append(line).append("\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return content.toString();
    }

    private static void writeFileContent(String filePath, String content) {
        try (FileWriter writer = new FileWriter(filePath)) {
            writer.write(content);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
