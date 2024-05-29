package me.rapierxbox.ShellyElevate;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class HttpServer extends Service {
    private NanoHTTPD server;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startHttpServer();
        return START_STICKY;
    }

    private void startHttpServer() {
        server = new NanoHTTPD(8080) {
            @Override
            public Response serve(IHTTPSession session) {
                Method method = session.getMethod();
                String uri = session.getUri();
                JSONObject jsonResponse = new JSONObject();

                try {
                    if (uri.equals("/relay")) {
                        if (method.equals(Method.GET)) {
                            boolean redEnable = readFileContent("/sys/devices/platform/leds/red_enable").contains("1");
                            jsonResponse.put("state", redEnable);
                            return newFixedLengthResponse(Response.Status.OK, "application/json", jsonResponse.toString());
                        } else if (method.equals(Method.POST)) {
                            Map<String, String> files = new HashMap<>();
                            session.parseBody(files);
                            String postData = files.get("postData");
                            if ("true".equals(postData)) {
                                writeFileContent("/sys/devices/platform/leds/green_enable", "1");
                                writeFileContent("/sys/devices/platform/leds/red_enable", "1");
                            } else if ("false".equals(postData)) {
                                writeFileContent("/sys/devices/platform/leds/green_enable", "0");
                                writeFileContent("/sys/devices/platform/leds/red_enable", "0");
                            } else {
                                jsonResponse.put("error", "Invalid State Value");
                                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", jsonResponse.toString());
                            }
                            jsonResponse.put("state", readFileContent("/sys/devices/platform/leds/red_enable").contains("1"));
                            return newFixedLengthResponse(Response.Status.OK, "application/json",jsonResponse.toString());
                        } else {
                            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Missing 'enable' header.\"}");
                        }
                    } else if (uri.equals("/getTemp")) {
                        String[] tempSplit = readFileContent("/sys/devices/platform/sht3x-user/sht3x_access").split(":");
                        double temp = (((Double.parseDouble(tempSplit[1]) * 175.0) / 65535.0) - 45.0) - 1.1;
                        temp = Math.round(temp * 10.0) / 10.0;
                        jsonResponse.put("temperature", temp);
                        return newFixedLengthResponse(Response.Status.OK, "application/json", jsonResponse.toString());
                    } else if (uri.equals("/getHumidity")) {
                        String[] humiditySplit = readFileContent("/sys/devices/platform/sht3x-user/sht3x_access").split(":");
                        double humidity = ((Double.parseDouble(humiditySplit[0]) * 100.0) / 65535.0) + 18.0;
                        humidity = Math.round(humidity);
                        jsonResponse.put("humidity", humidity);
                        return newFixedLengthResponse(Response.Status.OK, "application/json", jsonResponse.toString());
                    } else {
                        jsonResponse.put("message", "ShellyElevate by RapierXbox");
                        return newFixedLengthResponse(Response.Status.OK, "application/json", jsonResponse.toString());
                    }
                } catch (JSONException | IOException | ResponseException e) {
                    return newFixedLengthResponse("Internal Server Error!" + e);
                }
            }
        };
        try {
            server.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stop();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private String readFileContent(String filePath) {
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

    private void writeFileContent(String filePath, String content) {
        try (FileWriter writer = new FileWriter(filePath)) {
            writer.write(content);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
