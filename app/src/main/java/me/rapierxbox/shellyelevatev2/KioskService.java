package me.rapierxbox.shellyelevatev2;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

// foreground anchor that keeps the process alive and brings the kiosk ui back when it goes away
public class KioskService extends Service {
	private static final String TAG = "KioskService";

	private static final String CHANNEL_ID = "kiosk_channel";
	private static final int NOTIFICATION_ID = 1;
	private static final long WATCHDOG_INTERVAL_MS = 30_000;

	private Handler watchdogHandler;
	private Runnable watchdogTask;

	@Override
	public void onCreate() {
		super.onCreate();
		ensureNotificationChannel();
		Log.i(TAG, "Foreground service created");
		startForeground(NOTIFICATION_ID, buildNotification());

		startWatchdog();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		return START_STICKY;
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	private Notification buildNotification() {
		return new NotificationCompat.Builder(this, CHANNEL_ID)
				.setContentTitle("Kiosk running")
				.setContentText("Foreground anchor active")
				.setSmallIcon(R.drawable.ic_launcher_foreground)
				.build();
	}

	private void ensureNotificationChannel() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

		NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if (manager == null) return;

		NotificationChannel channel = new NotificationChannel(
				CHANNEL_ID,
				"Kiosk",
				NotificationManager.IMPORTANCE_LOW
		);
		channel.setDescription("Keeps the kiosk foreground service alive");
		manager.createNotificationChannel(channel);
	}

	private void startWatchdog() {
		if (watchdogHandler != null) return;
		watchdogHandler = new Handler(Looper.getMainLooper());
		watchdogTask = new Runnable() {
			@Override
			public void run() {
				checkKioskActivity();
				watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
			}
		};
		watchdogHandler.postDelayed(watchdogTask, WATCHDOG_INTERVAL_MS);
	}

	private void checkKioskActivity() {
		if (isLiteModeEnabled()) {
			Log.i(TAG, "Lite mode enabled, skipping MainActivity relaunch");
			return;
		}
		if (isOwnActivityOnTop()) return;

		Log.w(TAG, "MainActivity not running, relaunching...");
		Intent activityIntent = new Intent(this, MainActivity.class);
		activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		startActivity(activityIntent);
	}

	@Override
	public void onDestroy() {
		// without this a recreated service stacks another watchdog loop
		if (watchdogHandler != null) {
			watchdogHandler.removeCallbacksAndMessages(null);
			watchdogHandler = null;
			watchdogTask = null;
		}
		super.onDestroy();
	}

	private boolean isLiteModeEnabled() {
		SharedPreferences prefs = getSharedPreferences(Constants.SHARED_PREFERENCES_NAME, MODE_PRIVATE);
		return prefs.getBoolean(Constants.SP_LITE_MODE, false);
	}

	// the settings screen counts as in kiosk so the watchdog does not yank the user out of it
	private boolean isOwnActivityOnTop() {
		ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
		if (am == null) return false;
		String mainName = MainActivity.class.getName();
		String settingsName = SettingsActivity.class.getName();
		for (ActivityManager.AppTask task : am.getAppTasks()) {
			try {
				ComponentName top = task.getTaskInfo().topActivity;
				if (top == null) continue;
				String topName = top.getClassName();
				if (mainName.equals(topName) || settingsName.equals(topName)) return true;
			} catch (Exception e) {
				// the task can vanish between listing and querying it
				Log.d(TAG, "Skipping app task", e);
			}
		}
		return false;
	}
}
