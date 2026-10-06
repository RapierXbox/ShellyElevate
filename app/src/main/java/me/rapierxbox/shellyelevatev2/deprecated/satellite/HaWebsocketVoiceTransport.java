package me.rapierxbox.shellyelevatev2.deprecated.satellite;

import static me.rapierxbox.shellyelevatev2.Constants.SP_VOICE_ASSISTANT_PIPELINE_ID;
import static me.rapierxbox.shellyelevatev2.Constants.SP_VOICE_ASSISTANT_TOKEN;
import static me.rapierxbox.shellyelevatev2.Constants.SP_WEBVIEW_URL;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.util.Log;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import me.rapierxbox.shellyelevatev2.deprecated.DeprecatedFeatures;
import me.rapierxbox.shellyelevatev2.voice.VoiceTransport;
import okhttp3.OkHttpClient;

// the own home assistant satellite: assist pipeline over the ha websocket with a long lived token
/**
 * @deprecated since 3.26279, replaced by the Shelly Elevate Home Assistant integration (haVoiceEnabled),
 * removal planned in a later release
 */
@Deprecated
public class HaWebsocketVoiceTransport implements VoiceTransport {
    private static final String TAG = "HaVoiceTransport";

    private final OkHttpClient okHttpClient;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger reconnectDelaySec = new AtomicInteger(5);
    private volatile HAVoicePipeline pipeline;
    private volatile Listener listener;
    private volatile boolean closed = false;

    public HaWebsocketVoiceTransport() {
        okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    // true when the prefs allow this transport to run
    public static boolean isConfigured() {
        return !mSharedPreferences.getString(SP_VOICE_ASSISTANT_TOKEN, "").isEmpty()
                && !mSharedPreferences.getString(SP_WEBVIEW_URL, "").isEmpty();
    }

    @Override
    public void open(Listener listener) {
        this.listener = listener;
        DeprecatedFeatures.warnOnce(DeprecatedFeatures.Feature.HA_SATELLITE);
        connect();
    }

    private void connect() {
        if (closed) return;
        String haUrl = mSharedPreferences.getString(SP_WEBVIEW_URL, "");
        String token = mSharedPreferences.getString(SP_VOICE_ASSISTANT_TOKEN, "");
        if (haUrl.isEmpty() || token.isEmpty()) { Log.w(TAG, "missing URL or token"); return; }

        HAVoicePipeline previous = pipeline;
        if (previous != null) previous.close();
        pipeline = new HAVoicePipeline(okHttpClient, new HAVoicePipeline.Callback() {
            @Override public void onConnected() {}

            @Override public void onAuthOk() {
                Log.i(TAG, "authenticated, ready");
                reconnectDelaySec.set(5);
                Listener l = listener;
                if (l != null && !closed) l.onReady();
            }

            @Override public void onListening() { Log.d(TAG, "streaming audio to HA"); }

            @Override public void onTranscript(String text) {
                Listener l = listener;
                if (l != null && !closed) l.onTranscript(text);
            }

            @Override public void onResponse(String text) {
                Listener l = listener;
                if (l != null && !closed) l.onResponse(text);
            }

            @Override public void onSpeechEnd() {
                Listener l = listener;
                if (l != null && !closed) l.onSpeechEnd();
            }

            @Override public void onTtsUrl(String url) {
                Listener l = listener;
                if (l != null && !closed) l.onTts(url, null);
            }

            @Override public void onPipelineEnd() {
                Listener l = listener;
                if (l != null && !closed) l.onSessionEnd();
            }

            @Override public void onError(String message) {
                Listener l = listener;
                if (l != null && !closed) l.onError(message);
            }

            @Override public void onDisconnected() {
                Log.w(TAG, "disconnected");
                Listener l = listener;
                if (l != null && !closed) l.onDisconnected(false);
                scheduleReconnect();
            }
        });
        pipeline.connect(haUrl, token);
    }

    private void scheduleReconnect() {
        if (closed) return;
        int delay = reconnectDelaySec.getAndUpdate(cur -> Math.min(cur * 2, 60));
        Log.i(TAG, "reconnecting in " + delay + "s");
        try {
            scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // closed meanwhile
        }
    }

    @Override
    public boolean isReady() {
        HAVoicePipeline p = pipeline;
        return p != null && p.isAuthenticated();
    }

    @Override
    public String unavailableMessage() {
        Log.w(TAG, "trigger: not authenticated");
        return null;
    }

    @Override
    public void startSession(String wakeWordId, String phrase, boolean remoteInitiated) {
        HAVoicePipeline p = pipeline;
        if (p == null) return;
        String pipelineId = mSharedPreferences.getString(SP_VOICE_ASSISTANT_PIPELINE_ID, "");
        p.startPipeline(pipelineId.isEmpty() ? null : pipelineId);
    }

    @Override
    public void sendAudio(byte[] buf, int len) {
        HAVoicePipeline p = pipeline;
        if (p != null) p.sendAudio(buf, len);
    }

    @Override
    public void endAudio() {
        HAVoicePipeline p = pipeline;
        if (p != null) p.endAudio();
    }

    @Override
    public void onTtsFinished() {
        // the ha pipeline does not wait for playback
    }

    @Override
    public void close() {
        closed = true;
        listener = null;
        scheduler.shutdownNow();
        HAVoicePipeline p = pipeline;
        if (p != null) { p.close(); pipeline = null; }
        okHttpClient.dispatcher().executorService().shutdown();
        okHttpClient.connectionPool().evictAll();
    }
}
