package me.rapierxbox.shellyelevatev2.helper;

import static android.content.Context.MODE_PRIVATE;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_SETTINGS_CHANGED;
import static me.rapierxbox.shellyelevatev2.Constants.SHARED_PREFERENCES_NAME;
import static me.rapierxbox.shellyelevatev2.Constants.SP_NIGHT_MODE_ENABLED;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMQTTServer;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.WeakHashMap;

public class NightModeManager implements Application.ActivityLifecycleCallbacks {

    private static final String TAG = "NightModeManager";

    private final Context appContext;
    private final SharedPreferences prefs;
    private final WeakHashMap<Activity, NightModeOverlayView> overlays = new WeakHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean enabled;

    public NightModeManager(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(SHARED_PREFERENCES_NAME, MODE_PRIVATE);
        this.enabled = prefs.getBoolean(SP_NIGHT_MODE_ENABLED, false);

        BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean newState = prefs.getBoolean(SP_NIGHT_MODE_ENABLED, false);
                if (newState != enabled) {
                    enabled = newState;
                    refreshOverlays();
                }
            }
        };
        LocalBroadcastManager.getInstance(appContext)
                .registerReceiver(settingsReceiver, new IntentFilter(INTENT_SETTINGS_CHANGED));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        if (value == enabled && prefs.getBoolean(SP_NIGHT_MODE_ENABLED, false) == value) {
            return;
        }
        enabled = value;
        prefs.edit().putBoolean(SP_NIGHT_MODE_ENABLED, value).apply();
        refreshOverlays();
        LocalBroadcastManager.getInstance(appContext)
                .sendBroadcast(new Intent(INTENT_SETTINGS_CHANGED));
        if (mMQTTServer != null) {
            mMQTTServer.publishNightModeState();
        }
    }

    public void onDestroy() {
        for (java.util.Map.Entry<Activity, NightModeOverlayView> e : overlays.entrySet()) {
            detachOverlay(e.getKey(), e.getValue());
        }
        overlays.clear();
    }

    private void refreshOverlays() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::refreshOverlays);
            return;
        }
        int vis = enabled ? View.VISIBLE : View.GONE;
        for (NightModeOverlayView v : overlays.values()) {
            if (v == null) continue;
            if (v.getVisibility() != vis) v.setVisibility(vis);
            if (enabled) v.invalidate();
        }
    }

    private void attachOverlay(Activity activity) {
        if (overlays.containsKey(activity)) return;
        try {
            View decor = activity.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) {
                Log.w(TAG, "decorView is not a ViewGroup; cannot attach night mode overlay");
                return;
            }
            ViewGroup root = (ViewGroup) decor;
            NightModeOverlayView overlay = new NightModeOverlayView(activity);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            overlay.setLayoutParams(lp);
            overlay.setClickable(false);
            overlay.setFocusable(false);
            overlay.setFocusableInTouchMode(false);
            overlay.setVisibility(enabled ? View.VISIBLE : View.GONE);
            // added last so it stays on top
            root.addView(overlay);
            overlays.put(activity, overlay);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to attach night mode overlay", t);
        }
    }

    private void detachOverlay(Activity activity, NightModeOverlayView overlay) {
        if (overlay == null) return;
        try {
            ViewGroup parent = (ViewGroup) overlay.getParent();
            if (parent != null) parent.removeView(overlay);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to detach night mode overlay", t);
        }
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        attachOverlay(activity);
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        // reattach if a config change recreated the window
        if (!overlays.containsKey(activity)) {
            attachOverlay(activity);
        }
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (!enabled) return;
        NightModeOverlayView v = overlays.get(activity);
        if (v != null) v.bringToFront();
    }

    @Override public void onActivityPaused(@NonNull Activity activity) {}
    @Override public void onActivityStopped(@NonNull Activity activity) {}
    @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        NightModeOverlayView overlay = overlays.remove(activity);
        detachOverlay(activity, overlay);
    }

    private class NightModeOverlayView extends View {
        NightModeOverlayView(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!enabled) return;
            // red MULTIPLY zeroes the green and blue channels
            canvas.drawColor(Color.RED, PorterDuff.Mode.MULTIPLY);
        }
    }
}
