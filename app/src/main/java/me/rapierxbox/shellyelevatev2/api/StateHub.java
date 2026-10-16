package me.rapierxbox.shellyelevatev2.api;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// the state_delta push of protocol-v1 section 5
// a state change only pokes it. it rebuilds the state and pushes the keys that changed
final class StateHub {
    private static final String TAG = "ApiState";
    private static final long POKE_DEBOUNCE_MS = 50;
    // sht3x values have no change broadcast so they are polled
    private static final long SENSOR_PERIOD_S = 10;
    // values that change all the time are only refreshed this often
    private static final long SLOW_PERIOD_S = 60;

    interface Pusher {
        void push(JSONObject message);
    }

    private final StateReader reader;
    private final Pusher pusher;
    private final Runnable listener = this::poke;
    private final AtomicBoolean pokeScheduled = new AtomicBoolean(false);
    private Map<String, Object> last = new LinkedHashMap<>();
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> sensorTick;
    private ScheduledFuture<?> slowTick;

    StateHub(Context context, Pusher pusher) {
        this.reader = StateReader.get(context);
        this.pusher = pusher;
    }

    synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, TAG));
        ApiHub.addStateListener(listener);
    }

    synchronized void stop() {
        ApiHub.removeStateListener(listener);
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    // sensors are only polled while a controller listens
    synchronized void onControllerChanged(boolean connected) {
        if (executor == null) return;
        if (connected && sensorTick == null) {
            sensorTick = executor.scheduleWithFixedDelay(() -> refresh(reader::refreshSensors), 0, SENSOR_PERIOD_S, TimeUnit.SECONDS);
            slowTick = executor.scheduleWithFixedDelay(() -> refresh(reader::refreshSlow), 0, SLOW_PERIOD_S, TimeUnit.SECONDS);
        } else if (!connected && sensorTick != null) {
            sensorTick.cancel(false);
            slowTick.cancel(false);
            sensorTick = null;
            slowTick = null;
        }
    }

    void poke() {
        if (!ApiHub.hasController() || !pokeScheduled.compareAndSet(false, true)) return;
        ScheduledExecutorService ex = executor;
        try {
            if (ex != null) {
                ex.schedule(() -> {
                    pokeScheduled.set(false);
                    pushDelta();
                }, POKE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
                return;
            }
        } catch (RejectedExecutionException ignored) {
            // shutting down
        }
        pokeScheduled.set(false);
    }

    // the current state without touching the delta baseline. for GET /api/v1/state
    JSONObject snapshot() {
        return reader.snapshot(true);
    }

    // pushes pending changes to the connected controllers then hands a fresh snapshot to a new one
    // runs under the lock that pushDelta uses so the new controller never misses a change in between
    synchronized void attach(SnapshotConsumer consumer) {
        Map<String, Object> state = reader.build(true);
        Map<String, Object> changes = diff(last, state);
        if (!changes.isEmpty()) send(changes);
        last = state;
        consumer.accept(StateReader.toJson(state));
    }

    interface SnapshotConsumer {
        void accept(JSONObject state);
    }

    private synchronized void pushDelta() {
        try {
            Map<String, Object> state = reader.build(false);
            Map<String, Object> changes = diff(last, state);
            if (changes.isEmpty()) return;
            last = state;
            send(changes);
        } catch (RuntimeException e) {
            Log.w(TAG, "State update failed", e);
        }
    }

    private void send(Map<String, Object> changes) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "state_delta");
            message.put("changes", StateReader.toJson(changes));
            pusher.push(message);
        } catch (JSONException e) {
            Log.w(TAG, "Could not build state_delta", e);
        }
    }

    private void refresh(Runnable read) {
        try {
            read.run();
            pushDelta();
        } catch (RuntimeException e) {
            Log.w(TAG, "Sensor refresh failed", e);
        }
    }

    private static Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : after.entrySet()) {
            if (!before.containsKey(entry.getKey()) || !Objects.equals(before.get(entry.getKey()), entry.getValue())) {
                changes.put(entry.getKey(), entry.getValue());
            }
        }
        // a key that went away is reported as null
        for (String key : before.keySet()) {
            if (!after.containsKey(key)) changes.put(key, null);
        }
        return changes;
    }
}
