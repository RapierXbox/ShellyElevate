package me.rapierxbox.shellyelevatev2.voice;

import android.Manifest;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import androidx.annotation.RequiresPermission;

// the microphone as 16 khz mono pcm16 for the wake word sessions and enrollment
public final class Mic {
    public static final int SAMPLE_RATE = NativeMelExtractor.SAMPLE_RATE;

    private Mic() {}

    // a recorder with room for four chunks or null when the mic can not be opened
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    public static AudioRecord open(int chunkBytes) {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf == AudioRecord.ERROR_BAD_VALUE) return null;
        AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf > 0 ? minBuf : chunkBytes, chunkBytes * 4));
        if (recorder.getState() == AudioRecord.STATE_INITIALIZED) return recorder;
        recorder.release();
        return null;
    }
}
