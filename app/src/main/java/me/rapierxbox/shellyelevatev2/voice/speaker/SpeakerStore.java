package me.rapierxbox.shellyelevatev2.voice.speaker;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// speakers live in files/speakers/<id>/ as profile.json plus <sample>.pcm for enrolled samples
// embeddings are cached per model in the profile so a model switch only embeds what is missing
// claimed and learned samples keep no audio so they only count for the model that made them
public final class SpeakerStore {
    private static final String TAG = "SpeakerStore";
    public static final String SOURCE_ENROLLED = "enrolled";
    public static final String SOURCE_CLAIMED = "claimed";
    public static final String SOURCE_LEARNED = "learned";
    // learned samples rotate so everyday use can not drift a voiceprint far
    static final int MAX_LEARNED = 10;
    static final int MAX_SAMPLES = 40;

    public static final class Sample {
        public final String id;
        public final String kind;
        public final String source;
        public final long at;
        final Map<String, float[]> emb = new HashMap<>();

        Sample(String id, String kind, String source, long at) {
            this.id = id;
            this.kind = kind;
            this.source = source;
            this.at = at;
        }
    }

    public static final class Profile {
        public final String id;
        public String name;
        public String person;
        public final List<Sample> samples = new ArrayList<>();

        Profile(String id) {
            this.id = id;
        }

        private Profile copy() {
            Profile p = new Profile(id);
            p.name = name;
            p.person = person;
            p.samples.addAll(samples);
            return p;
        }
    }

    private final File root;
    private final Map<String, Profile> profiles = new LinkedHashMap<>();
    private volatile String rev = "";

    SpeakerStore(File root) {
        this.root = root;
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return;
        for (File dir : dirs) {
            try {
                Profile p = parse(dir.getName(), new String(read(new File(dir, "profile.json")), StandardCharsets.UTF_8));
                profiles.put(p.id, p);
            } catch (IOException | JSONException e) {
                Log.w(TAG, "skipping speaker " + dir.getName() + ": " + e.getMessage());
            }
        }
        updateRev();
    }

    // copies so a listing never sees a sample added meanwhile
    public synchronized List<Profile> list() {
        List<Profile> out = new ArrayList<>();
        for (Profile p : profiles.values()) out.add(p.copy());
        return out;
    }

    public synchronized Profile get(String id) {
        Profile p = profiles.get(id);
        return p != null ? p.copy() : null;
    }

    // a null id creates a speaker. a null person keeps the current one
    // ids become file names so anything that could leave the speakers folder is refused
    public static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,64}");
    }

    private static void requireId(String id) throws IOException {
        if (!validId(id)) throw new IOException("invalid id");
    }

    public synchronized Profile upsert(String id, String name, String person) throws IOException {
        if (id != null) requireId(id);
        Profile p = id != null ? profiles.get(id) : null;
        if (p == null) {
            p = new Profile(id != null ? id : UUID.randomUUID().toString());
            profiles.put(p.id, p);
        }
        if (name != null) p.name = name;
        if (person != null) p.person = person.isEmpty() ? null : person;
        save(p);
        return p;
    }

    public synchronized boolean delete(String id) {
        if (!validId(id)) return false;
        if (profiles.remove(id) == null) return false;
        updateRev();
        File dir = new File(root, id);
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
        return true;
    }

    // pcm is kept for enrolled samples only
    synchronized Sample addSample(String profileId, String sampleId, String kind, String source, byte[] pcm,
                                  String model, float[] emb) throws IOException {
        if (sampleId != null) requireId(sampleId);
        Profile p = profiles.get(profileId);
        if (p == null) throw new IOException("unknown speaker");
        Sample s = new Sample(sampleId != null ? sampleId : UUID.randomUUID().toString().substring(0, 8),
                kind, source, System.currentTimeMillis());
        if (emb != null) s.emb.put(model, emb);
        removeSample(p, s.id);
        if (pcm != null) {
            dir(p);
            write(pcmFile(p.id, s.id), pcm);
        }
        p.samples.add(s);
        trim(p);
        save(p);
        return s;
    }

    public synchronized boolean deleteSample(String profileId, String sampleId) throws IOException {
        Profile p = profiles.get(profileId);
        if (p == null || !removeSample(p, sampleId)) return false;
        save(p);
        return true;
    }

    public byte[] readPcm(String profileId, String sampleId) throws IOException {
        requireId(profileId);
        requireId(sampleId);
        return read(pcmFile(profileId, sampleId));
    }

    private File pcmFile(String profileId, String sampleId) {
        return new File(new File(root, profileId), sampleId + ".pcm");
    }

    synchronized Map<String, List<float[]>> embeddings(String model) {
        Map<String, List<float[]>> out = new LinkedHashMap<>();
        for (Profile p : profiles.values()) {
            List<float[]> list = new ArrayList<>();
            for (Sample s : p.samples) {
                float[] e = s.emb.get(model);
                if (e != null) list.add(e);
            }
            if (!list.isEmpty()) out.put(p.id, list);
        }
        return out;
    }

    // enrolled samples that still need an embedding of this model as {profile id, sample id}
    synchronized List<String[]> missing(String model) {
        List<String[]> out = new ArrayList<>();
        for (Profile p : profiles.values()) {
            for (Sample s : p.samples) {
                if (!s.emb.containsKey(model) && pcmFile(p.id, s.id).isFile()) out.add(new String[]{p.id, s.id});
            }
        }
        return out;
    }

    synchronized void putEmbedding(String profileId, String sampleId, String model, float[] emb) throws IOException {
        Profile p = profiles.get(profileId);
        if (p == null) return;
        for (Sample s : p.samples) {
            if (s.id.equals(sampleId)) {
                s.emb.put(model, emb);
                save(p);
                return;
            }
        }
    }

    // changes whenever a speaker or a sample is added or removed
    public String rev() {
        return rev;
    }

    private void updateRev() {
        StringBuilder sb = new StringBuilder();
        for (Profile p : profiles.values()) {
            sb.append(p.id).append(p.name).append(p.person);
            for (Sample s : p.samples) sb.append(s.id);
        }
        rev = Integer.toHexString(sb.toString().hashCode());
    }

    // ---------------------------------------------------------------- files

    private boolean removeSample(Profile p, String sampleId) {
        for (Iterator<Sample> it = p.samples.iterator(); it.hasNext(); ) {
            if (it.next().id.equals(sampleId)) {
                it.remove();
                pcmFile(p.id, sampleId).delete();
                return true;
            }
        }
        return false;
    }

    // oldest learned samples go first then the oldest of all
    private void trim(Profile p) {
        int learned = 0;
        for (Sample s : p.samples) if (SOURCE_LEARNED.equals(s.source)) learned++;
        for (Iterator<Sample> it = p.samples.iterator(); it.hasNext() && learned > MAX_LEARNED; ) {
            Sample s = it.next();
            if (SOURCE_LEARNED.equals(s.source)) { it.remove(); learned--; }
        }
        while (p.samples.size() > MAX_SAMPLES) removeSample(p, p.samples.get(0).id);
    }

    private File dir(Profile p) throws IOException {
        File dir = new File(root, p.id);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("could not create " + dir);
        return dir;
    }

    private void save(Profile p) throws IOException {
        try {
            JSONArray samples = new JSONArray();
            for (Sample s : p.samples) {
                JSONObject emb = new JSONObject();
                for (Map.Entry<String, float[]> e : s.emb.entrySet()) emb.put(e.getKey(), encode(e.getValue()));
                samples.put(new JSONObject().put("id", s.id).put("kind", s.kind).put("source", s.source)
                        .put("at", s.at).put("emb", emb));
            }
            JSONObject json = new JSONObject().put("name", p.name).put("person", p.person).put("samples", samples);
            File file = new File(dir(p), "profile.json");
            File tmp = new File(file.getPath() + ".tmp");
            write(tmp, json.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(file)) throw new IOException("could not save " + file);
            updateRev();
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static Profile parse(String id, String text) throws JSONException {
        JSONObject json = new JSONObject(text);
        Profile p = new Profile(id);
        p.name = json.optString("name", "");
        p.person = json.isNull("person") ? null : json.optString("person", null);
        JSONArray samples = json.optJSONArray("samples");
        for (int i = 0; samples != null && i < samples.length(); i++) {
            JSONObject o = samples.getJSONObject(i);
            Sample s = new Sample(o.getString("id"), o.optString("kind", "free"),
                    o.optString("source", SOURCE_ENROLLED), o.optLong("at"));
            JSONObject emb = o.optJSONObject("emb");
            if (emb != null) {
                for (Iterator<String> it = emb.keys(); it.hasNext(); ) {
                    String model = it.next();
                    s.emb.put(model, decode(emb.getString(model)));
                }
            }
            p.samples.add(s);
        }
        return p;
    }

    private static byte[] read(File file) throws IOException {
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) throw new IOException("short read " + file);
                off += n;
            }
        }
        return data;
    }

    private static void write(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
            out.getFD().sync();
        }
    }

    private static String encode(float[] v) {
        ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) b.putFloat(f);
        return Base64.encodeToString(b.array(), Base64.NO_WRAP);
    }

    private static float[] decode(String s) {
        ByteBuffer b = ByteBuffer.wrap(Base64.decode(s, Base64.NO_WRAP)).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[b.remaining() / 4];
        for (int i = 0; i < v.length; i++) v[i] = b.getFloat();
        return v;
    }
}
