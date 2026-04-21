package me.rapierxbox.shellyelevatev2.voice;

import android.net.Uri;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Manages a single WebSocket session with the Home Assistant Assist pipeline.
 *
 * Protocol summary:
 *  1. Connect to ws(s)://{host}:{port}/api/websocket
 *  2. Server sends {"type":"auth_required"} → we reply with access_token
 *  3. Server sends {"type":"auth_ok"}
 *  4. We send assist_pipeline/run with start_stage=stt, end_stage=tts
 *  5. Server sends run-start event containing stt_binary_handler_id
 *  6. We stream PCM audio as binary frames: [handler_id byte][pcm bytes...]
 *  7. To signal end of audio send a frame with just the handler_id byte
 *  8. Server processes and returns stt-end, intent-end, tts-end, run-end events
 */

public class HAVoicePipeline {
    private static final String TAG = "HAVoicePipeline";

    public interface Callback {
        void onConnected();
        void onAuthOk();
        void onListening(); // pipeline started
        void onTranscript(String text); // stt from ha
        void onResponse(String text);
        void onTtsUrl(String url);
        void onSpeechEnd(); // ha vad detected end of speech
        void onPipelineEnd();
        void onError(String message);
        void onDisconnected();
    }

    private enum AuthState { WAITING, AUTHENTICATED, FAILED }

    private final OkHttpClient client;
    private final Callback callback;
    private WebSocket webSocket;
    private final AtomicInteger messageId = new AtomicInteger(1);
    private volatile AuthState authState = AuthState.WAITING;
    private volatile int sttBinaryHandlerId = -1;
    private String haBaseUrl;
    private String accessToken;

    public HAVoicePipeline(OkHttpClient client, Callback callback) {
        this.client = client;
        this.callback = callback;
    }

    public void connect(String haUrl, String token) {
        this.haBaseUrl = haUrl.endsWith("/") ? haUrl.substring(0, haUrl.length() - 1) : haUrl;
        this.accessToken = token;
        this.authState = AuthState.WAITING;
        this.sttBinaryHandlerId = -1;
        this.messageId.set(1);

        Uri uri = Uri.parse(haUrl);
        String scheme = "https".equalsIgnoreCase(uri.getScheme()) ? "wss" : "ws";
        int port = uri.getPort();
        String wsUrl = scheme + "://" + uri.getHost()
                + (port != -1 ? ":" + port : "")
                + "/api/websocket";

        Log.d(TAG, "Connecting to " + wsUrl);
        Request request = new Request.Builder().url(wsUrl).build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                Log.i(TAG, "WebSocket connected");
                callback.onConnected();
                // auth_required arrives from server momentaryly
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleTextMessage(text);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                Log.e(TAG, "WebSocket failure: " + (t != null ? t.getMessage() : "null"));
                callback.onError(t != null ? t.getMessage() : "Connection failed");
                callback.onDisconnected();
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                Log.i(TAG, "WebSocket closed: " + code + " " + reason);
                callback.onDisconnected();
            }
        });
    }

    private void handleTextMessage(String text) {
        try {
            JSONObject msg = new JSONObject(text);
            switch (msg.optString("type")) {
                case "auth_required":
                    sendAuth();
                    break;
                case "auth_ok":
                    authState = AuthState.AUTHENTICATED;
                    Log.i(TAG, "Authenticated with Home Assistant");
                    callback.onAuthOk();
                    break;
                case "auth_invalid":
                    authState = AuthState.FAILED;
                    callback.onError("Authentication failed... check your Long-Lived Access Token");
                    break;
                case "event":
                    handlePipelineEvent(msg);
                    break;
                case "result":
                    if (!msg.optBoolean("success", true)) {
                        JSONObject err = msg.optJSONObject("error");
                        String errMsg = err != null ? err.optString("message", "Unknown error") : "Unknown error";
                        callback.onError("Pipeline error: " + errMsg);
                    }
                    break;
                default:
                    Log.d(TAG, "Unhandled WS message type: " + msg.optString("type"));
                    break;
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse WS message: " + text, e);
        }
    }

    private void handlePipelineEvent(JSONObject msg) throws JSONException {
        JSONObject event = msg.optJSONObject("event");
        if (event == null) return;
        String eventType = event.optString("type");
        JSONObject data = event.optJSONObject("data");
        if (data == null) data = new JSONObject();

        Log.d(TAG, "Pipeline event: " + eventType);
        switch (eventType) {
            case "run-start": {
                JSONObject runnerData = data.optJSONObject("runner_data");
                if (runnerData != null) {
                    sttBinaryHandlerId = runnerData.optInt("stt_binary_handler_id", 1);
                }
                callback.onListening();
                break;
            }
            case "stt-end": {
                JSONObject sttOut = data.optJSONObject("stt_output");
                if (sttOut != null) {
                    callback.onTranscript(sttOut.optString("text", ""));
                }
                break;
            }
            case "intent-end": {
                JSONObject intentOut = data.optJSONObject("intent_output");
                if (intentOut != null) {
                    JSONObject response = intentOut.optJSONObject("response");
                    JSONObject speech = response != null ? response.optJSONObject("speech") : null;
                    if (speech != null) {
                        JSONObject plain = speech.optJSONObject("plain");
                        if (plain != null) {
                            String txt = plain.optString("speech", "");
                            if (!txt.isEmpty()) callback.onResponse(txt);
                        }
                    }
                }
                break;
            }
            case "tts-end": {
                JSONObject ttsOut = data.optJSONObject("tts_output");
                if (ttsOut != null) {
                    String ttsPath = ttsOut.optString("url", "");
                    if (!ttsPath.isEmpty()) {
                        // make absoluite if relative
                        String ttsUrl = ttsPath.startsWith("http") ? ttsPath
                                : haBaseUrl + (ttsPath.startsWith("/") ? ttsPath : "/" + ttsPath);
                        callback.onTtsUrl(ttsUrl);
                    }
                }
                break;
            }
            case "run-end":
                callback.onPipelineEnd();
                break;
            case "error": {
                String code = data.optString("code", "");
                String message = data.optString("message", "Pipeline error");
                callback.onError(code.isEmpty() ? message : code + ": " + message);
                break;
            }
            case "stt-vad-end":
                callback.onSpeechEnd();
                break;
            default:
                break;
        }
    }

    private void sendAuth() {
        if (webSocket == null) return;
        try {
            JSONObject auth = new JSONObject();
            auth.put("type", "auth");
            auth.put("access_token", accessToken);
            webSocket.send(auth.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build auth message", e);
        }
    }

    public void startPipeline(String pipelineId) {
        if (authState != AuthState.AUTHENTICATED || webSocket == null) {
            callback.onError("Cannot start pipeline – not authenticated");
            return;
        }
        sttBinaryHandlerId = -1;
        try {
            JSONObject msg = new JSONObject();
            msg.put("id", messageId.getAndIncrement());
            msg.put("type", "assist_pipeline/run");
            msg.put("start_stage", "stt");
            msg.put("end_stage", "tts");
            JSONObject input = new JSONObject();
            input.put("sample_rate", 16000);
            msg.put("input", input);
            if (pipelineId != null && !pipelineId.isEmpty()) {
                msg.put("pipeline", pipelineId);
            }
            webSocket.send(msg.toString());
            Log.d(TAG, "Pipeline run requested");
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build pipeline/run message", e);
        }
    }

    // 16khz 16bit little endian mono
    public boolean sendAudio(byte[] pcmData, int length) {
        if (webSocket == null || sttBinaryHandlerId < 0) return false;
        byte[] frame = new byte[length + 1];
        frame[0] = (byte) sttBinaryHandlerId;
        System.arraycopy(pcmData, 0, frame, 1, length);
        return webSocket.send(ByteString.of(frame));
    }

    public void endAudio() {
        if (webSocket == null || sttBinaryHandlerId < 0) return;
        webSocket.send(ByteString.of(new byte[]{(byte) sttBinaryHandlerId}));
        Log.d(TAG, "Audio stream ended");
    }

    public void close() {
        if (webSocket != null) {
            webSocket.close(1000, "Session complete");
            webSocket = null;
        }
        authState = AuthState.WAITING;
        sttBinaryHandlerId = -1;
    }

    public boolean isAuthenticated() {
        return authState == AuthState.AUTHENTICATED;
    }
}
