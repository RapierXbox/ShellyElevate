package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.Constants.SP_SWITCH_ON_SWIPE;
import static me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_SINGLE;
import static me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_TWO_FINGER_DOWN;
import static me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_TWO_FINGER_LEFT;
import static me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_TWO_FINGER_RIGHT;
import static me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_TWO_FINGER_UP;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMQTTServer;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.util.SparseArray;
import android.view.MotionEvent;

public class SwipeHelper {
    // Thresholds for "real swipe" vs. accidental drag. Velocity is px / ms;
    // distance is raw pixels, so values are tied to display density.
    public float minVel = 2.5F;
    public float minDist = 250.0F;

    private final SparseArray<PointerInfo> pointers = new SparseArray<>();
    // tracks across the full gesture; some fingers may have lifted before ACTION_UP
    private int maxPointerCount = 0;
    private long gestureStartTime = 0;

    private static class PointerInfo {
        float startX, startY, endX, endY;
        PointerInfo(float x, float y) {
            startX = x; startY = y; endX = x; endY = y;
        }
    }

    private void clearState() {
        pointers.clear();
        maxPointerCount = 0;
        gestureStartTime = 0;
    }

    public boolean onTouchEvent(MotionEvent event) {
        if (!mSharedPreferences.getBoolean(SP_SWITCH_ON_SWIPE, true)) return true;

        int actionMasked = event.getActionMasked();
        int actionIndex  = event.getActionIndex();

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
                clearState();
                pointers.put(event.getPointerId(0), new PointerInfo(event.getX(), event.getY()));
                maxPointerCount = 1;
                gestureStartTime = event.getEventTime();
                break;

            case MotionEvent.ACTION_POINTER_DOWN: {
                int pid = event.getPointerId(actionIndex);
                pointers.put(pid, new PointerInfo(event.getX(actionIndex), event.getY(actionIndex)));
                if (event.getPointerCount() > maxPointerCount) maxPointerCount = event.getPointerCount();
                break;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                int pid = event.getPointerId(actionIndex);
                PointerInfo p = pointers.get(pid);
                if (p != null) { p.endX = event.getX(actionIndex); p.endY = event.getY(actionIndex); }
                break;
            }

            case MotionEvent.ACTION_UP: {
                int pid = event.getPointerId(0);
                PointerInfo p = pointers.get(pid);
                if (p != null) { p.endX = event.getX(); p.endY = event.getY(); }
                evaluate(event.getEventTime());
                clearState();
                break;
            }

            case MotionEvent.ACTION_CANCEL:
                clearState();
                break;
        }

        return true;
    }

    private void evaluate(long endTime) {
        long totalTime = Math.max(1, endTime - gestureStartTime);

        if (maxPointerCount == 1) {
            PointerInfo p = pointers.size() > 0 ? pointers.valueAt(0) : null;
            if (p == null) return;
            float deltaY   = Math.abs(p.startY - p.endY);
            float velocity = deltaY / (float) totalTime;
            if (velocity > minVel && deltaY > minDist) {
                var numRelay = 0;
                mDeviceHelper.setRelay(numRelay, !mDeviceHelper.getRelay(numRelay));
                if (mMQTTServer.shouldSend()) mMQTTServer.publishSwipeEvent(SWIPE_EVENT_TYPE_SINGLE);
            }
            return;
        }

        int count = pointers.size();
        if (count == 0) return;

        float sumDx = 0, sumDy = 0;
        for (int i = 0; i < count; i++) {
            PointerInfo p = pointers.valueAt(i);
            sumDx += p.endX - p.startX;
            sumDy += p.endY - p.startY;
        }
        float meanDx = sumDx / count;
        float meanDy = sumDy / count;

        boolean vertical = Math.abs(meanDy) >= Math.abs(meanDx);
        float meanDist   = Math.max(Math.abs(meanDx), Math.abs(meanDy));
        float velocity   = meanDist / (float) totalTime;

        if (velocity <= minVel || meanDist <= minDist) return;

        // reject pinch/divergent: every pointer must agree in sign on the dominant axis
        for (int i = 0; i < count; i++) {
            PointerInfo p = pointers.valueAt(i);
            float delta = vertical ? (p.endY - p.startY) : (p.endX - p.startX);
            float mean  = vertical ? meanDy : meanDx;
            if (Math.signum(delta) != Math.signum(mean)) return;
        }

        if (!mMQTTServer.shouldSend()) return;

        if (vertical) {
            mMQTTServer.publishSwipeEvent(meanDy < 0 ? SWIPE_EVENT_TYPE_TWO_FINGER_UP : SWIPE_EVENT_TYPE_TWO_FINGER_DOWN);
        } else {
            mMQTTServer.publishSwipeEvent(meanDx < 0 ? SWIPE_EVENT_TYPE_TWO_FINGER_LEFT : SWIPE_EVENT_TYPE_TWO_FINGER_RIGHT);
        }
    }
}
