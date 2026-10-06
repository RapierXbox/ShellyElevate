package me.rapierxbox.shellyelevatev2.api;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// meeting point between the v1 api server and the features that talk to a paired controller
// features register commands and state here and never touch the server directly
public final class ApiHub {
    private static final String TAG = "ApiHub";

    private ApiHub() {}

    // runs a v1 command and returns its data object or null for none
    // called on a server thread so handlers post to the main thread themselves when needed
    public interface CommandHandler {
        JSONObject handle(JSONObject params) throws CommandException;
    }

    // adds flat state keys from protocol-v1 section 5 to a snapshot
    public interface StateProvider {
        void contribute(Map<String, Object> state);
    }

    public interface ControllerListener {
        void onControllerChanged(boolean connected);
    }

    // implemented by the websocket server to reach the connected controllers
    public interface Sink {
        boolean hasController();
        void sendText(String json);
        void sendBinary(byte[] frame);
    }

    public static final class CommandException extends Exception {
        public final String code;

        public CommandException(String code, String message) {
            super(message);
            this.code = code;
        }

        public static CommandException invalid(String message) {
            return new CommandException("invalid_params", message);
        }

        public static CommandException unsupported(String message) {
            return new CommandException("unsupported", message);
        }
    }

    private static final Map<String, CommandHandler> commands = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<StateProvider> stateProviders = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<ControllerListener> controllerListeners = new CopyOnWriteArrayList<>();
    private static volatile Sink sink;
    private static volatile Runnable stateChangedHook;

    public static void registerCommand(String action, CommandHandler handler) {
        commands.put(action, handler);
    }

    public static void unregisterCommand(String action, CommandHandler handler) {
        commands.remove(action, handler);
    }

    public static CommandHandler command(String action) {
        return commands.get(action);
    }

    public static void addStateProvider(StateProvider provider) {
        stateProviders.addIfAbsent(provider);
    }

    public static void removeStateProvider(StateProvider provider) {
        stateProviders.remove(provider);
    }

    public static Iterable<StateProvider> stateProviders() {
        return stateProviders;
    }

    public static void addControllerListener(ControllerListener listener) {
        controllerListeners.addIfAbsent(listener);
    }

    public static void removeControllerListener(ControllerListener listener) {
        controllerListeners.remove(listener);
    }

    public static boolean hasController() {
        Sink s = sink;
        return s != null && s.hasController();
    }

    // text frame to every connected controller. no-op without one
    public static void send(JSONObject message) {
        Sink s = sink;
        if (s == null || !s.hasController()) return;
        s.sendText(message.toString());
    }

    public static void send(String type, JSONObject payload) {
        try {
            JSONObject message = payload != null ? new JSONObject(payload.toString()) : new JSONObject();
            message.put("type", type);
            send(message);
        } catch (JSONException e) {
            Log.w(TAG, "Could not build " + type, e);
        }
    }

    // binary frame on a protocol channel such as 0x01 audio or 0x02 ble
    public static void sendBinary(int channel, byte[] payload, int offset, int length) {
        Sink s = sink;
        if (s == null || !s.hasController()) return;
        byte[] frame = new byte[length + 1];
        frame[0] = (byte) channel;
        System.arraycopy(payload, offset, frame, 1, length);
        s.sendBinary(frame);
    }

    // a protocol event such as button swipe or power_button
    public static void event(String name, JSONObject fields) {
        try {
            JSONObject message = fields != null ? new JSONObject(fields.toString()) : new JSONObject();
            message.put("type", "event");
            message.put("event", name);
            send(message);
        } catch (JSONException e) {
            Log.w(TAG, "Could not build event " + name, e);
        }
    }

    // tells the state hub that something changed so it diffs and pushes a state_delta
    public static void stateChanged() {
        Runnable hook = stateChangedHook;
        if (hook != null) hook.run();
    }

    static void setSink(Sink newSink) {
        sink = newSink;
    }

    static void setStateChangedHook(Runnable hook) {
        stateChangedHook = hook;
    }

    static void notifyControllerChanged(boolean connected) {
        for (ControllerListener listener : controllerListeners) {
            try {
                listener.onControllerChanged(connected);
            } catch (RuntimeException e) {
                Log.w(TAG, "Controller listener failed", e);
            }
        }
    }
}
