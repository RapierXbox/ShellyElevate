package me.rapierxbox.shellyelevatev2.api;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import me.rapierxbox.shellyelevatev2.R;
import me.rapierxbox.shellyelevatev2.voice.VoiceEngine;
import me.rapierxbox.shellyelevatev2.voice.VoiceTransport;

// voice through the paired controller (protocol v1 section 5 and 6)
// the display sends voice.wake then mic audio on channel 0x01 and the controller runs the pipeline
public class ControllerVoiceTransport implements VoiceTransport, ApiHub.ControllerListener {
    private static final String TAG = "ControllerVoice";

    public static final int CHANNEL_AUDIO = 0x01;

    private volatile Listener listener;
    // true between a session start and voice.audio_end or voice.stop_audio
    private final AtomicBoolean streaming = new AtomicBoolean(false);

    @Override
    public void open(Listener listener) {
        this.listener = listener;
        ApiHub.addControllerListener(this);
        // wake word detection works without a controller and a session then reports it
        listener.onReady();
    }

    @Override
    public boolean isReady() {
        return ApiHub.hasController();
    }

    @Override
    public String unavailableMessage() {
        return mApplicationContext.getString(R.string.voice_ha_not_connected);
    }

    @Override
    public void startSession(String wakeWordId, String phrase, boolean remoteInitiated) {
        streaming.set(true);
        // the controller already runs a pipeline when it asked for the session
        if (remoteInitiated) return;
        try {
            JSONObject payload = new JSONObject();
            if (wakeWordId != null && !wakeWordId.isEmpty()) payload.put("wake_word_id", wakeWordId);
            if (phrase != null && !phrase.isEmpty()) payload.put("phrase", phrase);
            ApiHub.send("voice.wake", payload);
        } catch (JSONException e) {
            Log.w(TAG, "Could not build voice.wake", e);
        }
    }

    @Override
    public void sendAudio(byte[] buf, int len) {
        if (streaming.get()) ApiHub.sendBinary(CHANNEL_AUDIO, buf, 0, len);
    }

    @Override
    public void endAudio() {
        // after voice.stop_audio the controller already ended the stream
        if (streaming.getAndSet(false)) ApiHub.send("voice.audio_end", null);
    }

    // the controller told us to stop streaming
    public void stopAudio() {
        streaming.set(false);
    }

    @Override
    public void onTtsFinished() {
        ApiHub.send("voice.tts_finished", null);
    }

    @Override
    public void onControllerChanged(boolean connected) {
        if (connected) return;
        streaming.set(false);
        Listener l = listener;
        if (l != null) l.onDisconnected(true);
    }

    @Override
    public void close() {
        ApiHub.removeControllerListener(this);
        streaming.set(false);
        listener = null;
    }

    // ---------------------------------------------------------------- commands and state

    // registers the voice commands the state keys and the voice.config push once per process
    public static void install(VoiceEngine engine) {
        ApiHub.registerCommand("voice.start", params -> {
            engine.startFromController(params.optBoolean("start_conversation", false));
            return null;
        });
        ApiHub.registerCommand("voice.stop_audio", params -> {
            engine.stopAudioFromController();
            return null;
        });
        ApiHub.registerCommand("voice.state", params -> {
            String state = params.optString("state", "");
            switch (state) {
                case "idle":
                case "listening":
                case "processing":
                case "responding":
                case "error":
                    break;
                default:
                    throw ApiHub.CommandException.invalid("unknown state " + state);
            }
            String message = params.isNull("message") ? null : params.optString("message", null);
            engine.applyControllerState(state, message);
            return null;
        });
        ApiHub.registerCommand("voice.play_tts", params -> {
            String url = params.optString("url", "");
            if (url.isEmpty()) throw ApiHub.CommandException.invalid("url is required");
            String preannounce = params.isNull("preannounce_url") ? null : params.optString("preannounce_url", null);
            engine.playFromController(url, preannounce == null || preannounce.isEmpty() ? null : preannounce);
            return null;
        });
        ApiHub.registerCommand("voice.set_config", params -> {
            JSONArray active = params.optJSONArray("active");
            if (active == null) throw ApiHub.CommandException.invalid("active must be a list");
            String chosen = null;
            boolean any = false;
            for (int i = 0; i < active.length(); i++) {
                String id = active.optString(i, "");
                if (id.isEmpty()) continue;
                any = true;
                if (engine.isWakeWordAvailable(id)) { chosen = id; break; }
            }
            if (any && chosen == null) throw ApiHub.CommandException.invalid("no installed wake word in active");
            engine.setActiveWakeWord(chosen);
            sendConfig(engine);
            return null;
        });
        ApiHub.registerCommand("timer.update", params -> {
            JSONObject timer = params.optJSONObject("timer");
            String event = params.optString("event", "");
            if (timer == null || timer.optString("id", "").isEmpty()) {
                throw ApiHub.CommandException.invalid("timer with id is required");
            }
            engine.timers().update(event, timer.optString("id"), timer.optString("name", ""),
                    timer.optInt("remaining", 0), timer.optBoolean("active", true));
            return null;
        });

        ApiHub.addStateProvider(state -> contributeState(engine, state));
        ApiHub.addControllerListener(connected -> {
            if (connected) sendConfig(engine);
        });
    }

    private static void contributeState(VoiceEngine engine, Map<String, Object> state) {
        state.put("voice.state", engine.getProtocolState());
        state.put("voice.muted", engine.isMuted());
    }

    // wake words the display can run so the assist satellite entity can offer them
    public static void sendConfig(VoiceEngine engine) {
        try {
            JSONObject payload = new JSONObject();
            JSONArray available = new JSONArray();
            for (String id : engine.availableWakeWords()) {
                available.put(new JSONObject()
                        .put("id", id)
                        .put("phrase", VoiceEngine.phraseOf(id))
                        .put("languages", new JSONArray()));
            }
            payload.put("available", available);
            JSONArray active = new JSONArray();
            String current = engine.activeWakeWord();
            if (current != null) active.put(current);
            payload.put("active", active);
            payload.put("max_active", 1);
            ApiHub.send("voice.config", payload);
        } catch (JSONException e) {
            Log.w(TAG, "Could not build voice.config", e);
        }
    }
}
