package me.rapierxbox.shellyelevatev2;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceSensorManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;
import static me.rapierxbox.shellyelevatev2.Constants.*;

import android.content.Intent;
import android.util.Log;
import android.webkit.JavascriptInterface;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.HashMap;
import java.util.Map;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager;

public class ShellyElevateJavascriptInterface {
    private final Map<String, String> bindings = new HashMap<>();

    public ShellyElevateJavascriptInterface() {
        init();
    }

    private void init() {
        if (eJSaEnabled()) {
            Log.d("ShellyElevateV2", "Initializing ShellyElevateJavascriptInterface");
        }
    }

    public boolean eJSaEnabled() {
        return mSharedPreferences.getBoolean(SP_EXTENDED_JAVASCRIPT_INTERFACE, false);
    }

    public String getDevice() {return DeviceModel.getReportedDevice().modelName;}

    // ========= GETTERS =========
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

    @JavascriptInterface public int getCurrentScreenBrightness() {
        return mDeviceHelper.getScreenBrightness();
    }

    @JavascriptInterface  public float getProximity() {
        return mDeviceSensorManager.getLastMeasuredDistance();
    }

    // ========= SETTERS =========

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
        mSharedPreferences.edit().putInt(SP_SCREEN_SAVER_ID, id).apply();
    }

	@JavascriptInterface public void keepScreenAlive(boolean keepAlive) {
		mScreenSaverManager.keepAlive(keepAlive);
	}

    // ========= EVENTS FUNCTION =========
    @JavascriptInterface
    public void bind(String eventName, String jsFunctionName) {
        Log.d("ShellyElevateV2", "JS EventName binding - " + eventName + " => " + jsFunctionName);
        bindings.put(eventName, jsFunctionName);
    }

    private void triggerEvent(String eventName, Object... params) {
        if (eJSaEnabled()) {
            String jsFunction = bindings.get(eventName);
            if (jsFunction != null) {
                Log.d("ShellyElevateV2", "ShellyElevateJavascriptInterface.notifyWebViewEvent: " + eventName);
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
                Log.d("ShellyElevateV2", "Sending JS: " + jsFunction + "(" + joinedParams + ");");
                sendJavascript(jsFunction + "(" + joinedParams + ");");
            }
        }
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
        // Only emit if someone has bound to it to avoid noisy logs
        if (bindings.containsKey("onMotion")) {
            triggerEvent("onMotion");
        }
    }

    public void onButtonPressed(int i) {
        triggerEvent("onButtonPressed", i);
    }
}