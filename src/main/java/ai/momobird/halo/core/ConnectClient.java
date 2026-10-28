package ai.momobird.halo.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * A blocking client for MomoBird's connect API. It is meant for the sync engine's background
 * thread, never for a request thread.
 *
 * <ul>
 *   <li>One timeout bounds the whole exchange, body included.
 *   <li>Network errors and 5xx answers are retried after the given delays (1 s, then 3 s by
 *       default); other answers never are.
 *   <li>A response body over 5 MB is aborted and a cut-off one is an error.
 *   <li>Redirects are never followed, so the key cannot be sent to another host.
 *   <li>The key travels only in the {@code Authorization} header, and no error carries it.
 * </ul>
 *
 * <p>Results are the {@code data} member of MomoBird's {@code {code, data, message}} envelope.
 */
public final class ConnectClient {

    public static final String API_PREFIX = "/momobird/api/public/connect/v1";
    public static final int PAGE_SIZE = 500;
    public static final int MAX_BODY_BYTES = 5 * 1024 * 1024;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    public static final List<Duration> DEFAULT_RETRY_DELAYS = List.of(Duration.ofSeconds(1), Duration.ofSeconds(3));

    /** Waits between attempts; replaced in tests. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    // Error codes are short identifiers; anything else the server puts in `code` is ignored
    // rather than copied into a message.
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String key;
    private final String base;
    private final Duration timeout;
    private final List<Duration> retryDelays;
    private final Sleeper sleeper;
    private final HttpClient http;

    public ConnectClient(Settings settings) {
        this(settings, DEFAULT_TIMEOUT, DEFAULT_RETRY_DELAYS, Thread::sleep);
    }

    public ConnectClient(Settings settings, Duration timeout, List<Duration> retryDelays) {
        this(settings, timeout, retryDelays, Thread::sleep);
    }

    public ConnectClient(Settings settings, Duration timeout, List<Duration> retryDelays, Sleeper sleeper) {
        List<String> problems = settings.validate();
        if (!problems.isEmpty()) {
            // The messages never repeat the address or the key.
            throw new IllegalArgumentException(problems.get(0));
        }
        this.key = settings.key();
        this.base = settings.baseUrl() + API_PREFIX;
        this.timeout = timeout;
        this.retryDelays = List.copyOf(retryDelays);
        this.sleeper = sleeper;
        this.http = HttpClient.newBuilder()
            .followRedirects(Redirect.NEVER)
            .version(Version.HTTP_1_1)
            .connectTimeout(timeout)
            .build();
    }

    public JsonNode status() {
        return perform("GET", "/status", null);
    }

    /** One page of what MomoBird holds; pass the previous page's {@code next_cursor}, or null for the first. */
    public JsonNode listPosts(String cursor) {
        String path = "/posts?limit=" + PAGE_SIZE;
        if (cursor != null && !cursor.isEmpty()) {
            path += "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8).replace("+", "%20");
        }
        return perform("GET", path, null);
    }

    /** Pushes posts (any Jackson-serializable objects in connect's shape; at most 20 per call). */
    public JsonNode upsert(List<?> posts) {
        ObjectNode body = JSON.createObjectNode();
        body.set("posts", JSON.valueToTree(posts));
        return perform("PUT", "/posts", body);
    }

    /** Takes posts down by external id (at most 100 per call). */
    public JsonNode delete(List<String> ids) {
        ObjectNode body = JSON.createObjectNode();
        body.set("external_ids", JSON.valueToTree(ids));
        return perform("POST", "/posts/delete", body);
    }

    public JsonNode enableWidget() {
        return perform("POST", "/widget/enable", null);
    }

    // ------------------------------------------------------------------

    private JsonNode perform(String method, String path, JsonNode payload) {
        String body = payload == null ? null : payload.toString();
        for (int attempt = 0; ; attempt++) {
            ConnectError failure;
            try {
                return interpret(send(method, path, body));
            } catch (ConnectError e) {
                failure = e;
            }
            boolean retryable = failure.kind() == ConnectError.Kind.NETWORK
                || (failure.kind() == ConnectError.Kind.HTTP && failure.status() != null && failure.status() >= 500);
            if (!retryable || attempt >= retryDelays.size()) {
                throw failure;
            }
            try {
                sleeper.sleep(retryDelays.get(attempt).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ConnectError.network("请求被中断", key);
            }
        }
    }

    private record Answer(int status, byte[] body) {
    }

    private Answer send(String method, String path, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(timeout)
            .header("Authorization", "Bearer " + key)
            .header("Accept", "application/json");
        if (body == null) {
            builder.method(method, BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                .method(method, BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }

        CompletableFuture<HttpResponse<byte[]>> pending =
            http.sendAsync(builder.build(), info -> new CappedBody(MAX_BODY_BYTES));
        try {
            HttpResponse<byte[]> response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Answer(response.statusCode(), response.body());
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw ConnectError.network(timeoutMessage(), key);
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw ConnectError.network("请求被中断", key);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof HttpTimeoutException) {
                throw ConnectError.network(timeoutMessage(), key);
            }
            if (cause instanceof BodyTooLarge) {
                throw ConnectError.network("响应体超过 5 MB", key);
            }
            throw ConnectError.network(cause, key);
        }
    }

    private String timeoutMessage() {
        double seconds = timeout.toMillis() / 1000.0;
        String shown = seconds == Math.floor(seconds) ? String.valueOf((long) seconds) : String.valueOf(seconds);
        return "请求超时（" + shown + " 秒）";
    }

    /** Maps one answer to its {@code data}, or throws the matching {@link ConnectError}. */
    private JsonNode interpret(Answer answer) {
        JsonNode envelope = parseEnvelope(answer.body());
        int status = answer.status();
        if (status >= 200 && status < 300) {
            JsonNode data = envelope == null ? null : envelope.get("data");
            if (data != null && !data.isNull()) {
                return data;
            }
            throw ConnectError.http(status, null);
        }
        String code = null;
        if (envelope != null && envelope.get("code") != null && envelope.get("code").isTextual()) {
            String candidate = envelope.get("code").asText();
            if (CODE_PATTERN.matcher(candidate).matches() && Redactor.clean(candidate, key).equals(candidate)) {
                code = candidate;
            }
        }
        throw ConnectError.http(status, code);
    }

    private static JsonNode parseEnvelope(byte[] body) {
        try {
            JsonNode value = JSON.readTree(body);
            return value != null && value.isObject() ? value : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------

    private static final class BodyTooLarge extends IOException {
        private static final long serialVersionUID = 1L;

        BodyTooLarge() {
            super("response body too large", null);
        }
    }

    /** Collects the body, cancelling the exchange as soon as it passes {@code limit} bytes. */
    private static final class CappedBody implements BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream collected = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private boolean done;

        CappedBody(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (done) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                if (collected.size() + buffer.remaining() > limit) {
                    done = true;
                    subscription.cancel();
                    result.completeExceptionally(new BodyTooLarge());
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                collected.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            done = true;
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            if (!done) {
                done = true;
                result.complete(collected.toByteArray());
            }
        }
    }
}
