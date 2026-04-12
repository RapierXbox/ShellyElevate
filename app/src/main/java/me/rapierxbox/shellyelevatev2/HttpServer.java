package me.rapierxbox.shellyelevatev2;

import static me.rapierxbox.shellyelevatev2.Constants.INTENT_SETTINGS_CHANGED;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_WEBVIEW_INJECT_JAVASCRIPT;
import static me.rapierxbox.shellyelevatev2.Constants.SP_HTTP_SERVER_ENABLED;
import static me.rapierxbox.shellyelevatev2.Constants.SP_MEDIA_ENABLED;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceSensorManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMediaHelper;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.BufferedReader;
import java.io.InputStreamReader;

import fi.iki.elonen.NanoHTTPD;
public class HttpServer extends NanoHTTPD {
    final SettingsParser mSettingsParser = new SettingsParser();

    public HttpServer() {
        super(8080);

        LocalBroadcastManager localBroadcastManager = LocalBroadcastManager.getInstance(mApplicationContext);
        BroadcastReceiver settingsChangedBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (mSharedPreferences.getBoolean(SP_HTTP_SERVER_ENABLED, true) && !isAlive()) {
                    try {
                        start();
                    } catch (IOException e) {
                        Log.d("HttpServer", "Failed to start http server: " + e);
                    }
                } else if (!mSharedPreferences.getBoolean(SP_HTTP_SERVER_ENABLED, true) && isAlive()) {
                    stop();
                }
            }
        };
        localBroadcastManager.registerReceiver(settingsChangedBroadcastReceiver, new IntentFilter(INTENT_SETTINGS_CHANGED));
    }

    @Override
    public Response serve(IHTTPSession session) {
        Method method = session.getMethod();
        String uri = session.getUri();
        JSONObject jsonResponse = new JSONObject();

        try {
            if (uri.startsWith("/media/")) {
                return handleMediaRequest(session);
            } else if (uri.startsWith("/device/")) {
                return handleDeviceRequest(session);
            } else if (uri.startsWith("/webview/")) {
                return handleWebviewRequest(session);
            } else if (uri.equals("/settings")) {
                if (method.equals(Method.GET)) {
                    jsonResponse.put("success", true);
                    jsonResponse.put("settings", mSettingsParser.getSettings());
                } else if (method.equals(Method.POST)) {
                    Map<String, String> files = new HashMap<>();
                    session.parseBody(files);
                    String postData = files.get("postData");
                    assert postData != null;
                    JSONObject jsonObject = new JSONObject(postData);

                    mSettingsParser.setSettings(jsonObject);

                    jsonResponse.put("success", true);
                    jsonResponse.put("settings", mSettingsParser.getSettings());
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("error", "Invalid request method");
                }
                return newFixedLengthResponse(Response.Status.OK, "application/json", jsonResponse.toString());
            } else if (uri.equals("/")) {
                JSONObject json = new JSONObject();

                try {
                    json.put("name", mApplicationContext.getPackageName());

                    String version = "unknown";
                    try {
                        PackageInfo pInfo = mApplicationContext.getPackageManager()
                                .getPackageInfo(mApplicationContext.getPackageName(), 0);
                        version = pInfo.versionName;
                    } catch (PackageManager.NameNotFoundException ignored) {}

                    json.put("version", version);
                    var device = DeviceModel.getReportedDevice();
                    json.put("modelName", device.name());
                    json.put("proximity", device.hasProximitySensor ? "true" : "false");
                    json.put("numOfButtons", device.buttons);
                    json.put("numOfInputs", device.inputs);
                } catch (JSONException e) {
                    Log.e("MQTT", "Error publishing hello", e);
                }

                return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString());
            }
        } catch (JSONException | ResponseException | IOException | InterruptedException e) {
            Log.e("HttpServer", "Error handling request", e);
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", jsonResponse.toString());
    }

    private Response handleWebviewRequest(IHTTPSession session) throws JSONException, ResponseException, IOException {
        Method method = session.getMethod();
        String uri = session.getUri();
        JSONObject jsonResponse = new JSONObject();
        jsonResponse.put("success", false);

        switch (uri.replace("/webview/", "")) {
            case "refresh":
                if (method.equals(Method.GET)) {
                    Intent intent = new Intent(INTENT_SETTINGS_CHANGED);
                    LocalBroadcastManager.getInstance(ShellyElevateApplication.mApplicationContext).sendBroadcast(intent);
                    jsonResponse.put("success", true);
                }
                break;
            case "inject":
                if (method.equals(Method.POST)) {
                    Map<String, String> files = new HashMap<>();
                    session.parseBody(files);
                    String postData = files.get("postData");
                    assert postData != null;
                    JSONObject jsonObject = new JSONObject(postData);

                    String javascript = jsonObject.getString("javascript");

                    Intent intent = new Intent(INTENT_WEBVIEW_INJECT_JAVASCRIPT);
                    intent.putExtra("javascript", javascript);
                    LocalBroadcastManager.getInstance(ShellyElevateApplication.mApplicationContext).sendBroadcast(intent);

                    jsonResponse.put("success", true);
                }
                break;
            default:
                jsonResponse.put("error", "Invalid request URI");
                break;
        }

        return newFixedLengthResponse(jsonResponse.optBoolean("success") ? Response.Status.OK : Response.Status.INTERNAL_ERROR, "application/json", jsonResponse.toString());
    }

    private Response handleMediaRequest(IHTTPSession session) throws JSONException, ResponseException, IOException {
        if (!mSharedPreferences.getBoolean(SP_MEDIA_ENABLED, false) || ShellyElevateApplication.mMediaHelper == null) {
            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("success", false);
            jsonResponse.put("error", "Media disabled");
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", jsonResponse.toString());
        }

        Method method = session.getMethod();
        String uri = session.getUri();
        JSONObject jsonResponse = new JSONObject();

        switch (uri.replace("/media/", "")) {
            case "play":
                if (method.equals(Method.POST)) {
                    Map<String, String> files = new HashMap<>();
                    session.parseBody(files);
                    String postData = files.get("postData");
                    assert postData != null;
                    JSONObject jsonObject = new JSONObject(postData);

                    Uri mediaUri = Uri.parse(jsonObject.getString("url"));
                    boolean music = jsonObject.getBoolean("music");
                    double volume = jsonObject.getDouble("volume");

                    mMediaHelper.setVolume(volume);

                    if (music) {
                        mMediaHelper.playMusic(mediaUri);
                    } else {
                        mMediaHelper.playEffect(mediaUri);
                    }

                    jsonResponse.put("success", true);
                    jsonResponse.put("url", jsonObject.getString("url"));
                    jsonResponse.put("music", music);
                    jsonResponse.put("volume", volume);
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("error", "Invalid request method");
                }
                break;
            case "pause":
                if (method.equals(Method.POST)) {
                    mMediaHelper.pauseMusic();
                    jsonResponse.put("success", true);
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("error", "Invalid request method");
                }
                break;
            case "resume":
                if (method.equals(Method.POST)) {
                    mMediaHelper.resumeMusic();
                    jsonResponse.put("success", true);
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("error", "Invalid request method");
                }
                break;
            case "stop":
                if (method.equals(Method.POST)) {
                    mMediaHelper.stopAll();
                    jsonResponse.put("success", true);
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("error", "Invalid request method");
                }
                break;
            case "volume":
                if (method.equals(Method.POST)) {
                    Map<String, String> files = new HashMap<>();
                    session.parseBody(files);
                    String postData = files.get("postData");
                    assert postData != null;
                    JSONObject jsonObject = new JSONObject(postData);

                    double volume = jsonObject.getDouble("volume");

                    mMediaHelper.setVolume(volume);

                    jsonResponse.put("success", true);
                    jsonResponse.put("volume", mMediaHelper.getVolume());
                } else if (method.equals(Method.GET)) {
                    jsonResponse.put("success", true);
                    jsonResponse.put("volume", mMediaHelper.getVolume());
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("error", "Invalid request method");
                }
                break;
            default:
                jsonResponse.put("success", false);
                jsonResponse.put("error", "Invalid request URI");
                break;
        }

        return newFixedLengthResponse(jsonResponse.getBoolean("success") ? Response.Status.OK : Response.Status.INTERNAL_ERROR, "application/json", jsonResponse.toString());
    }

    private Response handleDeviceRequest(IHTTPSession session) throws JSONException, IOException, InterruptedException {
        Method method = session.getMethod();
        String uri = session.getUri();
        JSONObject jsonResponse = new JSONObject();

        DeviceModel device = DeviceModel.getReportedDevice();

        switch (uri.replace("/device/", "")) {
            case "relay":
                Map<String, String> files = new HashMap<>();
                Map<String, List<String>> params = new HashMap<>();
                try {
                    /* cannot be called multiple times;
                       session.parseBody() reads the POST request's body and calls HTTPSession's decodeParms() method,
                       which sets the queryParameterString and parms field values to the POST's body */
                    session.parseBody(files);

                    //params.putAll(session.getParms()); getParams() is deprecated!
                    params.putAll(session.getParameters());
                } catch (IOException | ResponseException e) {
                    Log.e("HttpServer", "Invalid parameters", e);
                }
                if (method.equals(Method.GET)) {
                    int num = GetNumParameter(params, 0);
                    if (num == -999) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid num");
                    jsonResponse.put("success", true);
                    jsonResponse.put("state", mDeviceHelper.getRelay(num));
                } else if (method.equals(Method.POST)) {
                    // get the POST body { "state", "true" }
                    String postData = files.get("postData");
                    assert postData != null;
                    JSONObject jsonObject = new JSONObject(postData);

                    int num = GetNumParameter(params, -1);
                    if (num == -999) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid num");
                    // num as json body
                    if (num == -1 && jsonObject.getInt("num")>=0)
                        num = jsonObject.getInt("num");

                    mDeviceHelper.setRelay(num, jsonObject.getBoolean("state"));

                    jsonResponse.put("success", true);
                    jsonResponse.put("state", mDeviceHelper.getRelay(num));
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("error", "Invalid request method");
                }
                break;
            case "getTemperature":
                if (method.equals(Method.GET)) {
                    jsonResponse.put("success", true);
                    jsonResponse.put("temperature", mDeviceHelper.getTemperature());
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("error", "Invalid request method");
                }
                break;
            case "getHumidity":
                if (method.equals(Method.GET)) {
                    jsonResponse.put("success", true);
                    jsonResponse.put("humidity", mDeviceHelper.getHumidity());
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("error", "Invalid request method");
                }
                break;
            case "getLux":
                if (method.equals(Method.GET)) {
                    jsonResponse.put("success", true);
                    jsonResponse.put("lux", mDeviceSensorManager.getLastMeasuredLux());
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("error", "Invalid request method");
                }
                break;
            case "getProximity":
                if (method.equals(Method.GET)) {

                    if (device.hasProximitySensor) {
                        jsonResponse.put("success", true);
                        jsonResponse.put("distance", mDeviceSensorManager.getLastMeasuredDistance());
                    } else {
                        jsonResponse.put("success", false);
                        jsonResponse.put("error", "This device doesn't support proximity sensor measurement");
                    }
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("error", "Invalid request method");
                }
                break;
            case "wake":
                jsonResponse.put("success", false);
                if (method.equals(Method.POST)) {
                    mScreenSaverManager.stopScreenSaver();
                    jsonResponse.put("success", true);
                }
                break;
            case "sleep":
                jsonResponse.put("success", false);
                if (method.equals(Method.POST)) {
                    mScreenSaverManager.startScreenSaver();
                    jsonResponse.put("success", true);
                }
                break;
            case "reboot":
                jsonResponse.put("success", false);
                if (method.equals(Method.POST)) {
                    long deltaTime = System.currentTimeMillis() - ShellyElevateApplication.getApplicationStartTime();
                    deltaTime /= 1000;
                    if (deltaTime > 20) {
                        try {
                            Runtime.getRuntime().exec("reboot");
                            jsonResponse.put("success", true);
                        } catch (IOException e) {
                            Log.e("HttpServer", "Error rebooting:", e);
                        }
                    } else {
                        Toast.makeText(mApplicationContext, "Please wait %s seconds before rebooting".replace("%s", String.valueOf(20 - deltaTime)), Toast.LENGTH_LONG).show();
                    }
                }
                break;
            case "free":
                jsonResponse.put("success", false);
                if (method.equals(Method.GET)) {
                    try {
                        // Execute 'free -m' to get memory info in mebibyte MiB
                        Process process = Runtime.getRuntime().exec("free -m");
                        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

                        String line;
                        while ((line = reader.readLine()) != null) {
                            /* free -m: (column "available" doesn't exist at Android)
                                       total        used        free      shared     buffers
                                Mem:     959         917          41           1          29
                            */
                            if (line.startsWith("Mem:")) {
                                // Split by whitespace; \\s+ handles multiple spaces
                                String[] tokens = line.split("\\s+");

                                long totalMemory = Long.parseLong(tokens[1]);
                                long availableMemory = Long.parseLong(tokens[3]);

                                jsonResponse.put("success", true);
                                jsonResponse.put("Mem total memory", totalMemory + "MiB");
                                jsonResponse.put("Mem free memory", availableMemory + "MiB");
                            }
                            if (line.startsWith("Swap:")) {
                                String[] tokens = line.split("\\s+");

                                long totalMemory = Long.parseLong(tokens[1]);
                                long availableMemory = Long.parseLong(tokens[3]);

                                jsonResponse.put("success", true);
                                jsonResponse.put("Swap total memory", totalMemory + "MiB");
                                jsonResponse.put("Swap free memory", availableMemory + "MiB");
                            }
                        }
                        process.waitFor();
                    } catch (IOException e) {
                        Log.e("HttpServer", "Error free command request", e);
                    }
                }
                break;
            default:
                jsonResponse.put("success", false);
                jsonResponse.put("error", "Invalid request URI");
                break;
        }

        return newFixedLengthResponse(jsonResponse.getBoolean("success") ? Response.Status.OK : Response.Status.INTERNAL_ERROR, "application/json", jsonResponse.toString());
    }
    private static int GetNumParameter(Map<String, List<String>> params, int defaultValue) {
        // Get the value of num
        List<String> numParam = params.get("num");
        if (!(numParam == null) && !numParam.isEmpty()) {
            try {
                // first element of the list is the value
                return Integer.parseInt(numParam.get(0));
            } catch (NumberFormatException e) {
                // handle invalid number
                return -999;
            }
        }
        return defaultValue; // Default
    }

    public void onDestroy() {
        closeAllConnections();
        stop();
    }
}
