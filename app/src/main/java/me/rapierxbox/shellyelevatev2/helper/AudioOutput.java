package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Build;

import me.rapierxbox.shellyelevatev2.Constants;

// where the app plays its sounds
// auto: everything follows the system output, a connected bluetooth speaker or else the built-in speaker
// builtin: everything stays on the built-in speaker
// split: music follows the system output while announcements and voice stay on the built-in speaker,
// each with its own level on top of the speaker volume
// read on every play so a changed setting applies to the next sound
public final class AudioOutput {
    public static final String OUTPUT_AUTO = "auto";
    public static final String OUTPUT_BUILTIN = "builtin";
    public static final String OUTPUT_SPLIT = "split";

    private AudioOutput() {}

    public static void routeMusic(MediaPlayer player) {
        route(player, OUTPUT_BUILTIN.equals(output()));
    }

    // announcements, voice replies and voice tones
    public static void routeVoice(MediaPlayer player) {
        route(player, !OUTPUT_AUTO.equals(output()));
    }

    public static void routeVoice(AudioTrack track) {
        // a null device hands the routing back to the system
        track.setPreferredDevice(OUTPUT_AUTO.equals(output()) ? null : builtInSpeaker());
    }

    // player gain on top of the speaker volume. only the split output has levels of its own
    public static float announceGain() {
        return OUTPUT_SPLIT.equals(output()) ? percent(Constants.SP_ANNOUNCE_VOLUME) / 100f : 1f;
    }

    public static float voiceGain() {
        return OUTPUT_SPLIT.equals(output()) ? percent(Constants.SP_VOICE_REPLY_VOLUME) / 100f : 1f;
    }

    private static void route(MediaPlayer player, boolean builtIn) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        // players are reused so a player that was pinned before is released again
        player.setPreferredDevice(builtIn ? builtInSpeaker() : null);
    }

    private static String output() {
        return mSharedPreferences.getString(Constants.SP_AUDIO_OUTPUT, OUTPUT_AUTO);
    }

    // null on a device without a built-in speaker, which leaves the routing to the system
    private static AudioDeviceInfo builtInSpeaker() {
        AudioManager audioManager = (AudioManager) mApplicationContext.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return null;
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return device;
        }
        return null;
    }

    // http writes keep the stored type so the pref may hold an int a long or a float
    private static int percent(String key) {
        int value;
        try {
            value = mSharedPreferences.getInt(key, 100);
        } catch (ClassCastException e) {
            Object stored = mSharedPreferences.getAll().get(key);
            value = stored instanceof Number ? ((Number) stored).intValue() : 100;
        }
        return Math.max(0, Math.min(100, value));
    }
}
