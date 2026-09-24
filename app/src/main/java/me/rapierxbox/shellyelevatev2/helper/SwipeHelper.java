package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.Constants.SP_SWITCH_ON_SWIPE;
import static me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_FIVE_FINGER_DOWN;
import static me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_FIVE_FINGER_LEFT;
import static me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_FIVE_FINGER_RIGHT;
import static me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_FIVE_FINGER_UP;
import static me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_FOUR_FINGER_DOWN;
import static me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_FOUR_FINGER_LEFT;
import static me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_FOUR_FINGER_RIGHT;
import static me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_FOUR_FINGER_UP;
import static me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_SINGLE;
import static me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_THREE_FINGER_DOWN;
import static me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_THREE_FINGER_LEFT;
import static me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_THREE_FINGER_RIGHT;
import static me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_THREE_FINGER_UP;
import static me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_TWO_FINGER_DOWN;
import static me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_TWO_FINGER_LEFT;
import static me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_TWO_FINGER_RIGHT;
import static me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_TWO_FINGER_UP;
import static me.rapierxbox.shellyelevatev2.Constants.SP_PUBLISH_SWIPE_EVENTS;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMQTTServer;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.util.Log;
import android.util.SparseArray;
import android.view.MotionEvent;

import me.rapierxbox.shellyelevatev2.BuildConfig;

public class SwipeHelper {
    private static final String TAG = "SwipeHelper";
    private static final float PINCH_SPREAD_RATIO_THRESHOLD = 0.35F;

    // thresholds for a real swipe vs an accidental drag. velocity is px/ms
    // distance is raw pixels so both are tied to display density
    private static final float MIN_VELOCITY_PX_MS = 1.0F; // 1000 px/s
    private final float minDistancePx = Math.min(
        mApplicationContext.getResources().getDisplayMetrics().widthPixels,
        mApplicationContext.getResources().getDisplayMetrics().heightPixels
    ) / 3.0F;

    private final SparseArray<PointerInfo> pointers = new SparseArray<>();
    // tracks across the full gesture since some fingers may have lifted before ACTION_UP
    private int maxPointerCount = 0;
    private long gestureStartTime = 0;
    private long lastPointerJoinTime = 0;

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
        lastPointerJoinTime = 0;
    }

    public boolean onTouchEvent(MotionEvent event) {
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
                lastPointerJoinTime = event.getEventTime();
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
        boolean switchOnSwipe = mSharedPreferences.getBoolean(SP_SWITCH_ON_SWIPE, true);
        boolean publishSwipeEvents = mSharedPreferences.getBoolean(SP_PUBLISH_SWIPE_EVENTS, true);

        // velocity is measured from the last finger-join timestamp for multi-touch
        // and from gestureStartTime for single-finger gestures
        long refTime = (lastPointerJoinTime > 0) ? lastPointerJoinTime : gestureStartTime;
        long totalTime = Math.max(1, endTime - refTime);

        if (maxPointerCount == 1) {
            evaluateSingleFinger(totalTime, switchOnSwipe);
            return;
        }

        evaluateMultiFinger(totalTime, publishSwipeEvents);
    }

    private void evaluateSingleFinger(long totalTime, boolean switchOnSwipe) {
        PointerInfo p = pointers.size() > 0 ? pointers.valueAt(0) : null;
        if (p == null) return;
        float deltaY   = Math.abs(p.startY - p.endY);
        float velocity = deltaY / (float) totalTime;
        if (switchOnSwipe && velocity > MIN_VELOCITY_PX_MS && deltaY > minDistancePx) {
            int relayIndex = 0;
            mDeviceHelper.setRelay(relayIndex, !mDeviceHelper.getRelay(relayIndex));
            if (mMQTTServer != null && mMQTTServer.shouldSend()) mMQTTServer.publishSwipeEvent(SWIPE_EVENT_TYPE_SINGLE);
        }
    }

    private void evaluateMultiFinger(long totalTime, boolean publishSwipeEvents) {
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

        if (velocity <= MIN_VELOCITY_PX_MS || meanDist <= minDistancePx) return;
        if (isPinchOrSpread(count, meanDist)) return;
        if (fingersDisagreeOnDirection(count, vertical, meanDx, meanDy)) return;
        if (!publishSwipeEvents || mMQTTServer == null || !mMQTTServer.shouldSend()) return;

        publishMultiFingerSwipe(vertical, meanDx, meanDy);
    }

    // rejects pinch/spread: the inter-pointer distance changing by more than
    // PINCH_SPREAD_RATIO_THRESHOLD x meanDist means this is pinch-to-zoom not a swipe
    // only checked for two fingers since more than that is not a native pinch gesture
    private boolean isPinchOrSpread(int count, float meanDist) {
        if (count != 2) return false;
        PointerInfo p0 = pointers.valueAt(0);
        PointerInfo p1 = pointers.valueAt(1);
        float startSpread = (float) Math.hypot(p0.startX - p1.startX, p0.startY - p1.startY);
        float endSpread   = (float) Math.hypot(p0.endX   - p1.endX,   p0.endY   - p1.endY);
        return Math.abs(endSpread - startSpread) > PINCH_SPREAD_RATIO_THRESHOLD * meanDist;
    }

    // every pointer must agree in sign on the dominant axis or this is a pinch/divergent gesture not a swipe
    private boolean fingersDisagreeOnDirection(int count, boolean vertical, float meanDx, float meanDy) {
        float mean = vertical ? meanDy : meanDx;
        for (int i = 0; i < count; i++) {
            PointerInfo p = pointers.valueAt(i);
            float delta = vertical ? (p.endY - p.startY) : (p.endX - p.startX);
            if (Math.signum(delta) != Math.signum(mean)) return true;
        }
        return false;
    }

    private void publishMultiFingerSwipe(boolean vertical, float meanDx, float meanDy) {
        String[] events = eventNamesFor(maxPointerCount);
        String event = vertical
                ? (meanDy < 0 ? events[0] : events[1])
                : (meanDx < 0 ? events[2] : events[3]);

        mMQTTServer.publishSwipeEvent(event);
        if (mScreenSaverManager != null) mScreenSaverManager.onSwipeFired();
        if (BuildConfig.DEBUG) Log.d(TAG, "multi-finger accepted event=" + event);
    }

    // [up down left right] event names for a simultaneous finger count clamped to 2..5
    private static String[] eventNamesFor(int fingerCount) {
        if (fingerCount >= 5) {
            return new String[] {
                SWIPE_EVENT_TYPE_FIVE_FINGER_UP, SWIPE_EVENT_TYPE_FIVE_FINGER_DOWN,
                SWIPE_EVENT_TYPE_FIVE_FINGER_LEFT, SWIPE_EVENT_TYPE_FIVE_FINGER_RIGHT
            };
        } else if (fingerCount == 4) {
            return new String[] {
                SWIPE_EVENT_TYPE_FOUR_FINGER_UP, SWIPE_EVENT_TYPE_FOUR_FINGER_DOWN,
                SWIPE_EVENT_TYPE_FOUR_FINGER_LEFT, SWIPE_EVENT_TYPE_FOUR_FINGER_RIGHT
            };
        } else if (fingerCount == 3) {
            return new String[] {
                SWIPE_EVENT_TYPE_THREE_FINGER_UP, SWIPE_EVENT_TYPE_THREE_FINGER_DOWN,
                SWIPE_EVENT_TYPE_THREE_FINGER_LEFT, SWIPE_EVENT_TYPE_THREE_FINGER_RIGHT
            };
        }
        // fingerCount == 2
        return new String[] {
            SWIPE_EVENT_TYPE_TWO_FINGER_UP, SWIPE_EVENT_TYPE_TWO_FINGER_DOWN,
            SWIPE_EVENT_TYPE_TWO_FINGER_LEFT, SWIPE_EVENT_TYPE_TWO_FINGER_RIGHT
        };
    }
}
