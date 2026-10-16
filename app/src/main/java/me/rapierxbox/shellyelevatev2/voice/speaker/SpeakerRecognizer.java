package me.rapierxbox.shellyelevatev2.voice.speaker;

import static me.rapierxbox.shellyelevatev2.Constants.*;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMQTTServer;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import me.rapierxbox.shellyelevatev2.api.ApiHub;
import me.rapierxbox.shellyelevatev2.voice.VoiceEngine;
import me.rapierxbox.shellyelevatev2.voice.VoiceTap;

// who spoke a voice command. off means no tap on the engine no model and after a few seconds no thread
// a session is embedded twice: the wake phrase right away so home assistant can tell the
// conversation agent who speaks and then wake phrase plus command for the published result
public final class SpeakerRecognizer implements VoiceTap {
    private static final String TAG = "Speaker";

    private static final int BYTES_PER_S = SpeakerEmbedder.SAMPLE_RATE * 2;
    // one model window of command speech next to the wake phrase
    private static final int COMMAND_BYTES = 2 * BYTES_PER_S;
    private static final int MIN_COMMAND_BYTES = BYTES_PER_S / 2;
    private static final int THREADS = 2;
    // below the wake word and capture but above 10 which android 7 moves into the background cgroup
    private static final int THREAD_PRIORITY = 5;
    // the model costs tens of mb so it only stays loaded around voice use
    private static final long UNLOAD_MS = 120_000L;
    // a model switch or a copied sample is embedded one sample at a time with a pause in between
    private static final long EMBED_PAUSE_MS = 1_000L;
    private static final long DOWNLOAD_RETRY_MIN = 5;
    // cosine above which a match counts at strictness 50. librispeech dev-clean with 2 s crops
    // gave 1 % false accepts at 0.31 to 0.33 for all three models and an eer below 1 %
    private static final float THRESHOLD = 0.33f;
    // strictness 0 or 100 moves the threshold this much
    private static final float STRICTNESS_RANGE = 0.15f;
    // a match this far above the threshold in both stages becomes a learned sample
    private static final float LEARN_MARGIN = 0.15f;
    private static final int RECENT = 20;

    public static final class Result {
        public final long session;
        public final String id;
        public final String name;
        public final String person;
        public final float score;
        public final boolean known;
        public final long at = System.currentTimeMillis();
        final float[] emb;
        final String model;
        // the stt text the controller sent for this session
        public volatile String text;

        Result(long session, SpeakerMatcher.Match m, SpeakerStore.Profile p, float[] emb, String model) {
            this.session = session;
            this.id = m != null ? m.id : null;
            this.name = p != null ? p.name : null;
            this.person = p != null ? p.person : null;
            this.score = m != null ? m.score : 0f;
            this.known = m != null && m.known && p != null;
            this.emb = emb;
            this.model = model;
        }

        public JSONObject toJson() throws JSONException {
            return new JSONObject().put("session", session)
                    .put("status", known ? "identified" : "unknown")
                    .put("speaker_id", known ? id : JSONObject.NULL)
                    .put("name", known ? name : JSONObject.NULL)
                    .put("person", known ? person : JSONObject.NULL)
                    .put("best_guess", id != null ? id : JSONObject.NULL)
                    .put("score", rounded())
                    .put("at", at);
        }

        double rounded() {
            return Math.round(score * 1000) / 1000.0;
        }
    }

    private final VoiceEngine engine;
    private final File files;
    private final File modelDir;
    // serial so the model is only ever touched by one thread. its thread ends after a few idle seconds
    private final ScheduledThreadPoolExecutor executor;
    private volatile SpeakerStore store;
    private volatile boolean enabled;
    // set while the executor embeds a backlog so a wake does not promise a quick answer
    private volatile boolean busy;
    private volatile String state = "off";
    // what the next wake costs: the last embedding plus the last load while the model is unloaded
    private volatile long embedMs = 0;
    private volatile long loadMs = 0;
    private volatile boolean loaded;
    private volatile Result last;

    // executor thread
    private SpeakerEmbedder embedder;
    private String loadedModel;
    private ScheduledFuture<?> unloadFuture;
    private ScheduledFuture<?> retryFuture;
    private boolean downloading;
    private long wakeSession = -1;
    private Result wakeResult;

    // capture thread. allocated while on
    private volatile byte[] command;
    private int commandLen;
    // any thread
    private final ArrayDeque<Result> recent = new ArrayDeque<>();

    public SpeakerRecognizer(VoiceEngine engine) {
        this.engine = engine;
        files = mApplicationContext.getFilesDir();
        modelDir = new File(files, "speakermodels");
        executor = new ScheduledThreadPoolExecutor(1, r -> new Thread(() -> {
            Process.setThreadPriority(THREAD_PRIORITY);
            r.run();
        }, "SpeakerRecognizer"));
        executor.setKeepAliveTime(10, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        executor.setRemoveOnCancelPolicy(true);
        LocalBroadcastManager.getInstance(mApplicationContext).registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { apply(); }
        }, new IntentFilter(INTENT_SETTINGS_CHANGED));
        mApplicationContext.registerComponentCallbacks(new ComponentCallbacks2() {
            @Override public void onTrimMemory(int level) {
                if (level >= TRIM_MEMORY_RUNNING_LOW) run(SpeakerRecognizer.this::unload);
            }
            @Override public void onConfigurationChanged(Configuration newConfig) {}
            @Override public void onLowMemory() { run(SpeakerRecognizer.this::unload); }
        });
        apply();
    }

    public boolean isEnabled() {
        return enabled;
    }

    // the profiles are only read from disk once someone needs them
    public SpeakerStore store() {
        SpeakerStore s = store;
        if (s != null) return s;
        synchronized (this) {
            if (store == null) store = new SpeakerStore(new File(files, "speakers"));
            return store;
        }
    }

    public synchronized void apply() {
        boolean want = mSharedPreferences.getBoolean(SP_SPEAKER_ID_ENABLED, false) && VoiceEngine.isConfigured();
        if (want != enabled) {
            enabled = want;
            Log.i(TAG, want ? "on" : "off");
            if (!want) {
                engine.setTap(null);
                last = null;
                synchronized (recent) {
                    recent.clear();
                }
                executor.execute(() -> {
                    cancel(unloadFuture);
                    cancel(retryFuture);
                    unload();
                    if (!enabled) command = null;
                    wakeResult = null;
                    setState("off");
                });
                return;
            }
            if (command == null) command = new byte[COMMAND_BYTES];
            engine.setTap(this);
        }
        // fetches a newly picked model and embeds the enrolled samples with it ahead of the next wake
        if (want) executor.execute(this::prepare);
    }

    // ---------------------------------------------------------------- voice tap

    // only when someone is enrolled and nothing else keeps the executor busy
    @Override public boolean quickResult(byte[] preRoll) {
        return preRoll != null && "ready".equals(state) && !busy && !store().embeddings(currentModel()).isEmpty();
    }

    @Override public void onSessionStart(long session, byte[] preRoll, boolean pending) {
        commandLen = 0;
        run(() -> {
            wakeSession = session;
            wakeResult = null;
            if (preRoll == null) return;
            float[] emb = embed(preRoll, preRoll.length);
            Result r = emb != null ? match(session, emb) : null;
            wakeResult = r;
            if (!pending) return;
            try {
                JSONObject msg = r != null ? r.toJson() : new JSONObject().put("session", session).put("status", "none");
                ApiHub.send("voice.speaker", msg.put("stage", "wake"));
            } catch (JSONException ignored) {
            }
        });
    }

    @Override public void onAudio(long session, byte[] buf, int len, boolean speech) {
        byte[] cmd = command;
        if (!speech || cmd == null || commandLen >= cmd.length) return;
        int n = Math.min(len, cmd.length - commandLen);
        System.arraycopy(buf, 0, cmd, commandLen, n);
        commandLen += n;
    }

    @Override public void onSessionEnd(long session, boolean complete) {
        byte[] cmd = command;
        if (!complete || cmd == null) return;
        byte[] speech = commandLen >= MIN_COMMAND_BYTES ? Arrays.copyOf(cmd, commandLen) : null;
        run(() -> finish(session, speech));
    }

    // executor
    private void finish(long session, byte[] speech) {
        Result wake = wakeSession == session ? wakeResult : null;
        Result r;
        if (speech == null) {
            r = wake;
        } else {
            float[] emb = embed(speech, speech.length);
            if (emb == null) return;
            if (wake != null && wake.emb != null) emb = SpeakerMatcher.mean(wake.emb, emb);
            r = match(session, emb);
        }
        if (r == null) return;
        last = r;
        synchronized (recent) {
            if (recent.size() >= RECENT) recent.removeFirst();
            recent.addLast(r);
        }
        if (speech != null) learn(wake, r);
        ApiHub.stateChanged();
        try {
            JSONObject json = r.toJson();
            ApiHub.event("speaker", json);
            if (mMQTTServer != null) mMQTTServer.publishSpeakerEvent(json.put("event_type", json.get("status")).toString());
        } catch (JSONException ignored) {
        }
        Log.i(TAG, "session " + session + ": " + (r.known ? r.name : "unknown") + " " + r.score);
    }

    // ---------------------------------------------------------------- matching

    private Result match(long session, float[] emb) {
        SpeakerMatcher.Match m = SpeakerMatcher.best(store().embeddings(loadedModel), emb, threshold());
        return new Result(session, m, m != null ? store().get(m.id) : null, emb, loadedModel);
    }

    private float threshold() {
        int strictness = mSharedPreferences.getInt(SP_SPEAKER_ID_STRICTNESS, 50);
        return THRESHOLD + (strictness - 50) / 50f * STRICTNESS_RANGE;
    }

    // only when the wake phrase and the whole command agree with a wide margin
    private void learn(Result wake, Result r) {
        if (!mSharedPreferences.getBoolean(SP_SPEAKER_ID_LEARN, true)) return;
        if (wake == null || !wake.known || !r.known || !r.id.equals(wake.id)) return;
        float bar = threshold() + LEARN_MARGIN;
        if (wake.score < bar || r.score < bar) return;
        try {
            store().addSample(r.id, null, "free", SpeakerStore.SOURCE_LEARNED, null, r.model, r.emb);
        } catch (IOException e) {
            Log.w(TAG, "could not learn a sample: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- model

    // executor. null when the model is not there yet or memory is short
    private float[] embed(byte[] pcm, int len) {
        if (!enabled || !load()) return null;
        long start = SystemClock.elapsedRealtime();
        float[] emb = embedder.embed(pcm, 0, len);
        embedMs = SystemClock.elapsedRealtime() - start;
        cancel(unloadFuture);
        unloadFuture = executor.schedule(this::unload, UNLOAD_MS, TimeUnit.MILLISECONDS);
        return emb;
    }

    private boolean load() {
        String model = currentModel();
        if (embedder != null && model.equals(loadedModel)) return true;
        unload();
        File file = SpeakerModels.file(modelDir, model);
        if (!file.isFile()) return false;
        // loading tens of mb while the system is short would push the dashboard out
        ActivityManager am = (ActivityManager) mApplicationContext.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(memory);
        if (memory.lowMemory) {
            Log.w(TAG, "memory low, skipping speaker recognition");
            return false;
        }
        try {
            long start = SystemClock.elapsedRealtime();
            embedder = new SpeakerEmbedder(file, THREADS);
            loadMs = SystemClock.elapsedRealtime() - start;
            loadedModel = model;
            setLoaded(true);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "could not load " + file, e);
            setState("error");
            return false;
        }
    }

    private void unload() {
        if (embedder != null) embedder.close();
        embedder = null;
        setLoaded(false);
    }

    private void setLoaded(boolean value) {
        if (loaded == value) return;
        loaded = value;
        ApiHub.stateChanged();
    }

    // executor. downloads the model on its own thread then embeds one missing sample per step
    private void prepare() {
        if (!enabled || downloading) return;
        String model = currentModel();
        if (!SpeakerModels.file(modelDir, model).isFile()) {
            downloading = true;
            setState("downloading");
            new Thread(() -> {
                boolean ok = true;
                try {
                    SpeakerModels.ensure(modelDir, model);
                } catch (IOException e) {
                    Log.w(TAG, "model download failed: " + e.getMessage());
                    ok = false;
                }
                boolean done = ok;
                run(() -> {
                    downloading = false;
                    if (done) {
                        prepare();
                    } else {
                        setState("error");
                        cancel(retryFuture);
                        retryFuture = executor.schedule(this::prepare, DOWNLOAD_RETRY_MIN, TimeUnit.MINUTES);
                    }
                });
            }, "SpeakerModelDownload").start();
            return;
        }
        List<String[]> missing = store().missing(model);
        busy = !missing.isEmpty();
        if (!busy) {
            setState("ready");
            return;
        }
        setState("embedding");
        String[] s = missing.get(0);
        try {
            byte[] pcm = store().readPcm(s[0], s[1]);
            float[] emb = embed(pcm, pcm.length);
            if (emb == null) {
                // memory is short. the next settings change or added sample tries again
                busy = false;
                setState("ready");
                return;
            }
            store().putEmbedding(s[0], s[1], model, emb);
        } catch (IOException e) {
            Log.w(TAG, "dropping unreadable sample " + s[1] + ": " + e.getMessage());
            try {
                store().deleteSample(s[0], s[1]);
            } catch (IOException ignored) {
            }
        }
        executor.schedule(this::prepare, EMBED_PAUSE_MS, TimeUnit.MILLISECONDS);
    }

    private void setState(String s) {
        if (s.equals(state)) return;
        state = s;
        ApiHub.stateChanged();
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) future.cancel(false);
    }

    // ---------------------------------------------------------------- api

    private void run(Runnable task) {
        try {
            executor.execute(task);
        } catch (RuntimeException ignored) {
            // shutting down
        }
    }

    // runs on the executor and waits. throws while off
    private <T> T call(Callable<T> task) throws ApiHub.CommandException {
        if (!enabled) throw ApiHub.CommandException.unsupported("speaker recognition is off");
        try {
            return executor.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiHub.CommandException("busy", "interrupted");
        } catch (ExecutionException | RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof ApiHub.CommandException) throw (ApiHub.CommandException) cause;
            throw new ApiHub.CommandException("failed", String.valueOf(cause.getMessage()));
        }
    }

    // embeds pcm with the current model on the executor and waits
    public float[] embedNow(byte[] pcm) throws ApiHub.CommandException {
        float[] emb = call(() -> embed(pcm, pcm.length));
        if (emb == null) throw new ApiHub.CommandException("unavailable", "speaker model not ready");
        return emb;
    }

    public boolean modelReady() {
        return SpeakerModels.file(modelDir, currentModel()).isFile();
    }

    public String currentModel() {
        return SpeakerModels.id(mSharedPreferences.getString(SP_SPEAKER_ID_MODEL, SpeakerModels.DEFAULT));
    }

    // adds an enrolled sample. one without an embedding is embedded in the background
    public void addEnrolled(String speakerId, String sampleId, String kind, byte[] pcm, float[] emb) throws IOException {
        store().addSample(speakerId, sampleId, kind, SpeakerStore.SOURCE_ENROLLED, pcm, currentModel(), emb);
        if (emb == null) run(this::prepare);
        ApiHub.stateChanged();
    }

    public List<Result> recent() throws ApiHub.CommandException {
        if (!enabled) throw ApiHub.CommandException.unsupported("speaker recognition is off");
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    public void label(long session, String text) {
        synchronized (recent) {
            for (Result r : recent) if (r.session == session) r.text = text;
        }
    }

    // a recent voice was this speaker
    public void assign(long session, String speakerId) throws ApiHub.CommandException {
        Result found = null;
        for (Result r : recent()) if (r.session == session) found = r;
        if (found == null) throw ApiHub.CommandException.invalid("no recent voice for session " + session);
        try {
            store().addSample(speakerId, null, "free", SpeakerStore.SOURCE_CLAIMED, null, found.model, found.emb);
        } catch (IOException e) {
            throw new ApiHub.CommandException("failed", e.getMessage());
        }
        ApiHub.stateChanged();
    }

    public void contributeState(Map<String, Object> s) {
        s.put("speaker.state", state);
        if (!enabled) return;
        Result r = last;
        s.put("speaker.model", currentModel());
        s.put("speaker.latency_ms", embedMs + (loaded ? 0 : loadMs));
        s.put("speaker.last", r == null ? null : r.known ? r.name : "unknown");
        s.put("speaker.last_id", r != null && r.known ? r.id : null);
        s.put("speaker.person", r != null && r.known ? r.person : null);
        s.put("speaker.score", r != null ? r.rounded() : null);
        s.put("speaker.at", r != null ? r.at : null);
        s.put("speaker.rev", store().rev());
    }
}
