package me.rapierxbox.shellyelevatev2;

import static me.rapierxbox.shellyelevatev2.Constants.INTENT_WEBVIEW_INJECT_JAVASCRIPT;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_WEBVIEW_REFRESH;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceSensorManager;

import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import me.rapierxbox.shellyelevatev2.helper.MediaHelper;
import me.rapierxbox.shellyelevatev2.screensavers.ScreenSaverManagerHolder;

public class HttpServer extends NanoHTTPD {
    public HttpServer() {
        super(8080);
    }

    SettingsParser mSettingsParser = new SettingsParser();
    MediaHelper   mMediaHelper = new MediaHelper();

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
                return newFixedLengthResponse(Response.Status.OK, "application/json",
                        "{\"message\": \"ShellyElevateV2 by RapierXbox\"}");
            }
        } catch (JSONException | ResponseException | IOException e) {
            Log.e("HttpServer", "Error handling request", e);
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", jsonResponse.toString());
    }

    private Response handleWebviewRequest(IHTTPSession session) throws JSONException, ResponseException, IOException {
        Method method = session.getMethod();
        String uri = session.getUri();
        JSONObject jsonResponse = new JSONObject();

        switch (uri.replace("/webview/", "")) {
            case "refresh":
                if (method.equals(Method.GET)) {
                    Intent intent = new Intent(INTENT_WEBVIEW_REFRESH);
                    LocalBroadcastManager.getInstance(ShellyElevateApplication.mApplicationContext).sendBroadcast(intent);
                    jsonResponse.put("success", true);
                }
            case "inject":
                if (method.equals(Method.POST)) {
                    Map<String, String> files = new HashMap<>();
                    session.parseBody(files);
                    String postData = files.get("postData");
                    JSONObject jsonObject = new JSONObject(postData);

                    String javascript = jsonObject.getString("javascript");

                    Intent intent = new Intent(INTENT_WEBVIEW_INJECT_JAVASCRIPT);
                    intent.putExtra("javascript", javascript);
                    LocalBroadcastManager.getInstance(ShellyElevateApplication.mApplicationContext).sendBroadcast(intent);

                    jsonResponse.put("success", true);
                }
        }

        return newFixedLengthResponse(jsonResponse.getBoolean("success") ? Response.Status.OK : Response.Status.INTERNAL_ERROR, "application/json", jsonResponse.toString());
    }

    private Response handleMediaRequest(IHTTPSession session) throws JSONException, ResponseException, IOException {
        Method method = session.getMethod();
        String uri = session.getUri();
        JSONObject jsonResponse = new JSONObject();

        switch (uri.replace("/media/", "")) {
            case "play":
                if (method.equals(Method.POST)) {
                    Map<String, String> files = new HashMap<>();
                    session.parseBody(files);
                    String postData = files.get("postData");
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

    private Response handleDeviceRequest(IHTTPSession session) throws JSONException, ResponseException, IOException {
        Method method = session.getMethod();
        String uri = session.getUri();
        JSONObject jsonResponse = new JSONObject();

        switch (uri.replace("/device/", "")) {
            case "relay":
                if (method.equals(Method.GET)) {
                    jsonResponse.put("success", true);
                    jsonResponse.put("state", mDeviceHelper.getRelay());
                } else if (method.equals(Method.POST)) {
                    Map<String, String> files = new HashMap<>();
                    session.parseBody(files);
                    String postData = files.get("postData");
                    JSONObject jsonObject = new JSONObject(postData);

                    mDeviceHelper.setRelay(jsonObject.getBoolean("state"));
                    jsonResponse.put("success", true);
                    jsonResponse.put("state", mDeviceHelper.getRelay());
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
            case "wake":
                jsonResponse.put("success", false);
                if (method.equals(Method.POST)) {
                    ScreenSaverManagerHolder.getInstance().stopScreenSaver();
                    jsonResponse.put("success", true);
                }
                break;
            case "sleep":
                jsonResponse.put("success", false);
                if (method.equals(Method.POST)) {
                    ScreenSaverManagerHolder.getInstance().startScreenSaver();
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
                            Log.e("MQTT", "Error rebooting:", e);
                        }
                    } else {
                        Toast.makeText(mApplicationContext, "Please wait %s seconds before rebooting".replace("%s", String.valueOf(20 - deltaTime)), Toast.LENGTH_LONG).show();
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

    public void onDestroy() {
        closeAllConnections();
        stop();
        mMediaHelper.onDestroy();
    }
}
