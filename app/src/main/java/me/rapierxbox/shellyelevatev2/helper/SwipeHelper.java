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
    // smaller screen side in pixels which every classifier threshold scales with
    private final float screenPx = Math.min(
        mApplicationContext.getResources().getDisplayMetrics().widthPixels,
        mApplicationContext.getResources().getDisplayMetrics().heightPixels
    );

    // fingers down right now by pointer id
    private final SparseArray<PointerInfo> pointers = new SparseArray<>();
    // fingers that already lifted. android reuses a lifted pointer id for the next finger
    // so a finger that drops out and lands again must not overwrite the track it had
    private final List<PointerInfo> lifted = new ArrayList<>();
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
        lifted.clear();
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
                pointers.put(pid,new PointerInfo(event.getX(actionIndex), event.getY(actionIndex)));
                if (event.getPointerCount() > maxPointerCount) maxPointerCount = event.getPointerCount();
                lastPointerJoinTime = event.getEventTime();
                break;
            }

            case MotionEvent.ACTION_MOVE:
                updateEnds(event);
                break;

            case MotionEvent.ACTION_POINTER_UP: {
                updateEnds(event);
                int pid = event.getPointerId(actionIndex);
                PointerInfo p = pointers.get(pid);
                if (p != null) {
                    lifted.add(p);
                    pointers.remove(pid);
                }
                break;
            }

            case MotionEvent.ACTION_UP:
                updateEnds(event);
                evaluate(event.getEventTime());
                clearState();
                break;

            case MotionEvent.ACTION_CANCEL:
                clearState();
                break;
        }

        return true;
    }

    // keeps the last known position of every finger so a missed lift event loses nothing
    private void updateEnds(MotionEvent event) {
        for (int i = 0; i < event.getPointerCount(); i++) {
            PointerInfo p = pointers.get(event.getPointerId(i));
            if (p != null) {
                p.endX = event.getX(i);
                p.endY = event.getY(i);
            }
        }
    }

    private void evaluate(long endTime) {
        // duration runs from the last finger joining so a late finger does not dilute the speed
        long refTime = (lastPointerJoinTime > 0) ? lastPointerJoinTime : gestureStartTime;
        long totalTime = Math.max(1, endTime - refTime);

        List<SwipeClassifier.Track> tracks = new ArrayList<>(lifted.size() + pointers.size());
        for (PointerInfo p : lifted) {
            tracks.add(new SwipeClassifier.Track(p.startX, p.startY, p.endX, p.endY));
        }
        for (int i = 0; i < pointers.size(); i++) {
            PointerInfo p = pointers.valueAt(i);
            tracks.add(new SwipeClassifier.Track(p.startX, p.startY, p.endX, p.endY));
        }
        SwipeClassifier.Swipe swipe = SwipeClassifier.classify(tracks, maxPointerCount, totalTime, screenPx);
        if (swipe != null) SwipeActions.dispatch(swipe);
    }
}
