package me.rapierxbox.shellyelevatev2.voice.speaker;

import java.util.List;
import java.util.Map;

// cosine scoring of an embedding against the samples of every speaker
final class SpeakerMatcher {
    // a speaker scores the mean of its best samples so one odd sample neither wins nor loses alone
    static final int TOP = 3;
    // the best speaker has to lead the next one by this much when both pass
    static final float MARGIN = 0.05f;

    static final class Match {
        final String id;
        final float score;
        final boolean known;

        Match(String id, float score, boolean known) {
            this.id = id;
            this.score = score;
            this.known = known;
        }
    }

    private SpeakerMatcher() {}

    // null when nobody is enrolled
    static Match best(Map<String, List<float[]>> speakers, float[] emb, float threshold) {
        String bestId = null;
        float best = -1f;
        float second = -1f;
        for (Map.Entry<String, List<float[]>> e : speakers.entrySet()) {
            float score = score(e.getValue(), emb);
            if (score > best) {
                second = best;
                best = score;
                bestId = e.getKey();
            } else if (score > second) {
                second = score;
            }
        }
        if (bestId == null) return null;
        boolean known = best >= threshold && (second < threshold || best - second >= MARGIN);
        return new Match(bestId, best, known);
    }

    static float score(List<float[]> samples, float[] emb) {
        float[] top = new float[TOP];
        java.util.Arrays.fill(top, -1f);
        int n = 0;
        for (float[] s : samples) {
            float c = dot(s, emb);
            if (n < TOP) n++;
            // insertion into the sorted top list
            for (int i = 0; i < TOP; i++) {
                if (c > top[i]) {
                    System.arraycopy(top, i, top, i + 1, TOP - i - 1);
                    top[i] = c;
                    break;
                }
            }
        }
        if (n == 0) return -1f;
        float sum = 0f;
        for (int i = 0; i < n; i++) sum += top[i];
        return sum / n;
    }

    static float dot(float[] a, float[] b) {
        float s = 0f;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    static float[] normalize(float[] v) {
        float n = (float) Math.sqrt(dot(v, v));
        if (n > 0f) for (int i = 0; i < v.length; i++) v[i] /= n;
        return v;
    }

    static float[] mean(float[]... embeddings) {
        float[] m = new float[embeddings[0].length];
        for (float[] e : embeddings) for (int i = 0; i < m.length; i++) m[i] += e[i];
        return normalize(m);
    }
}
