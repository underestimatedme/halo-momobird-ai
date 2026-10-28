package ai.momobird.halo.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WidgetStateTest {

    static final String KEY = "mbk_HALOLOCALTESTKEYHALOLOCALTESTKEYHALOLOCALTE";
    static final String SOURCE = "0192a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b";
    static final Instant T0 = Instant.parse("2026-10-28T08:00:00Z");
    static final String LOADER = "https://momobird.atlaspaces.com/widget/loader.v1.js";

    private final FakeConnect connect = new FakeConnect();
    private final Deque<Runnable> queued = new ArrayDeque<>();
    private final WidgetState state = new WidgetState(settings -> connect, queued::add);
    private final Settings settings = Settings.of(null, KEY, true, "right", true, false);
    private ExecutorService pool;

    WidgetStateTest() {
        connect.sourceId = SOURCE;
        connect.publicId = "pub_site";
        connect.widgetReason = "ok";
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    /** Runs the queued background refresh, as the executor would. */
    private void runQueued() {
        while (!queued.isEmpty()) {
            queued.poll().run();
        }
    }

    private void refreshNow(Instant now) {
        state.refreshIfStale(settings, now);
        runQueued();
    }

    @Test
    void noStateYetMeansNoWidget() {
        assertThat(state.snippet(T0, "right")).isEmpty();
    }

    @Test
    void availableStateRendersTheLoader() {
        refreshNow(T0);
        assertThat(state.snippet(T0, "right")).contains(
            "<script src=\"" + LOADER + "\" data-project=\"pub_site\" data-position=\"right\" defer></script>");
    }

    @Test
    void positionLeft() {
        refreshNow(T0);
        assertThat(state.snippet(T0, "left")).hasValueSatisfying(html ->
            assertThat(html).contains("data-position=\"left\"").doesNotContain("data-position=\"right\""));
        assertThat(state.snippet(T0, "anything else")).hasValueSatisfying(html ->
            assertThat(html).contains("data-position=\"right\""));
    }

    @Test
    void expiredHidesTheWidget() {
        connect.widgetReason = "plan_expired";
        refreshNow(T0);
        assertThat(state.snippet(T0, "right")).isEmpty();
    }

    @Test
    void disabledSourceHidesTheWidget() {
        connect.widgetReason = "source_disabled";
        refreshNow(T0);
        assertThat(state.snippet(T0, "right")).isEmpty();
    }

    @Test
    void unpublishedOrSwitchedOffHidesTheWidget() {
        connect.widgetReason = "not_published";
        refreshNow(T0);
        assertThat(state.snippet(T0, "right")).isEmpty();
        connect.widgetReason = "disabled_by_owner";
        refreshNow(T0.plus(Duration.ofMinutes(11)));
        assertThat(state.snippet(T0.plus(Duration.ofMinutes(11)), "right")).isEmpty();
    }

    @Test
    void anAnswerWithoutAUsablePublicIdHidesTheWidget() {
        for (String bad : new String[] {"", "has space", "a\"b", "<x>", "mbk_leaked"}) {
            WidgetState fresh = new WidgetState(s -> connect, queued::add);
            connect.publicId = bad;
            fresh.refreshIfStale(settings, T0);
            runQueued();
            assertThat(fresh.snippet(T0, "right")).as("public id [%s]", bad).isEmpty();
        }
    }

    @Test
    void aRevokedKeyHidesTheWidgetAtOnce() {
        refreshNow(T0);
        connect.statusFailure = ConnectError.http(401, null);
        refreshNow(T0.plus(Duration.ofMinutes(11)));
        assertThat(state.snippet(T0.plus(Duration.ofMinutes(11)), "right")).isEmpty();
        assertThat(state.sourceId(T0.plus(Duration.ofMinutes(11)))).isEmpty();
    }

    @Test
    void anExpiredPlanAnswerHidesTheWidgetAtOnce() {
        refreshNow(T0);
        connect.statusFailure = ConnectError.http(402, null);
        refreshNow(T0.plus(Duration.ofMinutes(11)));
        assertThat(state.snippet(T0.plus(Duration.ofMinutes(11)), "right")).isEmpty();
    }

    @Test
    void aDisabledSourceAnswerHidesTheWidgetAtOnce() {
        refreshNow(T0);
        connect.statusFailure = ConnectError.http(409, "SOURCE_DISABLED");
        refreshNow(T0.plus(Duration.ofMinutes(11)));
        assertThat(state.snippet(T0.plus(Duration.ofMinutes(11)), "right")).isEmpty();
    }

    @Test
    void networkFailureKeepsTheLastStateUntilItIsADayOld() {
        refreshNow(T0);
        connect.statusFailure = ConnectError.network("timeout", KEY);

        Instant later = T0.plus(Duration.ofHours(23));
        refreshNow(later);
        assertThat(connect.statusCalls.get()).isEqualTo(2);
        assertThat(state.snippet(later, "right")).isPresent();

        Instant tooOld = T0.plus(Duration.ofHours(24)).plusSeconds(1);
        assertThat(state.snippet(tooOld, "right")).isEmpty();
        assertThat(state.sourceId(tooOld)).isEmpty();
    }

    @Test
    void staleStateRefreshesInTheBackgroundOnce() {
        refreshNow(T0);
        assertThat(connect.statusCalls.get()).isEqualTo(1);

        // fresh: nothing is queued
        state.refreshIfStale(settings, T0.plus(Duration.ofMinutes(9)));
        assertThat(queued).isEmpty();

        // stale: many page views queue exactly one refresh, and each still renders the old state
        Instant stale = T0.plus(Duration.ofMinutes(11));
        for (int i = 0; i < 20; i++) {
            state.refreshIfStale(settings, stale);
            assertThat(state.snippet(stale, "right")).isPresent();
        }
        assertThat(queued).hasSize(1);
        assertThat(connect.statusCalls.get()).isEqualTo(1);

        // the refresh finishes with a different answer
        connect.widgetReason = "plan_expired";
        runQueued();
        assertThat(connect.statusCalls.get()).isEqualTo(2);
        assertThat(state.snippet(stale, "right")).isEmpty();

        // and the next stale check runs another one
        state.refreshIfStale(settings, stale.plus(Duration.ofMinutes(11)));
        assertThat(queued).hasSize(1);
    }

    @Test
    void headProcessorNeverWaitsOnTheNetwork() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        ConnectApi blocked = new FakeConnect() {
            @Override
            public com.fasterxml.jackson.databind.JsonNode status() {
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.status();
            }
        };
        pool = Executors.newSingleThreadExecutor();
        WidgetState slow = new WidgetState(s -> blocked, pool);

        long begin = System.nanoTime();
        slow.refreshIfStale(settings, T0);
        assertThat(slow.snippet(T0, "right")).isEmpty();
        slow.refreshIfStale(settings, T0.plusSeconds(1));
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(tookMillis).isLessThan(2000);
        release.countDown();
    }

    @Test
    void aChangedKeyDropsTheOldState() {
        refreshNow(T0);
        Settings other = Settings.of(null, "mbk_OTHERKEYOTHERKEYOTHERKEY", true, "right", true, false);
        state.refreshIfStale(other, T0.plusSeconds(5));
        assertThat(state.snippet(T0.plusSeconds(5), "right")).isEmpty();
        assertThat(queued).hasSize(1);
    }

    @Test
    void settingsThatCannotConnectClearTheStateAndCallNothing() {
        refreshNow(T0);
        Settings noKey = Settings.of(null, "", true, "right", true, false);
        state.refreshIfStale(noKey, T0.plusSeconds(1));
        assertThat(queued).isEmpty();
        assertThat(state.snippet(T0.plusSeconds(1), "right")).isEmpty();
    }

    @Test
    void nothingToShowMeansNoCalls() {
        Settings off = Settings.of(null, KEY, false, "right", false, false);
        state.refreshIfStale(off, T0);
        assertThat(queued).isEmpty();
        assertThat(connect.statusCalls.get()).isZero();
    }

    @Test
    void sourceIdComesFromTheSameState() {
        assertThat(state.sourceId(T0)).isEmpty();
        refreshNow(T0);
        assertThat(state.sourceId(T0)).contains(SOURCE);
    }

    @Test
    void withoutStateARetryComesAfterAMinuteNotTen() {
        connect.statusFailure = ConnectError.network("down", KEY);
        refreshNow(T0);
        state.refreshIfStale(settings, T0.plusSeconds(30));
        assertThat(queued).isEmpty();
        state.refreshIfStale(settings, T0.plusSeconds(61));
        assertThat(queued).hasSize(1);
    }
}
