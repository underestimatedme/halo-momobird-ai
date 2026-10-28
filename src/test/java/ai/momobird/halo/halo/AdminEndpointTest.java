package ai.momobird.halo.halo;

import static org.assertj.core.api.Assertions.assertThat;

import ai.momobird.halo.core.ConnectError;
import ai.momobird.halo.core.FakeConnect;
import ai.momobird.halo.core.FakePosts;
import ai.momobird.halo.core.PostId;
import ai.momobird.halo.core.Settings;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class AdminEndpointTest {

    static final String KEY = "mbk_HALOLOCALTESTKEYHALOLOCALTESTKEYHALOLOCALTE";

    private FakePosts posts;
    private FakeConnect client;
    private AtomicReference<Settings> settings;
    private SyncService service;
    private ExecutorService executor;
    private WebTestClient web;

    /** Runs every task at once on a thread of its own: the real single-thread behavior, no virtual time. */
    private static final class ThreadScheduler implements SyncService.Scheduler {
        private final ExecutorService executor;

        ThreadScheduler(ExecutorService executor) {
            this.executor = executor;
        }

        @Override
        public void schedule(Runnable task, Duration delay) {
            executor.execute(task);
        }

        @Override
        public void shutdown() {
            executor.shutdownNow();
        }
    }

    @BeforeEach
    void setUp() {
        posts = new FakePosts();
        client = new FakeConnect();
        settings = new AtomicReference<>(Settings.of("https://api.example.com", KEY, true, null, null, null));
        executor = Executors.newSingleThreadExecutor();
        service = new SyncService(posts, settings::get, s -> client, new ThreadScheduler(executor),
            Clock.systemUTC(), Duration.ZERO);
        web = bind(Duration.ofSeconds(10));
    }

    @AfterEach
    void tearDown() {
        service.stop();
    }

    private WebTestClient bind(Duration waitLimit) {
        AdminEndpoint endpoint = new AdminEndpoint(service, () -> Mono.fromSupplier(settings::get),
            s -> client, () -> posts.externalUrl, waitLimit);
        return WebTestClient.bindToRouterFunction(endpoint.endpoint())
            .configureClient().responseTimeout(Duration.ofSeconds(20)).build();
    }

    private String body(WebTestClient.ResponseSpec spec) {
        byte[] bytes = spec.expectBody().returnResult().getResponseBodyContent();
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private WebTestClient.ResponseSpec post(String path, String json) {
        WebTestClient.RequestBodySpec request = web.post().uri(path);
        return json == null ? request.exchange()
            : request.contentType(MediaType.APPLICATION_JSON).bodyValue(json).exchange();
    }

    // ------------------------------------------------------------------ routes

    @Test
    @DisplayName("lives under api.momobird.ai/v1alpha1")
    void groupVersion() {
        AdminEndpoint endpoint = new AdminEndpoint(service, () -> Mono.just(settings.get()), s -> client,
            () -> null, Duration.ofSeconds(1));
        assertThat(endpoint.groupVersion().group()).isEqualTo("api.momobird.ai");
        assertThat(endpoint.groupVersion().version()).isEqualTo("v1alpha1");
    }

    // ------------------------------------------------------------------ status

    @Test
    @DisplayName("status carries every field the page shows and never the key")
    void statusFieldsAndNoKey() {
        posts.addPublished("p1").addPublished("p2");
        client.projectName = "我的博客 " + KEY; // a hostile or careless project name
        post("/sync", "{\"confirmRemovals\":false}").expectStatus().isOk();

        WebTestClient.ResponseSpec spec = web.get().uri("/status").exchange();
        spec.expectStatus().isOk()
            .expectBody()
            .jsonPath("$.configured").isEqualTo(true)
            .jsonPath("$.problems.length()").isEqualTo(0)
            .jsonPath("$.externalUrl").isEqualTo(FakePosts.SITE)
            .jsonPath("$.widgetEnabled").isEqualTo(true)
            .jsonPath("$.connection.checked").isEqualTo(true)
            .jsonPath("$.connection.ok").isEqualTo(true)
            .jsonPath("$.connection.project.name").isEqualTo("我的博客 ***")
            .jsonPath("$.connection.source.name").isEqualTo("Halo 测试来源")
            .jsonPath("$.connection.source.kind").isEqualTo("halo")
            .jsonPath("$.connection.source.documentCount").isEqualTo(2)
            .jsonPath("$.connection.plan.text").isEqualTo("试用中，2026-11-02 到期")
            .jsonPath("$.connection.plan.active").isEqualTo(true)
            .jsonPath("$.connection.widget.available").isEqualTo(true) // the first sync switched it on
            .jsonPath("$.connection.widget.text").isEqualTo("可用")
            .jsonPath("$.connection.origin.ok").isEqualTo(true)
            .jsonPath("$.sync.running").isEqualTo(false)
            .jsonPath("$.sync.local").isEqualTo(2)
            .jsonPath("$.sync.guardPending").isEqualTo(false)
            .jsonPath("$.sync.last.mode").isEqualTo("full")
            .jsonPath("$.sync.last.pushed").isEqualTo(2)
            .jsonPath("$.sync.last.ok").isEqualTo(true)
            .jsonPath("$.sync.last.finishedAt").isNotEmpty()
            .jsonPath("$.failures.length()").isEqualTo(0);
        assertThat(body(web.get().uri("/status").exchange())).doesNotContain(KEY).doesNotContain("mbk_HALO");
    }

    @Test
    @DisplayName("status?check=false does not call MomoBird")
    void statusWithoutCheck() {
        web.get().uri("/status?check=false").exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.configured").isEqualTo(true)
            .jsonPath("$.connection.checked").isEqualTo(false);
        assertThat(client.statusCalls.get()).isZero();
    }

    @Test
    @DisplayName("a refused post shows in the failure list with its code, and retry sends it again")
    void failuresAndRetry() {
        posts.addPublished("p1").addPublished("p2");
        client.rejectIds.put(PostId.of("p1"), "VALIDATION_FAILED");
        post("/sync", "{}").expectStatus().isOk()
            .expectBody().jsonPath("$.report.failed").isEqualTo(1);

        web.get().uri("/status?check=false").exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.failures.length()").isEqualTo(1)
            .jsonPath("$.failures[0].title").isEqualTo("标题 p1")
            .jsonPath("$.failures[0].code").isEqualTo("VALIDATION_FAILED")
            .jsonPath("$.failures[0].kind").isEqualTo("refused")
            .jsonPath("$.failures[0].at").isNotEmpty();

        client.rejectIds.clear();
        post("/retry", null).expectStatus().isOk()
            .expectBody()
            .jsonPath("$.ok").isEqualTo(true)
            .jsonPath("$.report.mode").isEqualTo("retry")
            .jsonPath("$.report.pushed").isEqualTo(1);
        assertThat(client.remote).containsKey(PostId.of("p1"));
        web.get().uri("/status?check=false").exchange().expectBody().jsonPath("$.failures.length()").isEqualTo(0);
    }

    @Test
    @DisplayName("a connection failure is a 200 with an explanation, not a 500")
    void connectionFailure() {
        client.statusFailure = ConnectError.http(401, null);
        web.get().uri("/status").exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.configured").isEqualTo(true)
            .jsonPath("$.connection.checked").isEqualTo(true)
            .jsonPath("$.connection.ok").isEqualTo(false)
            .jsonPath("$.connection.error").isEqualTo("连接密钥无效或已被吊销，请在 MomoBird 控制台重新生成");
    }

    // ------------------------------------------------------------------ not configured

    @Test
    @DisplayName("without a key every endpoint answers 200 with a Chinese explanation")
    void notConfigured() {
        settings.set(Settings.of(null, "", false, null, null, null));

        web.get().uri("/status").exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.configured").isEqualTo(false)
            .jsonPath("$.problems[0]").value(v -> assertThat((String) v).contains("连接密钥"))
            .jsonPath("$.connection.checked").isEqualTo(false);
        post("/test", null).expectStatus().isOk()
            .expectBody()
            .jsonPath("$.configured").isEqualTo(false)
            .jsonPath("$.problems[0]").value(v -> assertThat((String) v).contains("连接密钥"));
        post("/sync", "{\"confirmRemovals\":true}").expectStatus().isOk()
            .expectBody()
            .jsonPath("$.ok").isEqualTo(false)
            .jsonPath("$.report.error").value(v -> assertThat((String) v).contains("连接密钥"));
        post("/retry", null).expectStatus().isOk()
            .expectBody().jsonPath("$.ok").isEqualTo(false);
        assertThat(client.statusCalls.get()).isZero();
    }

    @Test
    @DisplayName("a malformed key is explained without repeating it")
    void malformedKey() {
        settings.set(Settings.of(null, "mbk_with space and 密钥", false, null, null, null));
        String text = body(web.get().uri("/status").exchange());
        assertThat(text).contains("\"configured\":false").doesNotContain("with space").doesNotContain("密钥 and");
    }

    // ------------------------------------------------------------------ test

    @Test
    @DisplayName("test checks the connection and does not sync anything")
    void testConnection() {
        posts.addPublished("p1");
        post("/test", null).expectStatus().isOk()
            .expectBody()
            .jsonPath("$.configured").isEqualTo(true)
            .jsonPath("$.connection.ok").isEqualTo(true)
            .jsonPath("$.connection.project.name").isEqualTo("我的博客")
            .jsonPath("$.connection.source.kind").isEqualTo("halo")
            .jsonPath("$.connection.plan.status").isEqualTo("trial")
            .jsonPath("$.connection.origin.ok").isEqualTo(true);
        assertThat(client.statusCalls.get()).isEqualTo(1);
        assertThat(client.upserts).isEmpty();
        assertThat(client.deletes).isEmpty();
        assertThat(client.cursors).isEmpty();
    }

    @Test
    @DisplayName("test reports an address mismatch and an expired plan")
    void testReportsProblems() {
        client.siteUrl = "https://other.example.com";
        client.planActive = false;
        client.planStatus = "expired";
        client.kind = "typecho";
        client.widgetReason = "plan_required";
        post("/test", null).expectStatus().isOk()
            .expectBody()
            .jsonPath("$.connection.ok").isEqualTo(true)
            .jsonPath("$.connection.origin.ok").isEqualTo(false)
            .jsonPath("$.connection.origin.reason").value(v -> assertThat((String) v).contains("不一致"))
            .jsonPath("$.connection.plan.active").isEqualTo(false)
            .jsonPath("$.connection.plan.text").value(v -> assertThat((String) v).startsWith("已到期"))
            .jsonPath("$.connection.source.kind").isEqualTo("typecho")
            .jsonPath("$.connection.source.isHalo").isEqualTo(false)
            .jsonPath("$.connection.notes[0]").value(v -> assertThat((String) v).contains("typecho"))
            .jsonPath("$.connection.widget.text").isEqualTo("套餐已到期");
    }

    @Test
    @DisplayName("a widget that is not live yet says when it will be")
    void widgetNotPublished() {
        post("/test", null).expectBody()
            .jsonPath("$.connection.widget.available").isEqualTo(false)
            .jsonPath("$.connection.widget.text").isEqualTo("还没上线（第一次同步完成并建好索引后会自动开启）");
    }

    @Test
    @DisplayName("an unset external address is reported with both places to set it")
    void externalUrlUnset() {
        posts.externalUrl = null;
        post("/test", null).expectStatus().isOk()
            .expectBody()
            .jsonPath("$.connection.origin.ok").isEqualTo(false)
            .jsonPath("$.connection.origin.reason").value(v -> assertThat((String) v)
                .contains("外部访问地址").contains("halo.external-url"));
        web.get().uri("/status?check=false").exchange().expectBody().jsonPath("$.externalUrl").isEmpty();
    }

    // ------------------------------------------------------------------ sync

    @Test
    @DisplayName("sync runs the full sync and answers the report")
    void syncRuns() {
        posts.addPublished("p1").addPublished("p2");
        post("/sync", "{\"confirmRemovals\":false}").expectStatus().isOk()
            .expectBody()
            .jsonPath("$.ok").isEqualTo(true)
            .jsonPath("$.accepted").isEqualTo(false)
            .jsonPath("$.report.mode").isEqualTo("full")
            .jsonPath("$.report.pushed").isEqualTo(2)
            .jsonPath("$.report.local").isEqualTo(2)
            .jsonPath("$.report.remote").isEqualTo(0);
        assertThat(client.remote).hasSize(2);
        assertThat(service.state().lastFull()).isNotNull();
    }

    @Test
    @DisplayName("without a body, sync is a plain full sync that holds removals the guard holds")
    void syncGuardAndConfirm() {
        posts.addPublished("p1");
        String[] strangers = new String[12];
        for (int i = 0; i < strangers.length; i++) {
            strangers[i] = "9000000000" + i;
        }
        client.seed(strangers);

        post("/sync", null).expectStatus().isOk()
            .expectBody()
            .jsonPath("$.report.guardTripped").isEqualTo(true)
            .jsonPath("$.report.wouldRemove").isEqualTo(12)
            .jsonPath("$.report.removed").isEqualTo(0);
        web.get().uri("/status?check=false").exchange().expectBody()
            .jsonPath("$.sync.guardPending").isEqualTo(true);
        assertThat(client.deletedIds()).isEmpty();

        post("/sync", "{\"confirmRemovals\":true}").expectStatus().isOk()
            .expectBody()
            .jsonPath("$.report.removed").isEqualTo(12)
            .jsonPath("$.report.guardTripped").isEqualTo(false);
        web.get().uri("/status?check=false").exchange().expectBody()
            .jsonPath("$.sync.guardPending").isEqualTo(false);
        assertThat(client.remote).hasSize(1);
    }

    @Test
    @DisplayName("a body that is not JSON is a 400 with a Chinese message")
    void badBody() {
        post("/sync", "{not json").expectStatus().isBadRequest()
            .expectBody().jsonPath("$.ok").isEqualTo(false)
            .jsonPath("$.message").value(v -> assertThat((String) v).contains("请求"));
        assertThat(client.statusCalls.get()).isZero();
    }

    @Test
    @DisplayName("a sync that outlasts the wait answers 202 and its result shows in status afterwards")
    void slowSyncIsAccepted() throws Exception {
        web = bind(Duration.ofMillis(100));
        posts.addPublished("p1");
        client.upsertDelayMillis = 600;

        post("/sync", "{}").expectStatus().isAccepted()
            .expectBody()
            .jsonPath("$.accepted").isEqualTo(true)
            .jsonPath("$.running").isEqualTo(true)
            .jsonPath("$.report").isEmpty();
        web.get().uri("/status?check=false").exchange().expectBody().jsonPath("$.sync.running").isEqualTo(true);

        long deadline = System.currentTimeMillis() + 10_000;
        String running = "true";
        while ("true".equals(running) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            running = String.valueOf(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body(web.get().uri("/status?check=false").exchange())).path("sync").path("running")
                .asBoolean());
        }
        assertThat(running).isEqualTo("false");
        web.get().uri("/status?check=false").exchange().expectBody()
            .jsonPath("$.sync.last.mode").isEqualTo("full")
            .jsonPath("$.sync.last.pushed").isEqualTo(1);
        assertThat(client.remote).hasSize(1);
    }

    @Test
    @DisplayName("a second sync or retry while one runs is not queued")
    void noSecondRunWhileOneRuns() throws Exception {
        web = bind(Duration.ofMillis(100));
        posts.addPublished("p1");
        client.upsertDelayMillis = 600;

        post("/sync", "{}").expectStatus().isAccepted();
        post("/sync", "{\"confirmRemovals\":true}").expectStatus().isAccepted()
            .expectBody()
            .jsonPath("$.accepted").isEqualTo(true)
            .jsonPath("$.running").isEqualTo(true)
            .jsonPath("$.message").isEqualTo("已有同步在进行，请等它完成");
        post("/retry", null).expectStatus().isAccepted()
            .expectBody().jsonPath("$.message").isEqualTo("已有同步在进行，请等它完成");

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
            && new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body(web.get().uri("/status?check=false").exchange()))
                .path("sync").path("running").asBoolean()) {
            Thread.sleep(50);
        }
        assertThat(client.statusCalls.get()).as("only the first sync ran").isEqualTo(1);
        assertThat(client.upserts).hasSize(1);
        // once it is done, the next one starts again
        client.upsertDelayMillis = 0;
        web = bind(Duration.ofSeconds(10));
        post("/sync", "{}").expectStatus().isOk();
        assertThat(client.statusCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a key pasted into the address field never shows in status")
    void baseUrlIsCleaned() {
        settings.set(Settings.of("https://api.example.com/" + KEY, KEY, false, null, null, null));
        String text = body(web.get().uri("/status?check=false").exchange());
        assertThat(text).doesNotContain(KEY).doesNotContain("mbk_HALO");
    }

    @Test
    @DisplayName("a stopped plugin answers 503, not a hang")
    void stoppedService() {
        service.stop();
        post("/sync", "{}").expectStatus().isEqualTo(503)
            .expectBody().jsonPath("$.ok").isEqualTo(false)
            .jsonPath("$.message").value(v -> assertThat((String) v).contains("已停止"));
    }

    @Test
    @DisplayName("no response carries the key, even when MomoBird echoes it")
    void neverTheKey() {
        posts.addPublished("p1");
        client.upsertFailure = ConnectError.network("boom " + KEY, KEY);
        client.rejectIds.put(PostId.of("p1"), "VALIDATION_FAILED");
        String all = body(post("/sync", "{}")) + body(post("/test", null)) + body(post("/retry", null))
            + body(web.get().uri("/status").exchange());
        assertThat(all).doesNotContain(KEY).doesNotContain("mbk_HALO");
    }
}
