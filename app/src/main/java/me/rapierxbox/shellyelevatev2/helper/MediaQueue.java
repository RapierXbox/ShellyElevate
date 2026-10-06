package me.rapierxbox.shellyelevatev2.helper;

import java.util.ArrayList;
import java.util.List;

// music queue and repeat rules without android classes so they stay unit testable
public final class MediaQueue {
    public enum Repeat {
        OFF("off"), ONE("one"), ALL("all");

        public final String wire;

        Repeat(String wire) {
            this.wire = wire;
        }

        // null for an unknown mode
        public static Repeat parse(String value) {
            if (value == null) return null;
            for (Repeat r : values()) {
                if (r.wire.equalsIgnoreCase(value.trim())) return r;
            }
            return null;
        }
    }

    public static final String ENQUEUE_PLAY = "play";
    public static final String ENQUEUE_REPLACE = "replace";
    public static final String ENQUEUE_NEXT = "next";
    public static final String ENQUEUE_ADD = "add";

    public static boolean isEnqueueMode(String mode) {
        return ENQUEUE_PLAY.equals(mode) || ENQUEUE_REPLACE.equals(mode)
                || ENQUEUE_NEXT.equals(mode) || ENQUEUE_ADD.equals(mode);
    }

    public static final class Track {
        public final String url;
        public final String title;
        public final String artist;
        public final String album;
        public final String artwork;

        public Track(String url, String title, String artist, String album, String artwork) {
            this.url = url;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.artwork = artwork;
        }

        public static Track of(String url) {
            return new Track(url, null, null, null, null);
        }
    }

    private final List<Track> tracks = new ArrayList<>();
    private int index = -1;
    private Repeat repeat = Repeat.OFF;

    // adds a track and returns the one to start now or null when the current one keeps playing
    // active means a track is playing or paused right now
    public synchronized Track enqueue(Track track, String mode, boolean active) {
        switch (mode) {
            case ENQUEUE_REPLACE:
                tracks.clear();
                tracks.add(track);
                index = 0;
                return track;
            case ENQUEUE_PLAY:
                index = insertAfterCurrent(track);
                return track;
            case ENQUEUE_NEXT: {
                int at = insertAfterCurrent(track);
                if (active) return null;
                index = at;
                return track;
            }
            case ENQUEUE_ADD:
                tracks.add(track);
                if (active) return null;
                index = tracks.size() - 1;
                return track;
            default:
                throw new IllegalArgumentException("unknown enqueue mode " + mode);
        }
    }

    private int insertAfterCurrent(Track track) {
        int at = Math.min(index + 1, tracks.size());
        tracks.add(at, track);
        return at;
    }

    // track to play after the current one or null when playback ends
    // a skip leaves a repeat one track while a natural end replays it
    public synchronized Track advance(boolean skip) {
        if (index < 0 || tracks.isEmpty()) return null;
        if (!skip && repeat == Repeat.ONE) return tracks.get(index);
        if (index + 1 < tracks.size()) {
            index++;
            return tracks.get(index);
        }
        if (repeat == Repeat.ALL) {
            index = 0;
            return tracks.get(index);
        }
        return null;
    }

    public synchronized Track current() {
        return index >= 0 && index < tracks.size() ? tracks.get(index) : null;
    }

    public synchronized void clear() {
        tracks.clear();
        index = -1;
    }

    public synchronized int size() {
        return tracks.size();
    }

    public synchronized Repeat getRepeat() {
        return repeat;
    }

    public synchronized void setRepeat(Repeat repeat) {
        this.repeat = repeat;
    }
}
