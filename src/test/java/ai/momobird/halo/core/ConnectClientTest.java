package ai.momobird.halo.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConnectClientTest {

    static final String KEY = RedactorTest.KEY;
    static final String PREFIX = "/momobird/api/public/connect/v1";
    static final ObjectMapper JSON = new ObjectMapper();

    record Seen(String method, String path, String query, String authorization, String accept,
                String contentType, String body) {
    }

    final List<HttpServer> servers = new ArrayList<>();
    final List<ServerSocket> sockets = new ArrayList<>();
    final List<Long> sleeps = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() throws IOException {
        servers.forEach(s -> s.stop(0));
        for (ServerSocket s : sockets) {
            s.close();
        }
    }

    // ------------------------------------------------------------ helpers

    HttpServer serve(HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", handler);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        server.start();
        servers.add(server);
        return server;
    }

    static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static String read(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static Seen see(HttpExchange ex) throws IOException {
        return new Seen(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), ex.getRequestURI().getRawQuery(),
            ex.getRequestHeaders().getFirst("Authorization"), ex.getRequestHeaders().getFirst("Accept"),
            ex.getRequestHeaders().getFirst("Content-Type"), read(ex));
    }

    static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
        ex.close();
    }

    static String ok(String dataJson) {
        return "{\"code\":\"OK\",\"message\":\"ok\",\"data\":" + dataJson + "}";
    }

    static String failure(String code) {
        return "{\"code\":\"" + code + "\",\"message\":\"nope\",\"data\":null}";
    }

    ConnectClient client(String baseUrl) {
        return client(baseUrl, Duration.ofSeconds(5), List.of(Duration.ofSeconds(1), Duration.ofSeconds(3)));
    }

    ConnectClient client(String baseUrl, Duration timeout, List<Duration> delays) {
        return new ConnectClient(Settings.of(baseUrl, KEY, null, null, null, null), timeout, delays,
            ms -> sleeps.add(ms));
    }

    static ConnectError caught(Runnable call) {
        try {
            call.run();
        } catch (ConnectError e) {
            return e;
        }
        throw new AssertionError("expected a ConnectError");
    }

    static void assertNoKey(ConnectError e) throws Exception {
        java.io.StringWriter trace = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(trace));
        for (String view : new String[] {e.getMessage(), e.toString(), trace.toString(), String.valueOf(e.code())}) {
            assertThat(view).doesNotContain(KEY).doesNotContain("mbk_");
        }
        assertThat(e.getCause()).isNull();
    }

    // ------------------------------------------------------------ requests

    @Test
    void statusSendsAuthorizedGetWithoutBody() throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        HttpServer server = serve(ex -> {
            seen.add(see(ex));
            reply(ex, 200, ok("{\"project\":{\"name\":\"博客\"}}"));
        });

        JsonNode data = client(url(server)).status();

        assertThat(data.at("/project/name").asText()).isEqualTo("博客");
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).method()).isEqualTo("GET");
        assertThat(seen.get(0).path()).isEqualTo(PREFIX + "/status");
        assertThat(seen.get(0).authorization()).isEqualTo("Bearer " + KEY);
        assertThat(seen.get(0).accept()).isEqualTo("application/json");
        assertThat(seen.get(0).body()).isEmpty();
    }

    @Test
    void listPostsAsks500PerPageAndEncodesTheCursor() throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        HttpServer server = serve(ex -> {
            seen.add(see(ex));
            reply(ex, 200, ok("{\"items\":[],\"next_cursor\":null}"));
        });
        ConnectClient c = client(url(server));

        c.listPosts(null);
        c.listPosts("");
        c.listPosts("c1");
        c.listPosts("a b&c=d/é");

        assertThat(seen).extracting(Seen::path).containsOnly(PREFIX + "/posts");
        assertThat(seen).extracting(Seen::method).containsOnly("GET");
        assertThat(seen).extracting(Seen::query).containsExactly(
            "limit=500",
            "limit=500",
            "limit=500&cursor=c1",
            "limit=500&cursor=a%20b%26c%3Dd%2F%C3%A9");
    }

    @Test
    void upsertPutsPostsAsJson() throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        HttpServer server = serve(ex -> {
            seen.add(see(ex));
            reply(ex, 200, ok("{\"results\":[{\"external_id\":\"1\",\"status\":\"created\"}]}"));
        });
        List<java.util.Map<String, Object>> posts = List.of(
            java.util.Map.of("external_id", "1", "title", "你好", "content", "正文", "url", "https://x.test/a/"));

        JsonNode data = client(url(server)).upsert(posts);

        assertThat(data.at("/results/0/status").asText()).isEqualTo("created");
        Seen s = seen.get(0);
        assertThat(s.method()).isEqualTo("PUT");
        assertThat(s.path()).isEqualTo(PREFIX + "/posts");
        assertThat(s.contentType()).startsWith("application/json");
        assertThat(s.authorization()).isEqualTo("Bearer " + KEY);
        JsonNode body = JSON.readTree(s.body());
        assertThat(body.get("posts").get(0).get("title").asText()).isEqualTo("你好");
        assertThat(body.get("posts")).hasSize(1);
    }

    @Test
    void deletePostsSendsExternalIds() throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        HttpServer server = serve(ex -> {
            seen.add(see(ex));
            reply(ex, 200, ok("{\"results\":[]}"));
        });

        client(url(server)).delete(List.of("1", "2"));

        Seen s = seen.get(0);
        assertThat(s.method()).isEqualTo("POST");
        assertThat(s.path()).isEqualTo(PREFIX + "/posts/delete");
        assertThat(s.contentType()).startsWith("application/json");
        assertThat(s.body()).isEqualTo("{\"external_ids\":[\"1\",\"2\"]}");
    }

    @Test
    void enableWidgetPostsWithoutBody() throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        HttpServer server = serve(ex -> {
            seen.add(see(ex));
            reply(ex, 200, ok("{\"available\":true,\"public_id\":\"pub_1\",\"reason\":null}"));
        });

        JsonNode data = client(url(server)).enableWidget();

        assertThat(data.get("public_id").asText()).isEqualTo("pub_1");
        assertThat(seen.get(0).method()).isEqualTo("POST");
        assertThat(seen.get(0).path()).isEqualTo(PREFIX + "/widget/enable");
        assertThat(seen.get(0).body()).isEmpty();
    }

    @Test
    void refusesToSendTheKeyOverPlainHttpToARemoteHost() {
        Settings s = Settings.of("http://api.example.com", KEY, null, null, null, null);
        assertThatThrownBy(() -> new ConnectClient(s))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HTTPS")
            .hasMessageNotContaining(KEY);
        Settings noKey = Settings.of("https://api.example.com", "", null, null, null, null);
        assertThatThrownBy(() -> new ConnectClient(noKey)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------- retries

    @Test
    void retriesServerErrorsAfterOneAndThreeSeconds() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = serve(ex -> {
            read(ex);
            if (hits.incrementAndGet() < 3) {
                reply(ex, 503, failure("UNAVAILABLE"));
            } else {
                reply(ex, 200, ok("{\"fine\":true}"));
            }
        });

        JsonNode data = client(url(server)).status();

        assertThat(data.get("fine").asBoolean()).isTrue();
        assertThat(hits.get()).isEqualTo(3);
        assertThat(sleeps).containsExactly(1000L, 3000L);
    }

    @Test
    void givesUpAfterThreeAttemptsOnServerErrors() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = serve(ex -> {
            read(ex);
            hits.incrementAndGet();
            reply(ex, 503, failure("UNAVAILABLE"));
        });

        ConnectError e = caught(() -> client(url(server)).status());

        assertThat(e.kind()).isEqualTo(ConnectError.Kind.HTTP);
        assertThat(e.status()).isEqualTo(503);
        assertThat(e.code()).isEqualTo("UNAVAILABLE");
        assertThat(e.getMessage()).isEqualTo("MomoBird 暂时不可用（HTTP 503）");
        assertThat(hits.get()).isEqualTo(3);
        assertThat(sleeps).containsExactly(1000L, 3000L);
    }

    @Test
    void anHtmlGatewayPageIsRetriedAndHasNoCode() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = serve(ex -> {
            read(ex);
            hits.incrementAndGet();
            reply(ex, 502, "<html>Bad Gateway</html>");
        });

        ConnectError e = caught(() -> client(url(server)).status());

        assertThat(e.kind()).isEqualTo(ConnectError.Kind.HTTP);
        assertThat(e.status()).isEqualTo(502);
        assertThat(e.code()).isNull();
        assertThat(hits.get()).isEqualTo(3);
    }

    @Test
    void networkErrorsAreRetriedThenReportedAsNetwork() throws Exception {
        ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        int port = closed.getLocalPort();
        closed.close();

        ConnectError e = caught(() -> client("http://127.0.0.1:" + port).status());

        assertThat(e.kind()).isEqualTo(ConnectError.Kind.NETWORK);
        assertThat(e.status()).isNull();
        assertThat(e.getMessage()).startsWith("连不上 MomoBird：");
        assertThat(sleeps).containsExactly(1000L, 3000L);
        assertNoKey(e);
    }

    @Test
    void aNetworkErrorFollowedByAGoodAnswerSucceeds() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = serve(ex -> {
            read(ex);
            if (hits.incrementAndGet() == 1) {
                ex.close(); // closes the connection without an answer
            } else {
                reply(ex, 200, ok("{\"again\":true}"));
            }
        });

        assertThat(client(url(server)).status().get("again").asBoolean()).isTrue();
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void theRetryDelaysDecideTheNumberOfAttempts() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = serve(ex -> {
            read(ex);
            hits.incrementAndGet();
            reply(ex, 500, failure("BOOM"));
        });

        caught(() -> client(url(server), Duration.ofSeconds(5), List.of()).status());
        assertThat(hits.get()).isEqualTo(1);
        assertThat(sleeps).isEmpty();

        hits.set(0);
        caught(() -> client(url(server), Duration.ofSeconds(5),
            List.of(Duration.ofMillis(5), Duration.ofMillis(6), Duration.ofMillis(7))).status());
        assertThat(hits.get()).isEqualTo(4);
        assertThat(sleeps).containsExactly(5L, 6L, 7L);
    }

    @Test
    void theDefaultSleeperReallyWaits() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = serve(ex -> {
            read(ex);
            if (hits.incrementAndGet() == 1) {
                reply(ex, 502, failure("BAD_GATEWAY"));
            } else {
                reply(ex, 200, ok("{}"));
            }
        });
        ConnectClient c = new ConnectClient(Settings.of(url(server), KEY, null, null, null, null),
            Duration.ofSeconds(5), List.of(Duration.ofMillis(120)));

        long started = System.nanoTime();
        c.status();

        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isGreaterThanOrEqualTo(100);
        assertThat(hits.get()).isEqualTo(2);
    }

    // ------------------------------------------------------ classification

    @Test
    void clientErrorsAreClassifiedAndNotRetried() throws Exception {
        record Case(int status, String code, ConnectError.Kind kind, String message) {
        }
        List<Case> cases = List.of(
            new Case(401, "UNAUTHORIZED", ConnectError.Kind.UNAUTHORIZED, "连接密钥无效或已被吊销，请在 MomoBird 控制台重新生成"),
            new Case(402, "PLAN_REQUIRED", ConnectError.Kind.PLAN_REQUIRED, "套餐已到期，同步需要专业版或试用"),
            new Case(409, "SOURCE_DISABLED", ConnectError.Kind.DISABLED, "这个知识源已在 MomoBird 控制台停用"),
            new Case(409, "WIDGET_NOT_READY", ConnectError.Kind.REJECTED, "文章还在建索引，问答助手下次同步时再开启"),
            new Case(413, "PAYLOAD_TOO_LARGE", ConnectError.Kind.TOO_LARGE, "一次提交的内容太大，被 MomoBird 拒绝（HTTP 413）"),
            new Case(422, "VALIDATION_FAILED", ConnectError.Kind.REJECTED, "MomoBird 拒绝了这次请求（HTTP 422 VALIDATION_FAILED）"),
            new Case(404, "NOT_FOUND", ConnectError.Kind.REJECTED, "MomoBird 拒绝了这次请求（HTTP 404 NOT_FOUND）"),
            new Case(409, "SOMETHING_ELSE", ConnectError.Kind.REJECTED, "MomoBird 拒绝了这次请求（HTTP 409 SOMETHING_ELSE）"));
        for (Case c : cases) {
            AtomicInteger hits = new AtomicInteger();
            HttpServer server = serve(ex -> {
                read(ex);
                hits.incrementAndGet();
                reply(ex, c.status(), failure(c.code()));
            });

            ConnectError e = caught(() -> client(url(server)).upsert(List.of()));

            assertThat(e.kind()).as(c.toString()).isEqualTo(c.kind());
            assertThat(e.status()).isEqualTo(c.status());
            assertThat(e.code()).isEqualTo(c.code());
            assertThat(e.getMessage()).isEqualTo(c.message());
            assertThat(hits.get()).as("status %d must not be retried", c.status()).isEqualTo(1);
        }
        assertThat(sleeps).isEmpty();
    }

    @Test
    void widgetNotReadyHasAHelper() throws Exception {
        HttpServer server = serve(ex -> {
            read(ex);
            reply(ex, 409, failure("WIDGET_NOT_READY"));
        });
        assertThat(caught(() -> client(url(server)).enableWidget()).isWidgetNotReady()).isTrue();
    }

    @Test
    void aRedirectIsNotFollowedAndTheKeyNeverReachesTheOtherHost() throws Exception {
        List<Seen> other = new CopyOnWriteArrayList<>();
        HttpServer elsewhere = serve(ex -> {
            other.add(see(ex));
            reply(ex, 200, ok("{}"));
        });
        AtomicInteger hits = new AtomicInteger();
        HttpServer origin = serve(ex -> {
            read(ex);
            hits.incrementAndGet();
            ex.getResponseHeaders().set("Location", url(elsewhere) + PREFIX + "/status");
            ex.sendResponseHeaders(307, -1);
            ex.close();
        });

        ConnectError e = caught(() -> client(url(origin)).status());

        assertThat(e.kind()).isEqualTo(ConnectError.Kind.HTTP);
        assertThat(e.status()).isEqualTo(307);
        assertThat(e.getMessage()).isEqualTo("MomoBird 返回错误（HTTP 307）");
        assertThat(hits.get()).isEqualTo(1);
        assertThat(other).isEmpty();
    }

    // -------------------------------------------------- malformed answers

    @Test
    void aTwoHundredWithoutDataIsAnHttpErrorAndIsNotRetried() throws Exception {
        for (String body : List.of("<html>captive portal</html>", "{}", "{\"code\":\"OK\"}",
            "{\"code\":\"OK\",\"data\":null}", "[]", "null", "\"x\"")) {
            AtomicInteger hits = new AtomicInteger();
            HttpServer server = serve(ex -> {
                read(ex);
                hits.incrementAndGet();
                reply(ex, 200, body);
            });

            ConnectError e = caught(() -> client(url(server)).status());

            assertThat(e.kind()).as(body).isEqualTo(ConnectError.Kind.HTTP);
            assertThat(e.status()).isEqualTo(200);
            assertThat(e.getMessage()).isEqualTo("MomoBird 返回错误（HTTP 200）");
            assertThat(hits.get()).isEqualTo(1);
        }
    }

    @Test
    void aFalsyButPresentDataValueIsReturned() throws Exception {
        HttpServer server = serve(ex -> {
            read(ex);
            reply(ex, 200, "{\"code\":\"OK\",\"data\":0}");
        });
        assertThat(client(url(server)).status().asInt(-1)).isZero();
    }

    @Test
    void anErrorCodeThatDoesNotLookLikeACodeIsDropped() throws Exception {
        HttpServer server = serve(ex -> {
            read(ex);
            reply(ex, 422, "{\"code\":\"Bearer " + KEY + "\"}");
        });

        ConnectError e = caught(() -> client(url(server)).status());

        assertThat(e.code()).isNull();
        assertNoKey(e);
    }

    @Test
    void aTruncatedResponseIsAnError() throws Exception {
        ServerSocket socket = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        sockets.add(socket);
        AtomicInteger hits = new AtomicInteger();
        Thread t = new Thread(() -> {
            while (!socket.isClosed()) {
                try (Socket s = socket.accept()) {
                    hits.incrementAndGet();
                    drain(s);
                    OutputStream out = s.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 500\r\n\r\n"
                        + "{\"code\":\"OK\",\"data\":{\"half").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException ignored) {
                    // closed by the test
                }
            }
        });
        t.setDaemon(true);
        t.start();

        ConnectError e = caught(() -> client("http://127.0.0.1:" + socket.getLocalPort()).status());

        assertThat(e.kind()).isEqualTo(ConnectError.Kind.NETWORK);
        assertThat(hits.get()).isEqualTo(3); // a cut-off answer counts as a network error and is retried
    }

    static void drain(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int last4 = 0;
        int b;
        while ((b = in.read()) != -1) {
            head.write(b);
            last4 = (last4 << 8) | b;
            if (last4 == 0x0d0a0d0a) {
                break;
            }
        }
    }

    // ------------------------------------------------- timeouts and limits

    @Test
    void aServerThatNeverAnswersTimesOut() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = serve(ex -> {
            hits.incrementAndGet();
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                // test is over
            }
            ex.close();
        });

        long started = System.nanoTime();
        ConnectError e = caught(() -> client(url(server), Duration.ofMillis(300), List.of(Duration.ofMillis(1))).status());

        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(2500);
        assertThat(e.kind()).isEqualTo(ConnectError.Kind.NETWORK);
        assertThat(e.getMessage()).contains("请求超时");
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void theTimeoutCoversAResponseBodyThatTricklesIn() throws Exception {
        ServerSocket socket = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        sockets.add(socket);
        Thread t = new Thread(() -> {
            while (!socket.isClosed()) {
                try (Socket s = socket.accept()) {
                    drain(s);
                    OutputStream out = s.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    for (int i = 0; i < 100 && !socket.isClosed(); i++) {
                        out.write('x');
                        out.flush();
                        Thread.sleep(100);
                    }
                } catch (IOException | InterruptedException ignored) {
                    // closed by the test
                }
            }
        });
        t.setDaemon(true);
        t.start();

        long started = System.nanoTime();
        ConnectError e = caught(() -> client("http://127.0.0.1:" + socket.getLocalPort(), Duration.ofMillis(400),
            List.of()).status());

        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(3000);
        assertThat(e.kind()).isEqualTo(ConnectError.Kind.NETWORK);
        assertThat(e.getMessage()).contains("请求超时");
    }

    @Test
    void aBodyOverFiveMegabytesIsAborted() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = serve(ex -> {
            read(ex);
            hits.incrementAndGet();
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, 0); // chunked
            try (OutputStream out = ex.getResponseBody()) {
                byte[] chunk = new byte[64 * 1024];
                java.util.Arrays.fill(chunk, (byte) 'a');
                for (int i = 0; i < 200; i++) { // 12.5 MB
                    out.write(chunk);
                }
            } catch (IOException ignored) {
                // the client hung up, as it should
            }
        });

        ConnectError e = caught(() -> client(url(server), Duration.ofSeconds(10), List.of()).status());

        assertThat(e.kind()).isEqualTo(ConnectError.Kind.NETWORK);
        assertThat(e.getMessage()).contains("5 MB");
    }

    @Test
    void aBodyJustUnderTheLimitIsAccepted() throws Exception {
        HttpServer server = serve(ex -> {
            read(ex);
            String pad = "a".repeat(4 * 1024 * 1024);
            reply(ex, 200, ok("{\"pad\":\"" + pad + "\"}"));
        });
        assertThat(client(url(server)).status().get("pad").asText()).hasSize(4 * 1024 * 1024);
    }

    // ------------------------------------------------------- key redaction

    @Test
    void networkErrorsNeverCarryTheKey() {
        ConnectError e = ConnectError.network("request failed: Authorization: Bearer " + KEY + " and mbk_other", KEY);
        assertThat(e.kind()).isEqualTo(ConnectError.Kind.NETWORK);
        assertThat(e.getMessage()).isEqualTo("连不上 MomoBird：request failed: Authorization: Bearer *** and ***");
        assertThat(ConnectError.network("", KEY).getMessage()).isEqualTo("连不上 MomoBird：未知错误");
    }

    @Test
    void longNetworkReasonsAreCut() {
        String message = ConnectError.network("x".repeat(500), KEY).getMessage();
        assertThat(message.length()).isLessThan(230);
        assertThat(message).endsWith("…");
    }

    @Test
    void theKeyNeverShowsUpWhateverTheServerEchoes() throws Exception {
        for (int status : new int[] {400, 401, 402, 409, 413, 500}) {
            HttpServer server = serve(ex -> {
                read(ex);
                reply(ex, status, "{\"code\":\"" + KEY + "\",\"message\":\"" + KEY + "\"}");
            });
            ConnectError e = caught(() -> client(url(server), Duration.ofSeconds(5), List.of()).status());
            assertNoKey(e);
        }
    }
}
