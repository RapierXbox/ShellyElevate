package me.rapierxbox.shellyelevatev2.voice.speaker;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager;

import android.annotation.SuppressLint;
import android.media.AudioRecord;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import me.rapierxbox.shellyelevatev2.api.ApiHub;
import me.rapierxbox.shellyelevatev2.voice.Mic;
import me.rapierxbox.shellyelevatev2.voice.VoiceEngine;
import me.rapierxbox.shellyelevatev2.voice.WakeWordDetector;

// guided enrollment on the display. each prompt shows in the voice bubble and is recorded
// until the speaker pauses. a bad take is asked again and progress goes out as speaker.enroll
public final class SpeakerEnrollment {
    private static final String TAG = "SpeakerEnroll";
    private static final int CHUNK = Mic.SAMPLE_RATE / 10 * 2;
    private static final int WAIT_CHUNKS = 80;
    // 4 s so a take is two model windows and the next prompt follows quickly
    private static final int MAX_CHUNKS = 40;
    private static final int END_SILENCE_CHUNKS = 8;
    private static final int MIN_SPEECH_CHUNKS = 8;
    private static final float SPEECH_RMS = 0.01f;
    private static final float CLIP_RATIO = 0.01f;
    // a take this far from the others is likely someone else or a cough
    private static final float MIN_CONSISTENCY = 0.25f;
    private static final int TRIES = 3;

    public static final class Prompt {
        final String text;
        final String kind;

        public Prompt(String text, String kind) {
            this.text = text;
            this.kind = kind;
        }
    }

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicBoolean cancelled = new AtomicBoolean(false);

    private SpeakerEnrollment() {}

    // three times the wake phrase and three short commands
    public static List<Prompt> defaultPrompts(VoiceEngine engine) {
        List<Prompt> prompts = new ArrayList<>();
        String wake = engine.activeWakeWord();
        if (wake != null) for (int i = 0; i < 3; i++) prompts.add(new Prompt(VoiceEngine.phraseOf(wake), "wake"));
        prompts.add(new Prompt("Turn on the kitchen light", "free"));
        prompts.add(new Prompt("What is the weather like tomorrow", "free"));
        prompts.add(new Prompt("Set a timer for ten minutes", "free"));
        return prompts;
    }

    public static boolean isRunning() {
        return running.get();
    }

    public static void cancel() {
        cancelled.set(true);
    }

    public static void start(SpeakerRecognizer recognizer, VoiceEngine engine, String speakerId,
                             List<Prompt> prompts) throws ApiHub.CommandException {
        if (!recognizer.isEnabled()) throw ApiHub.CommandException.unsupported("speaker recognition is off");
        if (!recognizer.modelReady()) throw new ApiHub.CommandException("busy", "the speaker model is still downloading");
        if (recognizer.store().get(speakerId) == null) throw ApiHub.CommandException.invalid("unknown speaker");
        if (prompts.isEmpty()) throw ApiHub.CommandException.invalid("no prompts");
        if (!running.compareAndSet(false, true)) throw new ApiHub.CommandException("busy", "enrollment running");
        if (!engine.borrowMic()) {
            running.set(false);
            throw new ApiHub.CommandException("busy", "voice is busy or muted");
        }
        cancelled.set(false);
        new Handler(Looper.getMainLooper()).post(() -> mScreenSaverManager.stopScreenSaver());
        new Thread(() -> {
            String status = "failed";
            try {
                status = record(recognizer, engine, speakerId, prompts);
            } catch (Exception e) {
                Log.e(TAG, "enrollment failed", e);
            } finally {
                engine.releaseMic();
                running.set(false);
                engine.showText("done".equals(status) ? "Voice saved" : "");
                progress(speakerId, prompts.size(), prompts.size(), status, null, null);
                if ("done".equals(status)) new Handler(Looper.getMainLooper()).postDelayed(() -> engine.showText(""), 3_000);
            }
        }, "SpeakerEnroll").start();
    }

    @SuppressLint("MissingPermission")
    private static String record(SpeakerRecognizer recognizer, VoiceEngine engine, String speakerId,
                                 List<Prompt> prompts) throws Exception {
        AudioRecord rec = Mic.open(CHUNK);
        if (rec == null) return "failed";
        List<float[]> takes = new ArrayList<>();
        try {
            rec.startRecording();
            for (int i = 0; i < prompts.size(); i++) {
                Prompt prompt = prompts.get(i);
                String reason = null;
                for (int attempt = 0; attempt < TRIES; attempt++) {
                    if (cancelled.get()) return "cancelled";
                    engine.showText((reason != null ? hint(reason) + "\n" : "")
                            + "Say “" + prompt.text + "”  " + (i + 1) + "/" + prompts.size());
                    progress(speakerId, i, prompts.size(), "recording", prompt.text, reason);
                    // drops what queued up while the last take was embedded
                    rec.stop();
                    rec.startRecording();
                    byte[] pcm = take(rec);
                    reason = check(pcm);
                    if (reason != null) continue;
                    float[] emb = recognizer.embedNow(pcm);
                    if (!takes.isEmpty()
                            && SpeakerMatcher.dot(emb, SpeakerMatcher.mean(takes.toArray(new float[0][]))) < MIN_CONSISTENCY) {
                        reason = "different_voice";
                        continue;
                    }
                    takes.add(emb);
                    recognizer.addEnrolled(speakerId, null, prompt.kind, pcm, emb);
                    break;
                }
                if (reason != null) return "failed";
            }
            return "done";
        } finally {
            try { rec.stop(); } catch (Exception ignored) {}
            rec.release();
        }
    }

    // one utterance from the first speech to a short pause. null when nothing was said
    private static byte[] take(AudioRecord rec) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[CHUNK];
        byte[] previous = null;
        int speech = 0;
        int silence = 0;
        for (int n = 0; n < WAIT_CHUNKS + MAX_CHUNKS && !cancelled.get(); n++) {
            int read = rec.read(buf, 0, CHUNK);
            if (read <= 0) break;
            boolean loud = WakeWordDetector.calculateRms(buf, read) >= SPEECH_RMS;
            if (speech == 0) {
                if (!loud) { previous = Arrays.copyOf(buf, read); continue; }
                // keep the chunk before the onset so the first syllable is complete
                if (previous != null) out.write(previous, 0, previous.length);
            }
            out.write(buf, 0, read);
            if (loud) { speech++; silence = 0; }
            else if (++silence >= END_SILENCE_CHUNKS) break;
            if (out.size() >= MAX_CHUNKS * CHUNK) break;
        }
        return speech >= MIN_SPEECH_CHUNKS ? out.toByteArray() : (speech == 0 ? null : new byte[0]);
    }

    private static String check(byte[] pcm) {
        if (pcm == null) return "no_speech";
        if (pcm.length < MIN_SPEECH_CHUNKS * CHUNK) return "too_short";
        int clipped = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int s = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            if (s > 32000 || s < -32000) clipped++;
        }
        return clipped > pcm.length / 2 * CLIP_RATIO ? "too_loud" : null;
    }

    private static String hint(String reason) {
        switch (reason) {
            case "no_speech": return "Nothing heard, please speak a bit louder";
            case "too_short": return "That was too short";
            case "too_loud": return "Too loud, step back a little";
            default: return "That sounded different, once more";
        }
    }

    private static void progress(String speakerId, int step, int total, String status, String prompt, String reason) {
        try {
            ApiHub.send("speaker.enroll", new JSONObject().put("speaker_id", speakerId).put("step", step)
                    .put("total", total).put("status", status).put("prompt", prompt).put("reason", reason));
        } catch (JSONException ignored) {
        }
    }
}
