package me.rapierxbox.shellyelevatev2;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceSensorManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;
import static me.rapierxbox.shellyelevatev2.Constants.*;

import android.webkit.JavascriptInterface;


public class ShellyElevateJavascriptInterface {
    private boolean eJSa = false; //Extended Javascript allowed

    ShellyElevateJavascriptInterface() {
        updateValues();
    }

    public void updateValues() {
        eJSa = mSharedPreferences.getBoolean(SP_EXTENDED_JAVASCRIPT_INTERFACE, false);
    }

    @JavascriptInterface
    public boolean getRelay() {return mDeviceHelper.getRelay();}
    @JavascriptInterface
    public int getLux() {return Math.round(mDeviceSensorManager.getLastMeasuredLux());}
    @JavascriptInterface
    public double getTemperature() {return mDeviceHelper.getTemperature();}
    @JavascriptInterface
    public double getHumidity() {return mDeviceHelper.getHumidity();}
    @JavascriptInterface
    public int getScreenBrightness() {return mDeviceHelper.getScreenBrightness();}
    @JavascriptInterface
    public boolean getScreenSaverRunning() {return mScreenSaverManager.isScreenSaverRunning();}
    @JavascriptInterface
    public boolean getScreenSaverEnabled() {return mScreenSaverManager.isScreenSaverEnabled();}
    @JavascriptInterface
    public int getScreenSaverId() {return mScreenSaverManager.getCurrentScreenSaverId();}
    @JavascriptInterface
    public boolean getExtendedJavascriptInterfaceEnabled() {return eJSa;}

    @JavascriptInterface
    public void setRelay(boolean state) {if (eJSa) {mDeviceHelper.setRelay(state);}}
    @JavascriptInterface
    public void sleep() {if (eJSa) {mScreenSaverManager.startScreenSaver();}}
    public void wake() {if (eJSa) {mScreenSaverManager.stopScreenSaver();}}
    @JavascriptInterface
    public void setScreenBrightness(int brightness) {if (eJSa) {mDeviceHelper.setScreenBrightness(brightness);}}
    public void setScreenSaverEnabled(boolean enabled) {
        if (eJSa) {
            mSharedPreferences.edit().putBoolean(SP_SCREEN_SAVER_ENABLED, enabled).apply();
            mScreenSaverManager.updateValues();
        }
    }
    @JavascriptInterface
    public void setScreenSaverId(int id) {
        if (eJSa) {
            mSharedPreferences.edit().putInt(SP_SCREEN_SAVER_ID, id).apply();
            mScreenSaverManager.updateValues();
        }
    }
}

