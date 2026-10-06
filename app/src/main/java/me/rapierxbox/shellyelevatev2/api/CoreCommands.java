package me.rapierxbox.shellyelevatev2.api;

import static me.rapierxbox.shellyelevatev2.Constants.DISPLAY_MODULE_WEBVIEW;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_WEBVIEW_INJECT_JAVASCRIPT;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_WEBVIEW_REFRESH;
import static me.rapierxbox.shellyelevatev2.Constants.SP_AUTOMATIC_BRIGHTNESS;
import static me.rapierxbox.shellyelevatev2.Constants.SP_BRIGHTNESS;
import static me.rapierxbox.shellyelevatev2.Constants.SP_WEBVIEW_URL;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mNightModeManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;

import me.rapierxbox.shellyelevatev2.DeviceModel;
import me.rapierxbox.shellyelevatev2.MainActivity;
import me.rapierxbox.shellyelevatev2.display.DisplayModuleRegistry;
import me.rapierxbox.shellyelevatev2.helper.AppUpdater;
import me.rapierxbox.shellyelevatev2.helper.RebootHelper;

// the commands of protocol-v1 section 6 that map onto existing managers
// voice and media register their own commands
final class CoreCommands {
    private static final String TAG = "ApiCommands";

    private CoreCommands() {}

    static void register() {
        ApiHub.registerCommand("relay.set", CoreCommands::relaySet);
        ApiHub.registerCommand("dimmer.set", CoreCommands::dimmerSet);
        ApiHub.registerCommand("screen.wake", p -> {
            if (mScreenSaverManager != null) mScreenSaverManager.stopScreenSaver();
            ApiHub.stateChanged();
            return null;
        });
        ApiHub.registerCommand("screen.sleep", p -> {
            if (mScreenSaverManager != null) mScreenSaverManager.startScreenSaver();
            ApiHub.stateChanged();
            return null;
        });
        ApiHub.registerCommand("screen.set", CoreCommands::screenSet);
        ApiHub.registerCommand("night_mode.set", p -> {
            boolean on = requireBoolean(p, "on");
            if (mNightModeManager == null) throw ApiHub.CommandException.unsupported("night mode unavailable");
            mNightModeManager.setEnabled(on);
            ApiHub.stateChanged();
            return null;
        });
        ApiHub.registerCommand("webview.reload", p -> {
            requireWebview();
            LocalBroadcastManager.getInstance(mApplicationContext).sendBroadcast(new Intent(INTENT_WEBVIEW_REFRESH));
            return null;
        });
        ApiHub.registerCommand("webview.navigate", CoreCommands::webviewNavigate);
        ApiHub.registerCommand("ui.notify", CoreCommands::notify);
        ApiHub.registerCommand("device.reboot", p -> {
            if (!RebootHelper.rebootUnlessJustStarted(mApplicationContext)) {
                throw new ApiHub.CommandException("busy", "the display just started");
            }
            return null;
        });
        ApiHub.registerCommand("app.restart", p -> {
            restartApp();
            return null;
        });
        ApiHub.registerCommand("app.update", CoreCommands::appUpdate);
    }

    private static JSONObject relaySet(JSONObject p) throws ApiHub.CommandException {
        int index = requireInt(p, "index");
        boolean on = requireBoolean(p, "on");
        if (index < 0 || index >= DeviceModel.getReportedDevice().relays) {
            throw ApiHub.CommandException.invalid("no relay " + index);
        }
        mDeviceHelper.setRelay(index, on);
        ApiHub.stateChanged();
        return null;
    }

    private static JSONObject dimmerSet(JSONObject p) throws ApiHub.CommandException {
        if (mDeviceHelper == null || !mDeviceHelper.isDimmerAttached()) {
            throw ApiHub.CommandException.unsupported("no dimmer attached");
        }
        if (p.has("brightness")) {
            int brightness = requireInt(p, "brightness");
            mDeviceHelper.setDimmerBrightness(Math.max(0, Math.min(100, brightness)), ApiHub::stateChanged);
        }
        if (p.has("on")) {
            boolean on = requireBoolean(p, "on");
            // a brightness above zero already switches it on
            if (!on || !p.has("brightness")) mDeviceHelper.setDimmerOn(on);
        }
        if (!p.has("brightness") && !p.has("on")) throw ApiHub.CommandException.invalid("on or brightness required");
        ApiHub.stateChanged();
        return null;
    }

    private static JSONObject screenSet(JSONObject p) throws ApiHub.CommandException {
        if (!p.has("brightness") && !p.has("auto")) throw ApiHub.CommandException.invalid("brightness or auto required");
        android.content.SharedPreferences.Editor editor = mSharedPreferences.edit();
        if (p.has("brightness")) {
            int brightness = Math.max(0, Math.min(255, requireInt(p, "brightness")));
            editor.putInt(SP_BRIGHTNESS, brightness);
            // a brightness from the controller is a manual value unless auto is asked for as well
            if (!p.has("auto")) editor.putBoolean(SP_AUTOMATIC_BRIGHTNESS, false);
        }
        if (p.has("auto")) editor.putBoolean(SP_AUTOMATIC_BRIGHTNESS, requireBoolean(p, "auto"));
        editor.apply();
        if (mScreenManager != null) mScreenManager.reapplyBrightness();
        ApiHub.stateChanged();
        return null;
    }

    private static JSONObject webviewNavigate(JSONObject p) throws ApiHub.CommandException {
        requireWebview();
        String url = p.optString("url", "");
        String path = p.optString("path", "");
        String target;
        if (!url.isEmpty()) {
            target = url;
        } else if (!path.isEmpty()) {
            target = resolve(mSharedPreferences.getString(SP_WEBVIEW_URL, ""), path);
        } else {
            throw ApiHub.CommandException.invalid("url or path required");
        }
        String scheme = URI.create(target.replace(" ", "%20")).getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw ApiHub.CommandException.invalid("only http and https urls");
        }
        // the page navigates itself so the next reload returns to the configured dashboard
        Intent intent = new Intent(INTENT_WEBVIEW_INJECT_JAVASCRIPT);
        intent.putExtra("javascript", "window.location.href = " + JSONObject.quote(target) + ";");
        LocalBroadcastManager.getInstance(mApplicationContext).sendBroadcast(intent);
        return null;
    }

    static String resolve(String base, String path) throws ApiHub.CommandException {
        if (base == null || base.isEmpty()) throw ApiHub.CommandException.invalid("no dashboard url to resolve the path against");
        try {
            return new URI(base).resolve(path.startsWith("/") ? path : "/" + path).toString();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw ApiHub.CommandException.invalid("bad path " + path);
        }
    }

    private static JSONObject notify(JSONObject p) throws ApiHub.CommandException {
        String message = p.optString("message", "");
        if (message.isEmpty()) throw ApiHub.CommandException.invalid("message required");
        String title = p.optString("title", "");
        double duration = p.optDouble("duration", 5);
        String level = p.optString("level", "info");
        String text = title.isEmpty() ? message : title + "\n" + message;
        new Handler(Looper.getMainLooper()).post(() -> {
            // alerts wake the screen so they are seen
            if ("alert".equals(level) && mScreenSaverManager != null) mScreenSaverManager.stopScreenSaver();
            Toast.makeText(mApplicationContext, text, duration > 3 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
        });
        return null;
    }

    private static void restartApp() {
        Context context = mApplicationContext;
        Intent launch = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pending = PendingIntent.getActivity(context, 4711, launch,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) {
            alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1500, pending);
        }
        // exit after the reply went out
        new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "ApiRestart").start();
    }

    private static JSONObject appUpdate(JSONObject p) throws ApiHub.CommandException {
        String url = p.optString("url", "");
        String version = p.optString("version", "");
        if (url.isEmpty() || version.isEmpty()) throw ApiHub.CommandException.invalid("url and version required");
        if (!ApiInfo.canSelfUpdate(mApplicationContext)) {
            throw ApiHub.CommandException.unsupported("self update needs the privileged install");
        }
        if (AppUpdater.isInProgress()) throw new ApiHub.CommandException("busy", "an update is already running");
        String sha256 = p.optString("sha256", "");
        AppUpdater.installFromUrl(mApplicationContext, url, version, sha256.isEmpty() ? null : sha256,
                new AppUpdater.InstallListener() {
                    @Override public void onProgress(int percent) {}
                    @Override public void onInstalling() {
                        Log.i(TAG, "Installing " + version);
                    }
                    @Override public void onFailed(String reason) {
                        Log.w(TAG, "Update failed: " + reason);
                    }
                    @Override public void onCancelled() {}
                });
        try {
            return new JSONObject().put("started", true);
        } catch (JSONException e) {
            return null;
        }
    }

    private static void requireWebview() throws ApiHub.CommandException {
        if (!DISPLAY_MODULE_WEBVIEW.equals(DisplayModuleRegistry.activeId(mSharedPreferences))) {
            throw ApiHub.CommandException.unsupported("the webview module is not active");
        }
    }

    static int requireInt(JSONObject p, String key) throws ApiHub.CommandException {
        Object value = p.opt(key);
        if (value instanceof Number) return ((Number) value).intValue();
        throw ApiHub.CommandException.invalid(key + " must be a number");
    }

    static boolean requireBoolean(JSONObject p, String key) throws ApiHub.CommandException {
        Object value = p.opt(key);
        if (value instanceof Boolean) return (Boolean) value;
        throw ApiHub.CommandException.invalid(key + " must be true or false");
    }
}
