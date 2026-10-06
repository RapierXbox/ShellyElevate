package me.rapierxbox.shellyelevatev2.api;

import org.json.JSONObject;

import me.rapierxbox.shellyelevatev2.ShellyElevateApplication;
import me.rapierxbox.shellyelevatev2.helper.MediaHelper;
import me.rapierxbox.shellyelevatev2.helper.MediaQueue;

// media.* commands from protocol-v1 section 6
public final class MediaCommands {
    private MediaCommands() {}

    private interface MediaAction {
        void run(MediaHelper media, JSONObject params) throws ApiHub.CommandException;
    }

    public static void register() {
        add("media.play", MediaCommands::play);
        add("media.pause", (media, params) -> media.pause());
        add("media.resume", (media, params) -> media.resume());
        add("media.stop", (media, params) -> media.stop());
        add("media.next", (media, params) -> media.next());
        add("media.seek", (media, params) -> media.seek(number(params, "position", 0, Double.MAX_VALUE)));
        add("media.volume", MediaCommands::volume);
        add("media.repeat", (media, params) -> {
            MediaQueue.Repeat repeat = MediaQueue.Repeat.parse(params.optString("mode", null));
            if (repeat == null) throw ApiHub.CommandException.invalid("mode must be off one or all");
            media.setRepeat(repeat);
        });
    }

    private static void add(String action, MediaAction handler) {
        ApiHub.registerCommand(action, params -> {
            // read once so a concurrent media toggle cannot null it mid command
            MediaHelper media = ShellyElevateApplication.mMediaHelper;
            if (media == null) throw ApiHub.CommandException.unsupported("media is disabled on the display");
            handler.run(media, params != null ? params : new JSONObject());
            return null;
        });
    }

    private static void play(MediaHelper media, JSONObject params) throws ApiHub.CommandException {
        String url = text(params, "url");
        if (url == null) throw ApiHub.CommandException.invalid("url is required");
        String channel = text(params, "channel");
        if (channel == null) channel = "music";
        String enqueue = text(params, "enqueue");
        if (enqueue == null) enqueue = MediaQueue.ENQUEUE_PLAY;
        if (!MediaQueue.isEnqueueMode(enqueue)) {
            throw ApiHub.CommandException.invalid("enqueue must be replace add next or play");
        }
        if (params.has("volume") && !params.isNull("volume")) {
            media.setVolume(number(params, "volume", 0, 1));
        }
        switch (channel) {
            case "music":
                media.play(new MediaQueue.Track(url, text(params, "title"), text(params, "artist"),
                        text(params, "album"), text(params, "artwork")), enqueue);
                break;
            case "announce":
                media.announce(url, null);
                break;
            default:
                throw ApiHub.CommandException.invalid("channel must be music or announce");
        }
    }

    private static void volume(MediaHelper media, JSONObject params) throws ApiHub.CommandException {
        boolean hasVolume = params.has("volume") && !params.isNull("volume");
        boolean hasMuted = params.has("muted") && !params.isNull("muted");
        if (!hasVolume && !hasMuted) throw ApiHub.CommandException.invalid("volume or muted is required");
        if (hasMuted) {
            Object muted = params.opt("muted");
            if (!(muted instanceof Boolean)) throw ApiHub.CommandException.invalid("muted must be a bool");
            media.setMuted((Boolean) muted);
        }
        if (hasVolume) media.setVolume(number(params, "volume", 0, 1));
    }

    // null for a missing null or empty string
    private static String text(JSONObject params, String key) {
        if (!params.has(key) || params.isNull(key)) return null;
        String value = params.optString(key, "").trim();
        return value.isEmpty() ? null : value;
    }

    private static double number(JSONObject params, String key, double min, double max)
            throws ApiHub.CommandException {
        Object value = params.opt(key);
        if (!(value instanceof Number)) throw ApiHub.CommandException.invalid(key + " must be a number");
        double d = ((Number) value).doubleValue();
        if (Double.isNaN(d) || d < min || d > max) throw ApiHub.CommandException.invalid(key + " is out of range");
        return d;
    }
}
