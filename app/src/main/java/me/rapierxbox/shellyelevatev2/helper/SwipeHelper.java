package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.Constants.SP_SWITCH_ON_SWIPE;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMQTTServer;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.view.MotionEvent;

public class SwipeHelper{
    private float touchStartY = 0;
    private long touchStartEventTime = 0;

    public float minVel = 2.5F;
    public float minDist = 250.0F;

    public boolean onTouchEvent(MotionEvent event){
        if (!mSharedPreferences.getBoolean(SP_SWITCH_ON_SWIPE, true)) return true;

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touchStartY = event.getY();
                touchStartEventTime = event.getEventTime();
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                float deltaY = Math.abs(touchStartY - event.getY());
                float deltaT = Math.max(1f, Math.abs(touchStartEventTime - event.getEventTime())); // avoid div0
                float velocity = deltaY / deltaT;
                if (velocity > minVel && deltaY > minDist) {
                    var numRelay = 0;
                    mDeviceHelper.setRelay(numRelay, !mDeviceHelper.getRelay(numRelay));
                    if (mMQTTServer.shouldSend()) {
                        mMQTTServer.publishSwipeEvent();
                    }
                }
                break;
        }

        return true;
    }
}
