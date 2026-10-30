package dev.strafesrv.gateway;

import com.google.gson.JsonObject;
import dev.strafesrv.util.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Strafe gateway ("Stargate") client, using the JDK's built-in WebSocket.
 *
 * Protocol (https://app.strafe.chat/docs/gateway/): bots authenticate with an
 * "Authorization: Bot &lt;token&gt;" header on the handshake; the first frame is READY (op 4);
 * events are op 3 with {t, space_id, room_id, d}; bots are auto-subscribed to everything.
 * There is no resume: reconnect and read READY again.
 */
public final class GatewayClient {

    public interface Handler {
        void onReady(JsonObject d);

        /** @param envelope the outer event object ({t, space_id, room_id, user_id, d}); @param data the inner payload (never null). */
        void onEvent(String type, JsonObject envelope, JsonObject data);

        void onGatewayError(int code, String message);

        void onSessionRevoked(String reason);

        void onDisconnected();
    }

    private final String url;
    private final String token;
    private final Logger log;
    private final Handler handler;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "StrafeSRV-Gateway-Timer"));
    /** Single thread keeps events in order and keeps REST calls off the WebSocket thread. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> daemon(r, "StrafeSRV-Gateway-Worker"));

    private volatile boolean running;
    private volatile Connection current;
    private volatile int attempt;

    public GatewayClient(String url, String token, Logger log, Handler handler) {
        this.url = url;
        this.token = token;
        this.log = log;
        this.handler = handler;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    public void start() {
        running = true;
        connect();
    }

    public void stop() {
        running = false;
        Connection c = current;
        if (c != null) c.close();
        scheduler.shutdownNow();
        worker.shutdownNow();
    }

    public boolean isConnected() {
        Connection c = current;
        return c != null && !c.dead.get();
    }

    // ───────────────────────── outgoing ─────────────────────────

    public void send(JsonObject frame) {
        Connection c = current;
        if (c != null) c.send(Json.GSON.toJson(frame));
    }

    /** op 1: subscribe to a space or room (the field is "space_id" for both). */
    public void subscribe(String spaceOrRoomId) {
        JsonObject d = new JsonObject();
        d.addProperty("space_id", spaceOrRoomId);
        JsonObject f = new JsonObject();
        f.addProperty("op", 1);
        f.add("d", d);
        send(f);
    }

    /** op 9: "Playing &lt;name&gt;" (null/blank clears it). Must be re-sent after every READY. */
    public void setActivity(String name) {
        JsonObject f = new JsonObject();
        f.addProperty("op", 9);
        if (name == null || name.isBlank()) {
            f.add("d", com.google.gson.JsonNull.INSTANCE);
        } else {
            JsonObject act = new JsonObject();
            act.addProperty("type", "playing");
            act.addProperty("name", name);
            act.addProperty("started_at", System.currentTimeMillis());
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            arr.add(act);
            JsonObject d = new JsonObject();
            d.add("activities", arr);
            f.add("d", d);
        }
        send(f);
    }

    // ───────────────────────── connection handling ─────────────────────────

    private void connect() {
        if (!running) return;
        Connection c = new Connection();
        http.newWebSocketBuilder()
                .header("Authorization", "Bot " + token)
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(url), c)
                .whenComplete((ws, err) -> {
                    if (err == null) return;
                    Throwable cause = err.getCause() != null ? err.getCause() : err;
                    if (cause instanceof WebSocketHandshakeException) {
                        HttpResponse<?> r = ((WebSocketHandshakeException) cause).getResponse();
                        if (r != null && r.statusCode() == 401) {
                            log.severe("Strafe rejected the bot token (401). Check strafe.token in config.yml. Not retrying.");
                            running = false;
                            return;
                        }
                    }
                    log.warning("Could not connect to the Strafe gateway: " + cause);
                    c.failed();
                });
    }

    private void scheduleReconnect() {
        if (!running) return;
        int n = attempt++;
        long delay = Math.min(60L, 2L << Math.min(n, 5));
        log.info("Reconnecting to the Strafe gateway in " + delay + "s...");
        try {
            scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // shutting down
        }
    }

    private void handleFrame(String text) {
        try {
            JsonObject f = Json.parseObject(text);
            if (f == null) return;
            int op = Json.integer(f, "op", -1);
            JsonObject d = Json.obj(f, "d");
            switch (op) {
                case 4:
                    attempt = 0;
                    if (d != null) handler.onReady(d);
                    break;
                case 3:
                    if (d == null) break;
                    JsonObject inner = Json.obj(d, "d");
                    handler.onEvent(Json.str(d, "t"), d, inner != null ? inner : new JsonObject());
                    break;
                case 8:
                    handler.onGatewayError(Json.integer(d, "code", 0), Json.str(d, "message"));
                    break;
                default:
                    break; // 7 = pong, ignore
            }
        } catch (Throwable t) {
            log.log(java.util.logging.Level.WARNING, "Error while handling a gateway frame", t);
        }
    }

    private final class Connection implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();
        private final AtomicBoolean dead = new AtomicBoolean();
        private volatile WebSocket socket;
        private ScheduledFuture<?> keepalive;
        private CompletableFuture<Void> sendChain = CompletableFuture.completedFuture(null);

        @Override
        public void onOpen(WebSocket ws) {
            socket = ws;
            current = this;
            log.info("Strafe gateway socket open, waiting for READY...");
            try {
                // The server pings every 54 s and the JDK answers automatically; this is only a proxy keep-alive.
                keepalive = scheduler.scheduleAtFixedRate(() -> send("{\"op\":0}"), 30, 30, TimeUnit.SECONDS);
            } catch (RejectedExecutionException ignored) {
                // shutting down
            }
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                try {
                    worker.execute(() -> handleFrame(text));
                } catch (RejectedExecutionException ignored) {
                    // shutting down
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            log.info("Strafe gateway closed (" + statusCode + (reason == null || reason.isEmpty() ? "" : ": " + reason) + ").");
            failed();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.warning("Strafe gateway error: " + error);
            failed();
        }

        synchronized void send(String text) {
            WebSocket w = socket;
            if (w == null || dead.get()) return;
            // sendText must not be called again until the previous send completed, so chain them.
            sendChain = sendChain.thenCompose(v -> w.sendText(text, true)).handle((r, e) -> (Void) null);
        }

        void close() {
            WebSocket w = socket;
            dead.set(true);
            if (keepalive != null) keepalive.cancel(false);
            if (w != null) w.sendClose(WebSocket.NORMAL_CLOSURE, "plugin disabled");
        }

        void failed() {
            if (!dead.compareAndSet(false, true)) return;
            if (keepalive != null) keepalive.cancel(false);
            if (current == this) current = null;
            if (running) {
                handler.onDisconnected();
                scheduleReconnect();
            }
        }
    }
}
