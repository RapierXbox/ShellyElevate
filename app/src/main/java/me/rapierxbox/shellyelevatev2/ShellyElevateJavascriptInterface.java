package me.rapierxbox.shellyelevatev2;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceSensorManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;
import static me.rapierxbox.shellyelevatev2.Constants.*;

import android.webkit.JavascriptInterface;


public class ShellyElevateJavascriptInterface {
    @JavascriptInterface
    public String getDevice() {return DeviceModel.getDevice(mSharedPreferences).boardName;}
    @JavascriptInterface
    public boolean getRelay() {return mDeviceHelper.getRelay();}
    @JavascriptInterface
    public int getLux() {return Math.round(mDeviceSensorManager.getLastMeasuredLux());}
    @JavascriptInterface
    public double getTemperature() {return mDeviceHelper.getTemperature();}
    @JavascriptInterface
    public double getHumidity() {return mDeviceHelper.getHumidity();}
    @JavascriptInterface
    public float getProximity() {return mDeviceSensorManager.getLastMeasuredDistance();}
    @JavascriptInterface
    public int getScreenBrightness() {return mDeviceHelper.getScreenBrightness();}
    @JavascriptInterface
    public boolean getScreenSaverRunning() {return mScreenSaverManager.isScreenSaverRunning();}
    @JavascriptInterface
    public boolean getScreenSaverEnabled() {return mScreenSaverManager.isScreenSaverEnabled();}
    @JavascriptInterface
    public int getScreenSaverId() {return mScreenSaverManager.getCurrentScreenSaverId();}

    @JavascriptInterface
    public boolean getExtendedJavascriptInterfaceEnabled() {
        return mSharedPreferences.getBoolean(SP_EXTENDED_JAVASCRIPT_INTERFACE, false);
    }

    @JavascriptInterface
    public void setRelay(boolean state) {
        if (mSharedPreferences.getBoolean(SP_EXTENDED_JAVASCRIPT_INTERFACE, false)) {
            mDeviceHelper.setRelay(state);
        }
    }
    @JavascriptInterface
    public void sleep() {
        if (mSharedPreferences.getBoolean(SP_EXTENDED_JAVASCRIPT_INTERFACE, false)) {
            mScreenSaverManager.startScreenSaver();
        }
    }
    @JavascriptInterface
    public void wake() {
        if (mSharedPreferences.getBoolean(SP_EXTENDED_JAVASCRIPT_INTERFACE, false)) {
            mScreenSaverManager.stopScreenSaver();
        }
    }
    @JavascriptInterface
    public void setScreenBrightness(int brightness) {
        if (mSharedPreferences.getBoolean(SP_EXTENDED_JAVASCRIPT_INTERFACE, false)) {
            mDeviceHelper.setScreenBrightness(brightness);
        }
    }
    @JavascriptInterface
    public void setScreenSaverEnabled(boolean enabled) {
        if (mSharedPreferences.getBoolean(SP_EXTENDED_JAVASCRIPT_INTERFACE, false)) {
            mSharedPreferences.edit().putBoolean(SP_SCREEN_SAVER_ENABLED, enabled).apply();
        }
    }
    @JavascriptInterface
    public void setScreenSaverId(int id) {
        if (mSharedPreferences.getBoolean(SP_EXTENDED_JAVASCRIPT_INTERFACE, false)) {
            mSharedPreferences.edit().putInt(SP_SCREEN_SAVER_ID, id).apply();
        }
    }
}

