package me.rapierxbox.shellyelevatev2.helper;

import android.view.MotionEvent;

public class SwipeHelper{
    private float touchStartY = 0;
    private long touchStartEventTime = 0;

    public float minVel = 1.35F;
    public float minDist = 250.0F;
    private final Runnable action;

    public SwipeHelper(Runnable runnable){
        this.action = runnable;
    }

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
                if (velocity > minVel && deltaY > minDist){
                    this.action.run();
                    return false;
                }
        }
        return true;
    }
}
