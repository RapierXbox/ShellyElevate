package me.rapierxbox.shellyelevatev2.voice;

// where a voice session goes once the engine captured it
// the engine owns the mic the wake word and playback and the transport only moves data
public interface VoiceTransport {

    // implemented by the engine. may be called on any thread
    interface Listener {
        // the transport can take sessions now
        void onReady();
        // the remote side lost the connection. keepIdle keeps wake word detection armed
        void onDisconnected(boolean keepIdle);
        // the remote side detected the end of speech so capture stops
        void onSpeechEnd();
        void onTranscript(String text);
        void onResponse(String text);
        // play the answer of the running session or an announcement
        void onTts(String url, String preannounceUrl);
        // the remote side finished the session without speech output
        void onSessionEnd();
        void onError(String message);
    }

    // starts connecting. sessions are possible once onReady fired
    void open(Listener listener);

    boolean isReady();

    // shown when a session is started while not ready. null stays silent
    String unavailableMessage();

    // a session started. remoteInitiated is true when the remote side asked for it so it needs no notice
    void startSession(String wakeWordId, String phrase, boolean remoteInitiated);

    // pcm 16 khz 16 bit little endian mono
    void sendAudio(byte[] buf, int len);

    // local capture ended through vad or the max duration
    void endAudio();

    // playback of onTts output finished
    void onTtsFinished();

    void close();
}
