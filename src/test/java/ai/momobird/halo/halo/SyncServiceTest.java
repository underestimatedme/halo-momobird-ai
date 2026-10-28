package ai.momobird.halo.halo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.momobird.halo.core.ConnectError;
import ai.momobird.halo.core.FakeConnect;
import ai.momobird.halo.core.FakePosts;
import ai.momobird.halo.core.PostId;
import ai.momobird.halo.core.Settings;
import ai.momobird.halo.core.SyncReport;
import ai.momobird.halo.core.SyncState;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class SyncServiceTest {

    static final String KEY = "mbk_HALOLOCALTESTKEYHALOLOCALTESTKEYHALOLOCALTE";
    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    // 10:00 in Shanghai
    static final Instant START = ZonedDateTime.of(2026, 10, 28, 10, 0, 0, 0, SHANGHAI).toInstant();

    private FakePosts posts;
    private FakeConnect client;
    private ManualScheduler scheduler;
    private AtomicReference<Settings> settings;
    private SyncService service;

    @BeforeEach
    void setUp() {
        posts = new FakePosts();
        client = new FakeConnect();
        scheduler = new ManualScheduler(START, SHANGHAI);
        settings = new AtomicReference<>(Settings.of("https://api.example.com", KEY, false, null, null, null));
        service = new SyncService(posts, settings::get, s -> client, scheduler, scheduler.clock, Duration.ZERO);
    }

    private SyncReport await(Mono<SyncReport> pending) {
        scheduler.runDue();
        return pending.block(Duration.ofSeconds(5));
    }

    private void event(String name) {
        service.enqueue(name);
        scheduler.advance(SyncService.DEBOUNCE);
    }

    // ------------------------------------------------------------------ incremental

    @Test
    @DisplayName("every way out of public removes the post")
    void everyWayOutOfPublicRemovesThePost() {
        Map<String, Consumer<FakePosts>> ways = new LinkedHashMap<>();
        ways.put("私有", p -> p.change("p1", v -> v.toBuilder().visible("PRIVATE").build()));
        ways.put("内部", p -> p.change("p1", v -> v.toBuilder().visible("INTERNAL").build()));
        ways.put("回收站", p -> p.change("p1", v -> v.toBuilder().deleted(true).build()));
        ways.put("取消发布", p -> p.change("p1", v -> v.toBuilder().publish(false).phase("DRAFT").build()));
        ways.put("排除元数据", p -> p.change("p1",
            v -> v.toBuilder().annotations(Map.of("momobird.ai/exclude", "true")).build()));
        ways.put("删除", p -> p.posts.remove("p1"));

        String id = PostId.of("p1");
        ways.forEach((way, change) -> {
            setUp();
            posts.addPublished("p1");
            event("p1");
            assertThat(client.pushedIds()).as(way).containsExactly(id);
            assertThat(client.remote).as(way).containsKey(id);

            change.accept(posts);
            event("p1");

            assertThat(client.deletedIds()).as(way).containsExactly(id);
            assertThat(client.remote).as(way).doesNotContainKey(id);
            assertThat(client.pushedIds()).as(way).containsExactly(id);
            assertThat(service.state().lastIncremental().removed()).as(way).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("events for one post within 2 seconds collapse")
    void eventsForOnePostWithinTwoSecondsCollapse() {
        posts.addPublished("p1").addPublished("p2");

        service.enqueue("p1");
        scheduler.advance(Duration.ofMillis(1000));
        service.enqueue("p1");
        scheduler.advance(Duration.ofMillis(900));
        service.enqueue("p1");
        service.enqueue("p2");
        // 1.9 s after the first event and nothing has been read yet
        assertThat(posts.gets).hasValue(0);

        scheduler.advance(Duration.ofMillis(1999));
        assertThat(posts.gets).hasValue(0);
        assertThat(client.upserts).isEmpty();

        scheduler.advance(Duration.ofMillis(1));
        // one read per post, both posts in one push
        assertThat(posts.gets).hasValue(2);
        assertThat(client.upserts).singleElement()
            .satisfies(batch -> assertThat(batch).hasSize(2));

        scheduler.advance(Duration.ofSeconds(10));
        assertThat(posts.gets).hasValue(2);
        assertThat(client.upserts).hasSize(1);
    }

    @Test
    @DisplayName("the event listener only enqueues")
    void theListenerOnlyEnqueues() {
        posts.addPublished("p1");
        new PostEventListener(service).onPublished(
            new run.halo.app.event.post.PostPublishedEvent(this, "p1"));
        assertThat(posts.gets).hasValue(0);
        assertThat(client.statusCalls).hasValue(0);
        scheduler.advance(SyncService.DEBOUNCE);
        assertThat(client.pushedIds()).containsExactly(PostId.of("p1"));
    }

    @Test
    @DisplayName("a post that failed to send is retried with the next event")
    void aPendingPostIsRetriedWithTheNextEvent() {
        posts.addPublished("p1").addPublished("p2");
        client.upsertFailure = ConnectError.network("Connection reset", KEY);
        event("p1");
        assertThat(service.state().pending()).extracting(SyncState.Pending::name).containsExactly("p1");

        client.upsertFailure = null;
        event("p2");

        assertThat(client.remote).containsKeys(PostId.of("p1"), PostId.of("p2"));
        assertThat(service.state().pending()).isEmpty();
    }

    @Test
    @DisplayName("events are dropped while the plugin is not configured")
    void eventsAreDroppedWhileNotConfigured() {
        settings.set(Settings.of("https://api.example.com", "", false, null, null, null));
        posts.addPublished("p1");
        event("p1");
        assertThat(posts.gets).hasValue(0);
        assertThat(client.upserts).isEmpty();
        assertThat(service.state().configProblem()).contains("连接密钥");
    }

    // ------------------------------------------------------------------ failures

    @Test
    @DisplayName("rejected post lands in failures and is retried")
    void rejectedPostLandsInFailuresAndIsRetried() {
        posts.addPublished("good").addPublished("bad");
        client.rejectIds.put(PostId.of("bad"), "VALIDATION_FAILED");

        SyncReport first = await(service.fullSync(false));

        assertThat(first.failed()).isEqualTo(1);
        assertThat(service.state().failures()).singleElement().satisfies(failure -> {
            assertThat(failure.name()).isEqualTo("bad");
            assertThat(failure.title()).isEqualTo("标题 bad");
            assertThat(failure.code()).isEqualTo("VALIDATION_FAILED");
            assertThat(failure.at()).isEqualTo(START);
        });

        // still refused: stays in the list
        SyncReport again = await(service.retryFailures());
        assertThat(again.failed()).isEqualTo(1);
        assertThat(client.pushedIds()).containsExactly(PostId.of("good"), PostId.of("bad"), PostId.of("bad"));

        client.rejectIds.clear();
        SyncReport retried = await(service.retryFailures());

        assertThat(retried.pushed()).isEqualTo(1);
        assertThat(service.state().failures()).isEmpty();
        assertThat(client.remote).containsKey(PostId.of("bad"));
    }

    @Test
    @DisplayName("a failure for a post that leaves Halo is forgotten by the next full sync")
    void aFailureForAGonePostIsForgotten() {
        posts.addPublished("bad");
        client.rejectIds.put(PostId.of("bad"), "VALIDATION_FAILED");
        await(service.fullSync(false));
        assertThat(service.state().failures()).hasSize(1);

        posts.posts.remove("bad");
        posts.addPublished("other");
        await(service.fullSync(false));

        assertThat(service.state().failures()).isEmpty();
    }

    // ------------------------------------------------------------------ scheduling and lifecycle

    @Test
    @DisplayName("a full sync runs a minute after start and then every day at 04:00")
    void startupAndDailySchedule() {
        posts.addPublished("p1");
        service.start();

        scheduler.advance(Duration.ofSeconds(59));
        assertThat(client.statusCalls).hasValue(0);
        scheduler.advance(Duration.ofSeconds(1));
        assertThat(client.statusCalls).hasValue(1);
        assertThat(service.state().lastFull()).isNotNull();

        // next 04:00 in Shanghai is 17 h 59 min later
        scheduler.advance(Duration.ofHours(17).plusMinutes(58));
        assertThat(client.statusCalls).hasValue(1);
        scheduler.advance(Duration.ofMinutes(1));
        assertThat(client.statusCalls).hasValue(2);
        assertThat(service.state().lastFull().startedAt())
            .isEqualTo(ZonedDateTime.of(2026, 10, 29, 4, 0, 0, 0, SHANGHAI).toInstant());

        scheduler.advance(Duration.ofDays(1));
        assertThat(client.statusCalls).hasValue(3);
        service.stop();
    }

    @Test
    @DisplayName("the scheduled sync is skipped while the plugin is not configured")
    void scheduledSyncSkippedWithoutSettings() {
        settings.set(Settings.of("https://api.example.com", "", false, null, null, null));
        service.start();
        scheduler.advance(Duration.ofDays(2));
        assertThat(client.statusCalls).hasValue(0);
        assertThat(service.state().lastFull()).isNull();
        service.stop();
    }

    @Test
    @DisplayName("a manual sync without settings reports the problem")
    void aManualSyncWithoutSettingsReportsTheProblem() {
        settings.set(Settings.of("https://api.example.com", "", false, null, null, null));
        SyncReport report = await(service.fullSync(false));
        assertThat(report.error()).contains("连接密钥");
        assertThat(client.statusCalls).hasValue(0);
    }

    @Test
    @DisplayName("settings are read again for every run")
    void settingsAreReadForEveryRun() {
        posts.addPublished("p1");
        java.util.List<Settings> seen = new java.util.ArrayList<>();
        service = new SyncService(posts, settings::get, s -> {
            seen.add(s);
            return client;
        }, scheduler, scheduler.clock, Duration.ZERO);
        await(service.fullSync(false));
        settings.set(Settings.of("https://api2.example.com", KEY, true, null, null, null));
        await(service.fullSync(false));
        assertThat(seen).extracting(Settings::baseUrl)
            .containsExactly("https://api.example.com", "https://api2.example.com");
    }

    @Test
    @DisplayName("stopping fails waiting calls and refuses new work")
    void stoppingFailsWaitingCalls() {
        Mono<SyncReport> waiting = service.fullSync(false);
        service.stop();

        assertThatThrownBy(() -> waiting.block(Duration.ofSeconds(1))).hasMessageContaining("已停止");
        assertThatThrownBy(() -> service.fullSync(false).block(Duration.ofSeconds(1)))
            .hasMessageContaining("已停止");
        service.enqueue("p1"); // ignored, no exception
    }

    @Test
    @DisplayName("full and incremental runs never overlap")
    void fullAndIncrementalNeverOverlap() throws Exception {
        for (int i = 0; i < 60; i++) {
            posts.addPublished("p" + i);
        }
        client.upsertDelayMillis = 20;
        SyncService real = new SyncService(posts, settings::get, s -> client,
            SyncService.Scheduler.singleThread("momobird-sync-test"), java.time.Clock.systemUTC(), Duration.ZERO);
        try {
            Mono<SyncReport> full = real.fullSync(false);
            for (int i = 0; i < 10; i++) {
                real.enqueue("p" + i);
            }
            Mono<SyncReport> second = real.fullSync(true);
            assertThat(full.block(Duration.ofSeconds(10)).error()).isNull();
            assertThat(second.block(Duration.ofSeconds(10)).error()).isNull();
            // let the debounced events run too
            long until = System.currentTimeMillis() + 5000;
            while (real.state().lastIncremental() == null && System.currentTimeMillis() < until) {
                Thread.sleep(50);
            }
            assertThat(real.state().lastIncremental()).isNotNull();
            assertThat(client.maxInFlight).hasValue(1);
            assertThat(client.threads).containsExactly("momobird-sync-test");
        } finally {
            real.stop();
        }
    }

    @Test
    @DisplayName("state reports a running full sync")
    void stateReportsARunningSync() {
        boolean[] seenRunning = {false};
        posts.addPublished("p1");
        client.onUpsert = batch -> seenRunning[0] = service.state().running();
        await(service.fullSync(false));
        assertThat(seenRunning[0]).isTrue();
        assertThat(service.state().running()).isFalse();
    }

    // ------------------------------------------------------------------ annotation changes (no Halo event)

    private static final Map<String, String> EXCLUDED = Map.of("momobird.ai/exclude", "true");

    private void exclude(String name, boolean on) {
        posts.change(name, v -> v.toBuilder().annotations(on ? EXCLUDED : Map.of()).build());
    }

    @Test
    @DisplayName("adding the exclude mark to a synced post takes it down; removing it pushes it again")
    void anExcludeMarkChangeIsSyncedWithoutAFullSync() {
        posts.addPublished("p1");
        event("p1");
        String id = PostId.of("p1");
        assertThat(client.remote).containsKey(id);

        exclude("p1", true);
        service.postSeen("p1", EXCLUDED);
        scheduler.advance(SyncService.DEBOUNCE);
        assertThat(client.deletedIds()).containsExactly(id);
        assertThat(client.remote).doesNotContainKey(id);

        exclude("p1", false);
        service.postSeen("p1", Map.of());
        scheduler.advance(SyncService.DEBOUNCE);
        assertThat(client.remote).containsKey(id);
        assertThat(client.pushedIds()).containsExactly(id, id);
        assertThat(service.state().lastFull()).isNull();
    }

    @Test
    @DisplayName("the full sync is the baseline: removing a mark from a post untouched since start pushes it")
    void theFullSyncSeedsTheBaseline() {
        posts.addPublished("p1");
        exclude("p1", true);
        await(service.fullSync(false));
        String id = PostId.of("p1");
        assertThat(client.remote).doesNotContainKey(id);

        exclude("p1", false);
        service.postSeen("p1", Map.of());
        scheduler.advance(SyncService.DEBOUNCE);

        assertThat(client.remote).containsKey(id);
    }

    @Test
    @DisplayName("updates that do not touch the marks do not reach the engine")
    void otherUpdatesAreNoise() {
        posts.addPublished("p1");
        event("p1");
        int reads = posts.gets.get();

        service.postSeen("p1", Map.of("content.halo.run/last-released-snapshot", "s2"));
        service.postSeen("p2-never-read", Map.of("other", "x"));
        scheduler.advance(Duration.ofSeconds(10));

        assertThat(posts.gets).hasValue(reads);
        assertThat(client.upserts).hasSize(1);
    }

    @Test
    @DisplayName("a mark on a post the engine has not read yet gets a look")
    void aMarkOnAnUnreadPostGetsALook() {
        posts.addPublished("p1");
        exclude("p1", true);

        service.postSeen("p1", EXCLUDED);
        scheduler.advance(SyncService.DEBOUNCE);

        assertThat(client.deletedIds()).containsExactly(PostId.of("p1"));
    }

    @Test
    @DisplayName("after the plugin stopped, annotation changes do nothing")
    void afterStopAnnotationChangesDoNothing() {
        posts.addPublished("p1");
        event("p1");
        int reads = posts.gets.get();
        service.stop();

        exclude("p1", true);
        service.postSeen("p1", EXCLUDED);
        service.postGone("p1");
        scheduler.advance(Duration.ofSeconds(10));

        assertThat(posts.gets).hasValue(reads);
        assertThat(client.deletedIds()).isEmpty();
    }
}
