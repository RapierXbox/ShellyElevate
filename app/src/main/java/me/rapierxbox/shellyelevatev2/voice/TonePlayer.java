package me.rapierxbox.shellyelevatev2.voice;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import java.util.concurrent.Executor;

public class TonePlayer {
    private static final String TAG = "TonePlayer";
    private static final int SAMPLE_RATE = 44100;

    private static final double WAKE_FREQ = 660.0;
    private static final double WAKE_DURATION_SEC = 0.8;
    private static final double WAKE_DECAY = 5.0;
    private static final double END_FREQ = 440.0;
    private static final double END_DURATION_SEC = 0.5;
    private static final double END_DECAY = 9.0;

    private final Executor executor;
    private final short[] wakeBuffer;
    private final short[] endBuffer;

    public TonePlayer(Executor executor) {
        this.executor = executor;
        this.wakeBuffer = render(WAKE_FREQ, WAKE_DURATION_SEC, WAKE_DECAY);
        this.endBuffer = render(END_FREQ, END_DURATION_SEC, END_DECAY);
    }

    public void playWake() { play(wakeBuffer, WAKE_DURATION_SEC); }
    public void playEnd() { play(endBuffer, END_DURATION_SEC); }

    private void play(short[] buf, double durationSec) {
        executor.execute(() -> {
            AudioTrack track = null;
            try {
                track = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        buf.length * 2, AudioTrack.MODE_STATIC);
                track.write(buf, 0, buf.length);
                track.play();
                Thread.sleep((long) (durationSec * 1000) + 200);
                track.stop();
            } catch (Exception e) {
                Log.w(TAG, "could not play tone", e);
            } finally {
                if (track != null) { try { track.release(); } catch (Exception ignored) {} }
            }
        });
    }

    private static short[] render(double freq, double durationSec, double decayConstant) {
        int numSamples = (int) (SAMPLE_RATE * durationSec);
        int attackSamples = SAMPLE_RATE / 200;
        short[] buf = new short[numSamples];
        for (int i = 0; i < numSamples; i++) {
            double attack = i < attackSamples ? (double) i / attackSamples : 1.0;
            double envelope = attack * Math.exp(-decayConstant * i / numSamples);
            buf[i] = (short) (Short.MAX_VALUE * 0.75 * envelope
                    * Math.sin(2.0 * Math.PI * freq * i / SAMPLE_RATE));
        }
        return buf;
    }
}
