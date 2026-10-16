package me.rapierxbox.shellyelevatev2.voice;

// listens to the audio of voice sessions. VoiceEngine has none while no feature needs one
public interface VoiceTap {
    // true tells the controller that a quick result about this wake phrase will follow
    boolean quickResult(byte[] preRoll);

    // preRoll holds the wake phrase or is null for a session the controller started
    // called after the transport announced the session with the answer of quickResult
    void onSessionStart(long session, byte[] preRoll, boolean quickResult);

    // capture thread. buf is reused after the call
    void onAudio(long session, byte[] buf, int len, boolean speech);

    // complete is false when the mic failed or the session was dropped
    void onSessionEnd(long session, boolean complete);
}
