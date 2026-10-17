package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.Constants.SP_ANNOUNCE_VOLUME;
import static me.rapierxbox.shellyelevatev2.Constants.SP_AUDIO_OUTPUT;
import static me.rapierxbox.shellyelevatev2.Constants.SP_VOICE_REPLY_VOLUME;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Build;

// where the app plays its sounds and read on every play so a change applies to the next sound
// auto follows the system so a connected bluetooth speaker gets everything
// builtin keeps everything on the display
// split sends only music to bluetooth and keeps announcements and voice on the display at their own level
public final class AudioOutput {
    public static final String OUTPUT_AUTO = "auto";
    public static final String OUTPUT_BUILTIN = "builtin";
    public static final String OUTPUT_SPLIT = "split";

    private AudioOutput() {}

    public static void routeMusic(MediaPlayer player) {
        route(player, OUTPUT_BUILTIN.equals(output()));
    }

    public static void routeAnnounce(MediaPlayer player) {
        route(player, !OUTPUT_AUTO.equals(output()));
    }

    // voice replies tones and the thinking sound
    public static void applyVoice(MediaPlayer player) {
        route(player, !OUTPUT_AUTO.equals(output()));
        float gain = gain(SP_VOICE_REPLY_VOLUME);
        player.setVolume(gain, gain);
    }

    // tracks are never reused so auto leaves them alone
    public static void applyVoice(AudioTrack track) {
        if (OUTPUT_AUTO.equals(output())) return;
        track.setPreferredDevice(builtInSpeaker());
        track.setVolume(gain(SP_VOICE_REPLY_VOLUME));
    }

    public static float announceGain() {
        return gain(SP_ANNOUNCE_VOLUME);
    }

    // player gain on top of the speaker volume and only split has levels of its own
    private static float gain(String key) {
        return OUTPUT_SPLIT.equals(output()) ? mSharedPreferences.getInt(key, 100) / 100f : 1f;
    }

    // null hands a reused player back to the system
    // call after setDataSource. before it the native player does not exist yet and android drops the device
    private static void route(MediaPlayer player, boolean builtIn) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        player.setPreferredDevice(builtIn ? builtInSpeaker() : null);
    }

    private static String output() {
        return mSharedPreferences.getString(SP_AUDIO_OUTPUT, OUTPUT_AUTO);
    }

    // null without a built-in speaker which leaves the routing to the system
    private static AudioDeviceInfo builtInSpeaker() {
        AudioManager am = (AudioManager) mApplicationContext.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return null;
        for (AudioDeviceInfo device : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return device;
        }
        return null;
    }
}
