package me.rapierxbox.shellyelevatev2.api;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import fi.iki.elonen.NanoWSD;
import me.rapierxbox.shellyelevatev2.SettingsParser;
import me.rapierxbox.shellyelevatev2.settings.SettingsRegistry;

// protocol v1 over https and wss on port 8443 with the keystore certificate from TlsIdentity
// everything except hello and the two pairing calls needs a bearer token
final class ApiServer extends NanoWSD implements ApiHub.Sink {
    private static final String TAG = "ApiServer";
    private static final String JSON = "application/json";
    private static final String PREFIX = "/api/v1";
    private static final int MAX_BODY = 1024 * 1024;
    // hello and the pairing requests are small json objects
    private static final int MAX_OPEN_BODY = 4 * 1024;

    private final Context context;
    private final ClientTokenStore tokens;
    private final Pairing pairing;
    private final StateHub stateHub;
    private final SettingsParser settingsParser = new SettingsParser();
    private final CopyOnWriteArrayList<ControllerSocket> sockets = new CopyOnWriteArrayList<>();
    // ws commands run here so a slow one never stops the socket from reading
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "ApiCommands"));
    // closing waits for a socket write in progress so it never runs on a reader or the main thread
    private final ExecutorService closeExecutor = Executors.newCachedThreadPool(r -> new Thread(r, "ApiClose"));
    // a controller that answered no ping for this long is gone
    static final long PONG_TIMEOUT_MS = 50_000;

    ApiServer(Context context, ClientTokenStore tokens, Pairing pairing, StateHub stateHub) {
        super(ApiInfo.TLS_PORT);
        this.context = context.getApplicationContext();
        this.tokens = tokens;
        this.pairing = pairing;
        this.stateHub = stateHub;
    }

    // ------------------------------------------------------------------ routing

    @Override
    public Response serve(IHTTPSession session) {
        if ((PREFIX + "/ws").equals(session.getUri())) {
            ClientTokenStore.Client client = authenticate(session);
            if (client == null) return error(Status.UNAUTHORIZED, "unauthorized", "token rejected");
            if (!isWebsocketRequested(session)) return error(Status.BAD_REQUEST, "invalid_params", "websocket upgrade expected");
            return super.serve(session);
        }
        return serveHttp(session);
    }

    @Override
    protected Response serveHttp(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        try {
            boolean open = ((PREFIX + "/hello").equals(uri) && method == Method.GET)
                    || ((PREFIX + "/pair").equals(uri) || (PREFIX + "/pair/confirm").equals(uri)) && method == Method.POST;
            ClientTokenStore.Client client = open ? null : authenticate(session);
            // a stranger never makes us allocate more than a pairing request needs
            if (!open && client == null) {
                Response denied = uri.startsWith(PREFIX + "/")
                        ? error(Status.UNAUTHORIZED, "unauthorized", "token rejected")
                        : error(Status.NOT_FOUND, "unknown_action", "not found");
                // the unread body would otherwise be parsed as the next request
                denied.closeConnection(true);
                return denied;
            }

            // read the body up front so a keep alive connection never sees leftovers
            byte[] body = readBody(session, open ? MAX_OPEN_BODY : MAX_BODY);
            if (body == null) {
                Response tooLarge = error(Status.PAYLOAD_TOO_LARGE, "invalid_params", "body too large");
                tooLarge.closeConnection(true);
                return tooLarge;
            }

            if ((PREFIX + "/hello").equals(uri) && method == Method.GET) {
                return json(Status.OK, ApiInfo.hello(tokens.hasClients()));
            }
            if ((PREFIX + "/pair").equals(uri) && method == Method.POST) return pairStart(body);
            if ((PREFIX + "/pair/confirm").equals(uri) && method == Method.POST) return pairConfirm(body);

            if (!uri.startsWith(PREFIX + "/")) return error(Status.NOT_FOUND, "unknown_action", "not found");

            switch (uri.substring(PREFIX.length())) {
                case "/pair":
                    if (method != Method.DELETE) break;
                    tokens.revoke(client.id);
                    return json(Status.OK, new JSONObject().put("success", true));
                case "/pair/rotate":
                    if (method != Method.POST) break;
                    return json(Status.OK, new JSONObject().put("token", tokens.rotate(client)));
                case "/info":
                    if (method != Method.GET) break;
                    return json(Status.OK, ApiInfo.info(context));
                case "/state":
                    if (method != Method.GET) break;
                    return json(Status.OK, new JSONObject().put("state", stateHub.snapshot()));
                case "/settings":
                    if (method == Method.GET) return json(Status.OK, settingsJson());
                    if ("PATCH".equals(method.name()) || method == Method.PUT) return patchSettings(body);
                    break;
                case "/settings/schema":
                    if (method != Method.GET) break;
                    return json(Status.OK, SettingsRegistry.schemaJson());
                case "/command":
                    if (method != Method.POST) break;
                    return httpCommand(body);
                case "/screenshot":
                    if (method != Method.GET) break;
                    return screenshot();
                case "/logs":
                    if (method != Method.GET) break;
                    return logs(session.getParms().get("lines"));
                default:
                    return error(Status.NOT_FOUND, "unknown_action", "not found");
            }
            return error(Status.METHOD_NOT_ALLOWED, "invalid_params", "method not allowed");
        } catch (JSONException e) {
            return error(Status.BAD_REQUEST, "invalid_params", "invalid json");
        } catch (Exception e) {
            Log.e(TAG, "Request " + method + " " + uri + " failed", e);
            return error(Status.INTERNAL_ERROR, "internal", e.getClass().getSimpleName());
        }
    }

    private ClientTokenStore.Client authenticate(IHTTPSession session) {
        String header = session.getHeaders().get("authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        return tokens.authenticate(header.substring(7).trim());
    }

    // ------------------------------------------------------------------ pairing

    private Response pairStart(byte[] body) throws JSONException {
        JSONObject request = parseObject(body);
        String clientId = request.optString("client_id", "").trim();
        if (clientId.isEmpty() || clientId.length() > 128) return error(Status.BAD_REQUEST, "invalid_params", "client_id required");
        String clientName = request.optString("client_name", "Controller").trim();
        if (clientName.length() > 64) clientName = clientName.substring(0, 64);
        try {
            Pairing.Pending pending = pairing.start(clientId, clientName);
            PairingActivity.show(context, pending);
            return json(Status.OK, new JSONObject()
                    .put("pairing_id", pending.id)
                    .put("expires_in", Pairing.EXPIRY_MS / 1000));
        } catch (Pairing.RateLimited e) {
            return error(Status.TOO_MANY_REQUESTS, "rate_limited", e.getMessage());
        }
    }

    private Response pairConfirm(byte[] body) throws Exception {
        JSONObject request = parseObject(body);
        String pairingId = request.optString("pairing_id", "");
        String proof = request.optString("proof", "");
        Pairing.Pending pending = pairing.current();
        // a made up id must not take the code off the screen
        boolean ours = pending != null && pending.id.equals(pairingId);
        Pairing.Pending[] matched = new Pairing.Pending[1];
        Pairing.Outcome outcome = pairing.confirm(pairingId, proof, TlsIdentity.get().certificateSha256(), matched);
        switch (outcome) {
            case OK:
                String token = tokens.issue(matched[0].clientId, matched[0].clientName);
                PairingActivity.finish(context, true);
                Log.i(TAG, "Paired " + matched[0].clientName);
                return json(Status.OK, new JSONObject().put("token", token));
            case INVALID:
                if (ours && pairing.current() == null) PairingActivity.finish(context, false);
                return error(Status.FORBIDDEN, "invalid_code", "the code does not match");
            default:
                if (ours) PairingActivity.finish(context, false);
                return error(Status.GONE, "expired", "pairing expired");
        }
    }

    // ------------------------------------------------------------------ settings

    private JSONObject settingsJson() throws JSONException {
        return new JSONObject().put("settings",
                SettingsRegistry.resolvedSettings(me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences, true));
    }

    private Response patchSettings(byte[] body) throws JSONException {
        JSONObject patch = parseObject(body);
        try {
            SettingsParser.PatchResult result = settingsParser.applyPatch(patch);
            JSONObject response = settingsJson();
            response.put("restart_required", result.restartRequired);
            if (result.ignored.length() > 0) response.put("ignored", result.ignored);
            return json(Status.OK, response);
        } catch (IllegalArgumentException e) {
            return error(Status.BAD_REQUEST, "invalid_params", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ commands

    private Response httpCommand(byte[] body) throws JSONException {
        JSONObject request = parseObject(body);
        String action = request.optString("action", "");
        JSONObject params = request.optJSONObject("params");
        try {
            JSONObject data = runCommand(action, params != null ? params : new JSONObject());
            return json(Status.OK, new JSONObject().put("success", true).put("data", data != null ? data : new JSONObject()));
        } catch (ApiHub.CommandException e) {
            return error(statusFor(e.code), e.code, e.getMessage());
        }
    }

    static JSONObject runCommand(String action, JSONObject params) throws ApiHub.CommandException {
        ApiHub.CommandHandler handler = ApiHub.command(action);
        if (handler == null) throw new ApiHub.CommandException("unknown_action", "unknown action " + action);
        try {
            return handler.handle(params);
        } catch (ApiHub.CommandException e) {
            throw e;
        } catch (RuntimeException e) {
            Log.e(TAG, "Command " + action + " failed", e);
            throw new ApiHub.CommandException("internal", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Response.IStatus statusFor(String code) {
        switch (code) {
            case "unknown_action": return Status.NOT_FOUND;
            case "invalid_params":
            case "unsupported": return Status.BAD_REQUEST;
            case "busy":
            case "origin_mismatch": return Status.CONFLICT;
            default: return Status.INTERNAL_ERROR;
        }
    }

    // ------------------------------------------------------------------ screenshot and logs

    private Response screenshot() {
        byte[] png = ScreenCapture.capturePng(context);
        if (png == null) return error(Status.CONFLICT, "busy", "nothing to capture right now");
        return newFixedLengthResponse(Status.OK, "image/png", new ByteArrayInputStream(png), png.length);
    }

    private Response logs(String linesParam) {
        int lines = 500;
        try {
            if (linesParam != null) lines = Math.max(1, Math.min(5000, Integer.parseInt(linesParam)));
        } catch (NumberFormatException ignored) {
            // keep the default
        }
        return newFixedLengthResponse(Status.OK, "text/plain; charset=utf-8", AppLog.read(lines));
    }

    // ------------------------------------------------------------------ websocket

    @Override
    protected WebSocket openWebSocket(IHTTPSession handshake) {
        return new ControllerSocket(handshake, authenticate(handshake));
    }

    @Override
    public boolean hasController() {
        return !sockets.isEmpty();
    }

    // queues on every socket without blocking. the state hub calls it under its lock so the
    // order of snapshots and deltas is the queue order of each socket
    @Override
    public void sendText(String json) {
        for (ControllerSocket socket : sockets) socket.queue(json);
    }

    @Override
    public void sendBinary(byte[] frame) {
        for (ControllerSocket socket : sockets) socket.queue(frame);
    }

    // aiohttp only pings when it received nothing for a while so a busy display pings itself
    // and drops a controller that stopped answering
    void pingAll() {
        long now = System.currentTimeMillis();
        for (ControllerSocket socket : sockets) {
            if (now - socket.lastHeard > PONG_TIMEOUT_MS) {
                Log.w(TAG, "Controller " + socket.client.name + " stopped answering");
                socket.closeQuietly(WebSocketFrame.CloseCode.GoingAway, "timeout");
            } else {
                socket.queuePing();
            }
        }
    }

    // closes the sockets of clients whose token was revoked or rotated
    void dropStaleSockets() {
        List<ClientTokenStore.Client> current = tokens.list();
        for (ControllerSocket socket : sockets) {
            boolean valid = false;
            for (ClientTokenStore.Client c : current) {
                if (c.id.equals(socket.client.id) && c.tokenHash.equals(socket.client.tokenHash)) valid = true;
            }
            if (!valid) socket.closeQuietly(WebSocketFrame.CloseCode.PolicyViolation, "revoked");
        }
    }

    void closeAllSockets() {
        for (ControllerSocket socket : sockets) socket.closeQuietly(WebSocketFrame.CloseCode.GoingAway, "shutting down");
        commandExecutor.shutdownNow();
        closeExecutor.shutdown();
    }

    private void onSocketOpen(ControllerSocket socket) {
        // one connection per controller. the newer one wins
        for (ControllerSocket other : sockets) {
            if (other != socket && other.client.id.equals(socket.client.id)) {
                other.closeQuietly(WebSocketFrame.CloseCode.GoingAway, "replaced");
            }
        }
        String hello;
        try {
            hello = new JSONObject()
                    .put("type", "hello")
                    .put("api", ApiInfo.API_VERSION)
                    .put("info", ApiInfo.info(context)).toString();
        } catch (JSONException e) {
            Log.e(TAG, "Could not build hello", e);
            socket.closeQuietly(WebSocketFrame.CloseCode.InternalServerError, "no hello");
            return;
        }
        // queued and registered under the state lock so the snapshot comes before every later delta
        boolean[] first = new boolean[1];
        stateHub.attach(state -> {
            socket.queue(hello);
            socket.queue(wrap("state", "state", state));
            synchronized (sockets) {
                if (!socket.isOpen()) return;
                sockets.add(socket);
                first[0] = sockets.size() == 1;
            }
        });
        if (!sockets.contains(socket)) return;
        Log.i(TAG, "Controller " + socket.client.name + " connected");
        if (first[0]) controllerChanged(true);
        else ApiHub.notifyControllerChanged(true);
    }

    private void onSocketClosed(ControllerSocket socket) {
        boolean removed;
        boolean last;
        synchronized (sockets) {
            removed = sockets.remove(socket);
            last = sockets.isEmpty();
        }
        if (!removed) return;
        Log.i(TAG, "Controller " + socket.client.name + " disconnected");
        if (last) controllerChanged(false);
    }

    private void controllerChanged(boolean connected) {
        stateHub.onControllerChanged(connected);
        ApiHub.notifyControllerChanged(connected);
    }

    final class ControllerSocket extends WebSocket {
        // ble adverts are dropped first and audio only when far behind. text is never dropped
        private static final int MAX_PENDING_BLE = 64;
        private static final int MAX_PENDING_BINARY = 512;
        private static final int CHANNEL_BLE = 0x02;

        final ClientTokenStore.Client client;
        // one writer per socket so a stalled peer only stalls itself
        private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> new Thread(r, "ApiSend"));
        private final AtomicInteger pendingBinary = new AtomicInteger();
        volatile long lastHeard = System.currentTimeMillis();
        private volatile boolean closing;

        ControllerSocket(IHTTPSession handshake, ClientTokenStore.Client client) {
            super(handshake);
            this.client = client;
        }

        void queue(String text) {
            submit(() -> sendSafely(text));
        }

        void queue(byte[] frame) {
            int limit = frame.length > 0 && frame[0] == CHANNEL_BLE ? MAX_PENDING_BLE : MAX_PENDING_BINARY;
            if (pendingBinary.incrementAndGet() > limit) {
                pendingBinary.decrementAndGet();
                return;
            }
            submit(() -> {
                pendingBinary.decrementAndGet();
                sendSafely(frame);
            });
        }

        void queuePing() {
            submit(() -> {
                try {
                    if (isOpen()) ping(new byte[]{1});
                } catch (IOException e) {
                    closeQuietly(WebSocketFrame.CloseCode.AbnormalClosure, "ping failed");
                }
            });
        }

        private void submit(Runnable task) {
            if (closing) return;
            try {
                writer.execute(task);
            } catch (RejectedExecutionException ignored) {
                // closed
            }
        }

        @Override
        protected void onOpen() {
            if (client == null) {
                closeQuietly(WebSocketFrame.CloseCode.PolicyViolation, "unauthorized");
                return;
            }
            onSocketOpen(this);
        }

        @Override
        protected void onClose(WebSocketFrame.CloseCode code, String reason, boolean initiatedByRemote) {
            closing = true;
            onSocketClosed(this);
            writer.shutdown();
        }

        @Override
        protected void onMessage(WebSocketFrame frame) {
            lastHeard = System.currentTimeMillis();
            // a replaced or revoked socket may still deliver a frame it read before closing
            if (closing || !sockets.contains(this)) return;
            if (frame.getOpCode() != WebSocketFrame.OpCode.Text) return;
            JSONObject message;
            try {
                message = new JSONObject(frame.getTextPayload());
            } catch (JSONException e) {
                Log.d(TAG, "Ignoring invalid frame");
                return;
            }
            switch (message.optString("type")) {
                case "command":
                    int id = message.optInt("id", -1);
                    String action = message.optString("action", "");
                    JSONObject params = message.optJSONObject("params");
                    try {
                        commandExecutor.execute(() -> runAndReply(id, action, params != null ? params : new JSONObject()));
                    } catch (RejectedExecutionException e) {
                        reply(id, null, new ApiHub.CommandException("busy", "shutting down"));
                    }
                    break;
                case "get_state":
                    // under the state lock so no older delta can follow the snapshot
                    stateHub.attach(state -> queue(wrap("state", "state", state)));
                    break;
                case "ping":
                    queue("{\"type\":\"pong\"}");
                    break;
                default:
                    // unknown messages are ignored per the versioning rules
                    break;
            }
        }

        private void runAndReply(int id, String action, JSONObject params) {
            try {
                reply(id, runCommand(action, params), null);
            } catch (ApiHub.CommandException e) {
                reply(id, null, e);
            }
        }

        private void reply(int id, JSONObject data, ApiHub.CommandException error) {
            try {
                JSONObject result = new JSONObject().put("type", "result").put("id", id).put("success", error == null);
                if (error == null) {
                    result.put("data", data != null ? data : new JSONObject());
                } else {
                    result.put("error", error.code).put("message", error.getMessage());
                }
                queue(result.toString());
            } catch (JSONException e) {
                Log.w(TAG, "Could not build result", e);
            }
        }

        @Override
        protected void onPong(WebSocketFrame pong) {
            lastHeard = System.currentTimeMillis();
        }

        @Override
        protected void onException(IOException exception) {
            Log.d(TAG, "Socket of " + (client != null ? client.name : "?") + " failed: " + exception);
            onSocketClosed(this);
        }

        void sendSafely(String text) {
            try {
                if (isOpen()) send(text);
            } catch (IOException e) {
                closeQuietly(WebSocketFrame.CloseCode.AbnormalClosure, "send failed");
            }
        }

        void sendSafely(byte[] frame) {
            try {
                if (isOpen()) send(frame);
            } catch (IOException e) {
                closeQuietly(WebSocketFrame.CloseCode.AbnormalClosure, "send failed");
            }
        }

        // returns at once. the close frame waits for a write in progress on another thread
        void closeQuietly(WebSocketFrame.CloseCode code, String reason) {
            closing = true;
            onSocketClosed(this);
            writer.shutdown();
            try {
                closeExecutor.execute(() -> {
                    try {
                        close(code, reason, false);
                    } catch (IOException | RuntimeException ignored) {
                        // already gone
                    }
                });
            } catch (RejectedExecutionException ignored) {
                // shutting down
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] readBody(IHTTPSession session, int maxBody) throws IOException {
        String length = session.getHeaders().get("content-length");
        if (length == null) return new byte[0];
        int size;
        try {
            size = Integer.parseInt(length.trim());
        } catch (NumberFormatException e) {
            return new byte[0];
        }
        if (size > maxBody) return null;
        byte[] body = new byte[Math.max(0, size)];
        InputStream in = session.getInputStream();
        int read = 0;
        while (read < body.length) {
            int n = in.read(body, read, body.length - read);
            if (n < 0) break;
            read += n;
        }
        return body;
    }

    private static JSONObject parseObject(byte[] body) throws JSONException {
        String text = new String(body, StandardCharsets.UTF_8).trim();
        return text.isEmpty() ? new JSONObject() : new JSONObject(text);
    }

    private static String wrap(String type, String key, JSONObject value) {
        try {
            return new JSONObject().put("type", type).put(key, value).toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    private static Response json(Response.IStatus status, JSONObject body) {
        return newFixedLengthResponse(status, JSON, body.toString());
    }

    static Response error(Response.IStatus status, String code, String message) {
        JSONObject body = new JSONObject();
        try {
            body.put("success", false).put("error", code).put("message", message == null ? "" : message);
        } catch (JSONException ignored) {
            // constant keys
        }
        return newFixedLengthResponse(status, JSON, body.toString());
    }

    // the subset of statuses nanohttpd 2.3.1 lacks
    enum Status implements Response.IStatus {
        OK(200, "OK"),
        BAD_REQUEST(400, "Bad Request"),
        UNAUTHORIZED(401, "Unauthorized"),
        FORBIDDEN(403, "Forbidden"),
        NOT_FOUND(404, "Not Found"),
        METHOD_NOT_ALLOWED(405, "Method Not Allowed"),
        CONFLICT(409, "Conflict"),
        GONE(410, "Gone"),
        PAYLOAD_TOO_LARGE(413, "Payload Too Large"),
        TOO_MANY_REQUESTS(429, "Too Many Requests"),
        INTERNAL_ERROR(500, "Internal Server Error");

        private final int code;
        private final String description;

        Status(int code, String description) {
            this.code = code;
            this.description = description;
        }

        @Override
        public String getDescription() {
            return code + " " + description;
        }

        @Override
        public int getRequestStatus() {
            return code;
        }
    }
}
