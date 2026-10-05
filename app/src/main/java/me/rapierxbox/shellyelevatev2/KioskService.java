package me.rapierxbox.shellyelevatev2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import me.rapierxbox.shellyelevatev2.display.DisplayController;

// foreground anchor that keeps the process alive and brings the display module back when it goes away
public class KioskService extends Service {
	private static final String TAG = "KioskService";

	private static final String CHANNEL_ID = "kiosk_channel";
	private static final int NOTIFICATION_ID = 1;
	private HandlerThread watchdogThread;
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

	// runs on its own thread since the foreground check may shell out to dumpsys
	private void startWatchdog() {
		if (watchdogHandler != null) return;
		watchdogThread = new HandlerThread("KioskWatchdog");
		watchdogThread.start();
		watchdogHandler = new Handler(watchdogThread.getLooper());
		watchdogTask = new Runnable() {
			@Override
			public void run() {
				try {
					DisplayController.watchdogCheck(KioskService.this);
				} catch (Exception e) {
					Log.e(TAG, "Watchdog check failed", e);
				}
				// onDestroy may have cleared the field while this ran
				Handler handler = watchdogHandler;
				if (handler != null) handler.postDelayed(this, nextInterval());
			}
		};
		watchdogHandler.postDelayed(watchdogTask, nextInterval());
	}

	// an external app module is checked more often than the in process webview
	private long nextInterval() {
		return DisplayController.activeModule(this).getWatchdogIntervalMs();
	}

	@Override
	public void onDestroy() {
		// without this a recreated service stacks another watchdog loop
		if (watchdogHandler != null) {
			watchdogHandler.removeCallbacksAndMessages(null);
			watchdogHandler = null;
			watchdogTask = null;
		}
		if (watchdogThread != null) {
			watchdogThread.quitSafely();
			watchdogThread = null;
		}
		super.onDestroy();
	}
}
