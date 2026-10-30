package dev.strafesrv.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.strafesrv.util.Json;
import dev.strafesrv.util.Text;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Minimal Strafe REST client. All calls run on ONE worker thread, so requests are
 * ordered and a 429 pauses the whole queue (which is what we want for a chat bridge).
 */
public final class StrafeApi {

    public static final class Response {
        public final int status;
        public final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public JsonElement element() {
            return Json.parse(body);
        }

        public JsonObject object() {
            return Json.parseObject(body);
        }
    }

    private final String base;
    private final String token;
    private final String userAgent;
    private final Logger log;
    private final int maxLength;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "StrafeSRV-REST");
        t.setDaemon(true);
        return t;
    });

    public StrafeApi(String base, String token, String version, Logger log, int maxLength) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.token = token;
        this.userAgent = "StrafeSRV/" + version;
        this.log = log;
        this.maxLength = Math.max(100, maxLength);
    }

    /** Lets queued requests finish (up to 5 s), then stops the worker. */
    public void shutdown() {
        exec.shutdown();
        try {
            if (!exec.awaitTermination(5, TimeUnit.SECONDS)) exec.shutdownNow();
        } catch (InterruptedException e) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public CompletableFuture<Response> request(String method, String path, JsonObject body) {
        CompletableFuture<Response> f = new CompletableFuture<>();
        try {
            exec.execute(() -> {
                try {
                    f.complete(execute(method, path, body));
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            f.completeExceptionally(e);
        }
        return f;
    }

    private Response execute(String method, String path, JsonObject body) throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bot " + token)
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent);
            if (body != null) {
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(Json.GSON.toJson(body)));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> res;
            try {
                res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                if (attempt == 3) throw e;
                Thread.sleep(1000L * (attempt + 1));
                continue;
            }

            int status = res.statusCode();
            if (status == 429) {
                Thread.sleep(retryAfterMillis(res));
                continue;
            }
            if (status >= 500 && attempt < 3) {
                Thread.sleep(1000L << attempt);
                continue;
            }
            return new Response(status, res.body());
        }
        return new Response(429, "{\"error\":\"rate limited; gave up after retries\"}");
    }

    private static long retryAfterMillis(HttpResponse<String> res) {
        double seconds = 1.0;
        String header = res.headers().firstValue("Retry-After").orElse(null);
        try {
            if (header != null) {
                seconds = Double.parseDouble(header.trim());
            } else {
                JsonObject o = Json.parseObject(res.body());
                if (o != null && o.has("retry_after")) seconds = o.get("retry_after").getAsDouble();
            }
        } catch (RuntimeException ignored) {
            // keep default
        }
        return Math.max(500L, Math.min(30_000L, (long) (seconds * 1000)));
    }

    // ───────────────────────── convenience calls ─────────────────────────

    /** Posts one message. Mentions are never auto-added: only {@code <@id>} tokens already in the text ping. */
    public CompletableFuture<Response> sendMessage(String roomId, String text, String replyToId) {
        JsonObject b = new JsonObject();
        b.addProperty("plaintext", text);
        b.add("mentions", new JsonArray());
        b.add("mention_roles", new JsonArray());
        b.addProperty("mention_everyone", false);
        if (replyToId != null) b.addProperty("reply_to_id", replyToId);
        return request("POST", "/rooms/" + roomId + "/messages", b).thenApply(r -> {
            if (!r.ok()) log.warning("Could not post to Strafe room " + roomId + ": HTTP " + r.status + " " + r.body);
            return r;
        });
    }

    /** Posts text, splitting it into several messages when it exceeds the configured length. */
    public CompletableFuture<Response> sendText(String roomId, String text) {
        CompletableFuture<Response> last = CompletableFuture.completedFuture(null);
        for (String part : Text.chunk(text, maxLength)) last = sendMessage(roomId, part, null);
        return last;
    }

    public CompletableFuture<Response> react(String roomId, String messageId, String emoji) {
        String e = URLEncoder.encode(emoji, StandardCharsets.UTF_8).replace("+", "%20");
        return request("PUT", "/rooms/" + roomId + "/messages/" + messageId + "/reactions/" + e, null);
    }

    public CompletableFuture<Response> grantRole(String spaceId, String userId, String roleId) {
        return request("PUT", "/spaces/" + spaceId + "/members/" + userId + "/roles/" + roleId, null);
    }

    public CompletableFuture<Response> revokeRole(String spaceId, String userId, String roleId) {
        return request("DELETE", "/spaces/" + spaceId + "/members/" + userId + "/roles/" + roleId, null);
    }
}
