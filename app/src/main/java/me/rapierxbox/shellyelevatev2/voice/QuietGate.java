package me.rapierxbox.shellyelevatev2.voice;

// low power gate for the wake word models. follows a slow noise floor and reports
// activity while the level rises clearly above it plus a hangover so the models
// also see the quiet tail of a phrase
final class QuietGate {
    // about 6 db over the floor
    static final float RATIO = 2f;
    // below this nothing counts as sound even in a silent room
    static final float MIN_RMS = 0.001f;
    // chunks of 100 ms so two seconds
    static final int HANGOVER_CHUNKS = 20;
    // the floor drops fast and rises over about five seconds
    private static final float FALL = 0.3f;
    private static final float RISE = 0.02f;

    private float floor = -1f;
    private int hangover = 0;

    void reset() {
        floor = -1f;
        hangover = 0;
    }

    // true while the chunk should run through the models
    boolean update(float rms) {
        if (floor < 0f) {
            floor = rms;
            hangover = HANGOVER_CHUNKS;
            return true;
        }
        boolean loud = rms > Math.max(floor * RATIO, MIN_RMS);
        floor += (rms - floor) * (rms < floor ? FALL : RISE);
        if (loud) {
            hangover = HANGOVER_CHUNKS;
            return true;
        }
        if (hangover > 0) {
            hangover--;
            return true;
        }
        return false;
    }
}
