package me.rapierxbox.shellyelevatev2;

import static me.rapierxbox.shellyelevatev2.Constants.*;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceSensorManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.content.Intent;
import android.util.Log;
import android.webkit.JavascriptInterface;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import me.rapierxbox.shellyelevatev2.screensavers.ScreenSaverManager;

public class ShellyElevateJavascriptInterface {
    private static final String TAG = "ShellyElevateV2";
    // a plain or dotted function name like onPress or window.app.onPress
    private static final Pattern FUNCTION_NAME = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*");

    // written from the webview js bridge thread and read from the main thread
    private final Map<String, String> bindings = new ConcurrentHashMap<>();
    // only a keep alive the page asked for ends with the page
    private volatile boolean pageKeptAlive = false;

    public ShellyElevateJavascriptInterface() {
        if (eJSaEnabled()) {
            Log.d(TAG, "Initializing ShellyElevateJavascriptInterface");
        }
    }

    public boolean eJSaEnabled() {
        return mSharedPreferences.getBoolean(SP_EXTENDED_JAVASCRIPT_INTERFACE, false);
    }

    @JavascriptInterface public String getDevice() {return DeviceModel.getReportedDevice().sku;}

    @JavascriptInterface public boolean getRelay(int num) {
        return mDeviceHelper.getRelay(num);
    }

    @JavascriptInterface public int getLux() {
        return Math.round(mDeviceSensorManager.getLastMeasuredLux());
    }

    @JavascriptInterface public double getTemperature() {
        return mDeviceHelper.getTemperature();
    }

    @JavascriptInterface public double getHumidity() {
        return mDeviceHelper.getHumidity();
    }

    @JavascriptInterface public int getScreenBrightness() {
        return mDeviceHelper.getScreenBrightness();
    }

    @JavascriptInterface public boolean getScreenSaverRunning() {
        return mScreenSaverManager.isScreenSaverRunning();
    }

    @JavascriptInterface public boolean getScreenSaverEnabled() {
        return mScreenSaverManager.isScreenSaverEnabled();
    }

    @JavascriptInterface public int getScreenSaverId() {
        return mScreenSaverManager.getCurrentScreenSaverId();
    }

    @JavascriptInterface public boolean getExtendedJavascriptInterfaceEnabled() {
        return eJSaEnabled();
    }

    @JavascriptInterface public boolean isInForeground() {
        return ShellyElevateApplication.mApplicationContext != null && mDeviceHelper.getScreenOn();
    }

    // kept alongside getScreenBrightness for older js bridge callers same value
    @JavascriptInterface public int getCurrentScreenBrightness() {
        return mDeviceHelper.getScreenBrightness();
    }

    @JavascriptInterface  public float getProximity() {
        return mDeviceSensorManager.getLastMeasuredDistance();
    }

    @JavascriptInterface public void setRelay(int num, boolean state) {
        mDeviceHelper.setRelay(num, state);
    }

    @JavascriptInterface public void sleep() {
        mScreenSaverManager.startScreenSaver();
    }

    @JavascriptInterface public void wake() {
        mScreenSaverManager.stopScreenSaver();
    }

    @JavascriptInterface public void setScreenBrightness(int brightness) {
        mDeviceHelper.setScreenBrightness(brightness);
    }

    @JavascriptInterface public void setScreenSaverEnabled(boolean enabled) {
        mSharedPreferences.edit().putBoolean(SP_SCREEN_SAVER_ENABLED, enabled).apply();
    }

    @JavascriptInterface public void setScreenSaverId(int id) {
        // an id past the list would crash the screensaver spinner in the settings
        int last = ScreenSaverManager.getAvailableScreenSavers().length - 1;
        mSharedPreferences.edit().putInt(SP_SCREEN_SAVER_ID, Math.max(0, Math.min(id, last))).apply();
    }

    @JavascriptInterface public void keepScreenAlive(boolean keepAlive) {
        pageKeptAlive = keepAlive;
        mScreenSaverManager.keepAlive(keepAlive);
    }

    // a new page starts without what the old one asked for
    public void onPageStarted() {
        bindings.clear();
        if (pageKeptAlive) {
            pageKeptAlive = false;
            mScreenSaverManager.keepAlive(false);
        }
    }

    @JavascriptInterface
    public void bind(String eventName, String jsFunctionName) {
        // the name runs later in the main frame so any frame could otherwise bind a whole script
        if (jsFunctionName == null || !FUNCTION_NAME.matcher(jsFunctionName).matches()) {
            Log.w(TAG, "JS binding refused for " + eventName);
            return;
        }
        Log.d(TAG, "JS EventName binding - " + eventName + " => " + jsFunctionName);
        bindings.put(eventName, jsFunctionName);
    }

    private void triggerEvent(String eventName, Object... params) {
        if (!eJSaEnabled()) return;

        String jsFunction = bindings.get(eventName);
        if (jsFunction == null) return;

        Log.d(TAG, "ShellyElevateJavascriptInterface.notifyWebViewEvent: " + eventName);
        String joinedParams = "";
        if (params != null && params.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (Object p : params) {
                if (p instanceof String) {
                    sb.append("'").append(p.toString().replace("'", "\\'")).append("'");
                } else {
                    sb.append(p);
                }
                sb.append(",");
            }
            joinedParams = sb.substring(0, sb.length() - 1);
        }
        Log.d(TAG, "Sending JS: " + jsFunction + "(" + joinedParams + ");");
        sendJavascript(jsFunction + "(" + joinedParams + ");");
    }

    private void sendJavascript(String javascript){
        Intent intent = new Intent(INTENT_WEBVIEW_INJECT_JAVASCRIPT);
        intent.putExtra("javascript", javascript);
        LocalBroadcastManager.getInstance(ShellyElevateApplication.mApplicationContext).sendBroadcast(intent);
    }

    public void onScreenOn() {
        triggerEvent("onScreenOn");
    }

    public void onScreenOff() {
        triggerEvent("onScreenOff");
    }

    public void onScreensaverOn() {
        triggerEvent("onScreensaverOn");
    }

    public void onScreensaverOff() {
        triggerEvent("onScreensaverOff");
    }

    public void onMotion() {
        // only emit when bound since the proximity sensor fires often and would spam logs
        if (bindings.containsKey("onMotion")) {
            triggerEvent("onMotion");
        }
    }

    public void onButtonPressed(int i) {
        triggerEvent("onButtonPressed", i);
    }
}
