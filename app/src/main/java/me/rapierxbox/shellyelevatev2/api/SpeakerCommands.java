package me.rapierxbox.shellyelevatev2.api;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import me.rapierxbox.shellyelevatev2.voice.VoiceEngine;
import me.rapierxbox.shellyelevatev2.voice.speaker.SpeakerEnrollment;
import me.rapierxbox.shellyelevatev2.voice.speaker.SpeakerRecognizer;
import me.rapierxbox.shellyelevatev2.voice.speaker.SpeakerStore;

// speaker.* commands of protocol v1. profiles stay on the display and the controller copies
// samples between displays with speaker.sample.get and speaker.sample.put
public final class SpeakerCommands {
    // a sample longer than this is not a single prompt
    private static final int MAX_SAMPLE_BYTES = 10 * 16000 * 2;

    private SpeakerCommands() {}

    public static void register(SpeakerRecognizer recognizer, VoiceEngine engine) {
        ApiHub.registerCommand("speaker.list", p -> list(recognizer));
        ApiHub.registerCommand("speaker.upsert", p -> {
            String id = p.isNull("id") ? null : p.optString("id", null);
            return new JSONObject().put("id", upsert(recognizer.store(), id, p).id);
        });
        ApiHub.registerCommand("speaker.delete", p -> {
            if (!recognizer.store().delete(p.optString("id"))) throw ApiHub.CommandException.invalid("unknown speaker");
            ApiHub.stateChanged();
            return null;
        });
        // speaker_id records more of a speaker and a name creates one first
        ApiHub.registerCommand("speaker.enroll.start", p -> {
            List<SpeakerEnrollment.Prompt> prompts = new ArrayList<>();
            JSONArray list = p.optJSONArray("prompts");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o != null && !o.optString("text").isEmpty()) {
                    prompts.add(new SpeakerEnrollment.Prompt(o.optString("text"), o.optString("kind", "free")));
                }
            }
            if (prompts.isEmpty()) prompts = SpeakerEnrollment.defaultPrompts(engine);
            String id = p.optString("speaker_id");
            boolean created = id.isEmpty();
            if (created) id = upsert(recognizer.store(), null, p).id;
            try {
                SpeakerEnrollment.start(recognizer, engine, id, prompts);
            } catch (ApiHub.CommandException e) {
                if (created) recognizer.store().delete(id);
                throw e;
            }
            return new JSONObject().put("speaker_id", id);
        });
        ApiHub.registerCommand("speaker.enroll.cancel", p -> {
            SpeakerEnrollment.cancel();
            return null;
        });
        ApiHub.registerCommand("speaker.sample.get", p -> {
            try {
                byte[] pcm = recognizer.store().readPcm(p.optString("speaker_id"), p.optString("sample_id"));
                return new JSONObject().put("pcm_b64", Base64.encodeToString(pcm, Base64.NO_WRAP));
            } catch (IOException e) {
                throw ApiHub.CommandException.invalid("no audio for this sample");
            }
        });
        ApiHub.registerCommand("speaker.sample.put", p -> {
            byte[] pcm;
            try {
                pcm = Base64.decode(p.optString("pcm_b64"), Base64.NO_WRAP);
            } catch (IllegalArgumentException e) {
                throw ApiHub.CommandException.invalid("pcm_b64 is not base64");
            }
            if (pcm.length < 16000 || pcm.length > MAX_SAMPLE_BYTES || pcm.length % 2 != 0) {
                throw ApiHub.CommandException.invalid("sample must be 0.5 to 10 s of pcm16");
            }
            String kind = "wake".equals(p.optString("kind")) ? "wake" : "free";
            try {
                recognizer.addEnrolled(p.optString("speaker_id"), p.isNull("sample_id") ? null : p.optString("sample_id", null),
                        kind, pcm, null);
            } catch (IOException e) {
                throw ApiHub.CommandException.invalid(e.getMessage());
            }
            return null;
        });
        ApiHub.registerCommand("speaker.sample.delete", p -> {
            try {
                if (!recognizer.store().deleteSample(p.optString("speaker_id"), p.optString("sample_id"))) {
                    throw ApiHub.CommandException.invalid("unknown sample");
                }
            } catch (IOException e) {
                throw new ApiHub.CommandException("failed", e.getMessage());
            }
            ApiHub.stateChanged();
            return null;
        });
        ApiHub.registerCommand("speaker.recent", p -> {
            JSONArray out = new JSONArray();
            for (SpeakerRecognizer.Result r : recognizer.recent()) out.put(r.toJson().put("text", r.text));
            return new JSONObject().put("recent", out);
        });
        ApiHub.registerCommand("speaker.recent.label", p -> {
            recognizer.label(p.optLong("session", -1), p.optString("text"));
            return null;
        });
        ApiHub.registerCommand("speaker.assign", p -> {
            String id = p.optString("speaker_id");
            if (recognizer.store().get(id) == null) throw ApiHub.CommandException.invalid("unknown speaker");
            recognizer.assign(p.optLong("session", -1), id);
            return null;
        });
        ApiHub.addStateProvider(recognizer::contributeState);
    }

    // a missing person keeps the linked one and null unlinks it
    private static SpeakerStore.Profile upsert(SpeakerStore store, String id, JSONObject p) throws ApiHub.CommandException {
        String name = p.isNull("name") ? null : p.optString("name", null);
        if (name != null) name = name.trim();
        if (id == null && (name == null || name.isEmpty())) throw ApiHub.CommandException.invalid("name is required");
        try {
            SpeakerStore.Profile profile = store.upsert(id, name,
                    p.has("person") ? (p.isNull("person") ? "" : p.optString("person")) : null);
            ApiHub.stateChanged();
            return profile;
        } catch (IOException e) {
            throw new ApiHub.CommandException("failed", e.getMessage());
        }
    }

    private static JSONObject list(SpeakerRecognizer recognizer) throws JSONException {
        String model = recognizer.currentModel();
        JSONArray speakers = new JSONArray();
        for (SpeakerStore.Profile profile : recognizer.store().list()) {
            JSONArray samples = new JSONArray();
            for (SpeakerStore.Sample s : profile.samples) {
                samples.put(new JSONObject().put("id", s.id).put("kind", s.kind).put("source", s.source)
                        .put("at", s.at).put("audio", SpeakerStore.SOURCE_ENROLLED.equals(s.source)));
            }
            speakers.put(new JSONObject().put("id", profile.id).put("name", profile.name)
                    .put("person", profile.person != null ? profile.person : JSONObject.NULL).put("samples", samples));
        }
        return new JSONObject().put("enabled", recognizer.isEnabled()).put("speakers", speakers).put("model", model)
                .put("enrolling", SpeakerEnrollment.isRunning()).put("rev", recognizer.store().rev());
    }
}
