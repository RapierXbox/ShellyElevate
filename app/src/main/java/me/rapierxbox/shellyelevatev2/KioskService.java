package me.rapierxbox.shellyelevatev2;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.List;

public class KioskService extends Service {
	private static final String WATCHDOG_TAG = "KioskService";

	@Override
	public void onCreate() {
		super.onCreate();
		ensureNotificationChannel();
		Log.i("KioskService", "Foreground service created");
		startForeground(1, buildNotification());

		// Kick off watchdog loop
		startWatchdog();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		return START_STICKY; // restart if killed
	}

	@Override
	public IBinder onBind(Intent intent) { return null; }

	private Notification buildNotification() {
		// On Android 8+, you must create a channel first
		return new NotificationCompat.Builder(this, "kiosk_channel")
				.setContentTitle("Kiosk running")
				.setContentText("Foreground anchor active")
				.setSmallIcon(R.drawable.ic_launcher_foreground)
				.build();
	}

	private void ensureNotificationChannel() {
		if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
			return;
		}

		NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if (manager == null) {
			return; // Avoid crashing if the service is unavailable
		}

		NotificationChannel channel = new NotificationChannel(
				"kiosk_channel",
				"Kiosk",
				NotificationManager.IMPORTANCE_LOW
		);
		channel.setDescription("Keeps the kiosk foreground service alive");
		manager.createNotificationChannel(channel);
	}

	private void startWatchdog() {
		Handler handler = new Handler(Looper.getMainLooper());
		Runnable checkTask = new Runnable() {
			@Override
			public void run() {
				if (isLiteModeEnabled()) {
					Log.i(WATCHDOG_TAG, "Lite mode enabled, skipping MainActivity relaunch");
					handler.postDelayed(this, 30000);
					return;
				}

				if (!isActivityAtTop(MainActivity.class) && !isActivityAtTop(SettingsActivity.class)) {
					Log.w("KioskService", "MainActivity not running, relaunching...");
					Intent activityIntent = new Intent(KioskService.this, MainActivity.class);
					activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
					startActivity(activityIntent);
				}
				handler.postDelayed(this, 30000); // check every 30s (increased from 10s)
			}
		};
		handler.postDelayed(checkTask, 30000); // initial delay 30s
	}

	private boolean isLiteModeEnabled() {
		SharedPreferences prefs = getSharedPreferences(Constants.SHARED_PREFERENCES_NAME, MODE_PRIVATE);
		return prefs.getBoolean(Constants.SP_LITE_MODE, false);
	}

	private boolean isActivityRunning(Class<?> activityClass) {
		ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
		if (am != null) {
			List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(Integer.MAX_VALUE);
			for (ActivityManager.RunningTaskInfo task : tasks) {
				if (task.topActivity != null &&
						task.topActivity.getClassName().equals(activityClass.getName())) {
					return true;
				}
			}
		}
		return false;
	}
}
