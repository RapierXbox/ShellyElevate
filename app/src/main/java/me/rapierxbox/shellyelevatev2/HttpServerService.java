package me.rapierxbox.shellyelevatev2;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.legacy.content.WakefulBroadcastReceiver;

import java.io.IOException;

public class HttpServerService extends Service {
    private HttpServer server;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        server = new HttpServer(this);
        try {
            server.start();
            Log.i("HttpServerService", "HTTP server started");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        WakefulBroadcastReceiver.completeWakefulIntent(intent);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.onDestroy();
            server.stop();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
