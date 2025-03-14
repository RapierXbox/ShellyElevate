package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.Constants.SP_SWITCH_ON_SWIPE;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.util.Log;
import android.view.MotionEvent;

public class SwipeHelper{
    private float touchStartY = 0;
    private long touchStartEventTime = 0;

    public float minVel = 2.5F;
    public float minDist = 250.0F;

    private boolean doSwitchOnSwipe = true;
    public boolean onTouchEvent(MotionEvent event){
        switch (event.getAction()){
            case MotionEvent.ACTION_DOWN:
                touchStartY = event.getY();
                touchStartEventTime = event.getEventTime();
                return true;
            case MotionEvent.ACTION_UP:
                float deltaY = Math.abs(touchStartY - event.getY());
                float deltaT = Math.abs(touchStartEventTime - event.getEventTime());
                float velocity = deltaY / deltaT;
                if (velocity > minVel && deltaY > minDist && doSwitchOnSwipe){
                    mDeviceHelper.setRelay(!mDeviceHelper.getRelay());
                    return false;
                }
        }
        return true;
    }

    public void updateValues() {
        doSwitchOnSwipe = mSharedPreferences.getBoolean(SP_SWITCH_ON_SWIPE, true);
    }
}
