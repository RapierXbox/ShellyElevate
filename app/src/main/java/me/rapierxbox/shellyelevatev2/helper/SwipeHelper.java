package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;

import android.util.SparseArray;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;

import me.rapierxbox.shellyelevatev2.helper.touch.SwipeActions;
import me.rapierxbox.shellyelevatev2.helper.touch.SwipeClassifier;

// collects the pointers of an in app gesture and hands the finished gesture to SwipeClassifier
// touches over other apps reach the same classifier through TouchGestureMonitor
public class SwipeHelper {
    // distance is raw pixels so it is tied to display density
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
        // velocity is measured from the last finger-join timestamp for multi-touch
        // and from gestureStartTime for single-finger gestures
        long refTime = (lastPointerJoinTime > 0) ? lastPointerJoinTime : gestureStartTime;
        long totalTime = Math.max(1, endTime - refTime);

        List<SwipeClassifier.Track> tracks = new ArrayList<>(pointers.size());
        for (int i = 0; i < pointers.size(); i++) {
            PointerInfo p = pointers.valueAt(i);
            tracks.add(new SwipeClassifier.Track(p.startX, p.startY, p.endX, p.endY));
        }
        SwipeClassifier.Swipe swipe = SwipeClassifier.classify(tracks, maxPointerCount, totalTime,
                minDistancePx, SwipeClassifier.MIN_VELOCITY_PX_MS);
        if (swipe != null) SwipeActions.dispatch(swipe);
    }
}
