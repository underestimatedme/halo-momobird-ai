package ai.momobird.halo.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReconcilerTest {

    static final String KEY = "mbk_HALOLOCALTESTKEYHALOLOCALTESTKEYHALOLOCALTE";
    static final Instant NOW = Instant.parse("2026-10-28T08:00:00Z");

    private FakePosts posts;
    private FakeConnect client;
    private SyncState state;
    private Clock clock;

    @BeforeEach
    void setUp() {
        posts = new FakePosts();
        client = new FakeConnect();
        state = new SyncState();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static Settings settings(boolean widget) {
        return Settings.of("https://api.example.com", KEY, widget, null, null, null);
    }

    private Reconciler reconciler(boolean widget) {
        return new Reconciler(client, posts, settings(widget), state, clock);
    }

    private SyncReport full(boolean confirm) {
        return reconciler(false).fullSync(confirm);
    }

    private void addPublished(int count, String prefix) {
        for (int i = 0; i < count; i++) {
            posts.addPublished(String.format("%s%03d", prefix, i));
        }
    }

    private List<String> staleIds(int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(String.valueOf(9_000_000_000_000L + i));
        }
        client.seed(ids.toArray(new String[0]));
        return ids;
    }

    // ------------------------------------------------------------------ prune guard

    @Test
    @DisplayName("no local posts trips the guard")
    void noLocalPostsTripsTheGuard() {
        staleIds(3);

        SyncReport report = full(false);

        assertThat(report.guardTripped()).isTrue();
        assertThat(report.wouldRemove()).isEqualTo(3);
        assertThat(report.removed()).isZero();
        assertThat(client.deletes).isEmpty();
        assertThat(client.remote).hasSize(3);
        assertThat(report.error()).isNull();
        assertThat(report.notes()).anySatisfy(note -> assertThat(note).contains("清理保护").contains("确认撤下"));
        assertThat(state.guardPending()).isTrue();
    }

    @Test
    @DisplayName("no local posts trips the guard even when only drafts and private posts exist")
    void onlyUnsyncablePostsTripTheGuard() {
        posts.add(FakePosts.published("draft").toBuilder().publish(false).phase("DRAFT").build());
        posts.add(FakePosts.published("private").toBuilder().visible("PRIVATE").build());
        staleIds(1);

        SyncReport report = full(false);

        assertThat(report.guardTripped()).isTrue();
        assertThat(client.deletes).isEmpty();
        assertThat(client.upserts).isEmpty();
    }

    @Test
    @DisplayName("many removals trip the guard")
    void manyRemovalsTripTheGuard() {
        addPublished(5, "p");
        posts.posts.keySet().forEach(client::seedPosts);
        staleIds(11); // 11 > 10 and 11 > 16 / 2

        SyncReport report = full(false);

        assertThat(report.guardTripped()).isTrue();
        assertThat(report.wouldRemove()).isEqualTo(11);
        assertThat(client.deletes).isEmpty();
        // pushing is not held back by the guard
        assertThat(report.pushed() + report.unchanged()).isEqualTo(5);
        assertThat(report.error()).isNull();
        assertThat(state.guardPending()).isTrue();
    }

    @Test
    @DisplayName("removals at or under the thresholds go out without confirmation")
    void removalsUnderTheThresholdsGoOut() {
        // ten removals: not more than 10
        addPublished(5, "p");
        posts.posts.keySet().forEach(client::seedPosts);
        staleIds(10);
        SyncReport ten = full(false);
        assertThat(ten.guardTripped()).isFalse();
        assertThat(ten.removed()).isEqualTo(10);

        // eleven removals out of 30: not more than half
        setUp();
        addPublished(19, "q");
        posts.posts.keySet().forEach(client::seedPosts);
        staleIds(11);
        SyncReport eleven = full(false);
        assertThat(eleven.guardTripped()).isFalse();
        assertThat(eleven.removed()).isEqualTo(11);
        assertThat(state.guardPending()).isFalse();

        // eleven removals out of 22: exactly half is not more than half
        setUp();
        addPublished(11, "r");
        posts.posts.keySet().forEach(client::seedPosts);
        staleIds(11);
        SyncReport half = full(false);
        assertThat(half.guardTripped()).isFalse();
        assertThat(half.removed()).isEqualTo(11);

        // twelve out of 23: more than half
        setUp();
        addPublished(11, "s");
        posts.posts.keySet().forEach(client::seedPosts);
        staleIds(12);
        assertThat(full(false).guardTripped()).isTrue();
    }

    @Test
    @DisplayName("confirmed sync removes")
    void confirmedSyncRemoves() {
        addPublished(5, "p");
        posts.posts.keySet().forEach(client::seedPosts);
        List<String> stale = staleIds(11);
        full(false);
        assertThat(state.guardPending()).isTrue();

        SyncReport report = full(true);

        assertThat(report.guardTripped()).isFalse();
        assertThat(report.removed()).isEqualTo(11);
        assertThat(client.deletedIds()).containsExactlyElementsOf(stale);
        assertThat(client.remote.keySet()).doesNotContainAnyElementsOf(stale).hasSize(5);
        assertThat(state.guardPending()).isFalse();

        // an empty site is emptied only when confirmed
        setUp();
        staleIds(3);
        assertThat(full(true).removed()).isEqualTo(3);
        assertThat(client.remote).isEmpty();
    }

    // ------------------------------------------------------------------ 402

    @Test
    @DisplayName("402 still removes")
    void planRequiredStillRemoves() {
        addPublished(30, "p");
        posts.posts.keySet().forEach(client::seedPosts);
        List<String> stale = staleIds(3);
        client.planExpired = true;

        SyncReport report = reconciler(true).fullSync(false);

        assertThat(report.planRequired()).isEqualTo(30);
        assertThat(report.pushed()).isZero();
        // pushing stops at the first refusal
        assertThat(client.upserts).hasSize(1);
        assertThat(report.removed()).isEqualTo(3);
        assertThat(client.deletedIds()).containsExactlyElementsOf(stale);
        assertThat(report.error()).contains("套餐");
        assertThat(report.ok()).isFalse();
        assertThat(client.enableCalls).hasValue(0);
        assertThat(state.planRequired()).isTrue();
        // the posts not pushed are not failures: they are pushed after renewal
        assertThat(state.failures()).isEmpty();
    }

    @Test
    @DisplayName("402 keeps the prune guard")
    void planRequiredKeepsTheGuard() {
        addPublished(2, "p");
        posts.posts.keySet().forEach(client::seedPosts);
        staleIds(11);
        client.planExpired = true;

        SyncReport report = full(false);

        assertThat(report.planRequired()).isEqualTo(2);
        assertThat(report.guardTripped()).isTrue();
        assertThat(client.deletes).isEmpty();
    }

    // ------------------------------------------------------------------ source kind and origin

    @Test
    @DisplayName("wrong kind holds removals")
    void wrongKindHoldsRemovals() {
        client.kind = "hexo";
        addPublished(2, "p");
        posts.posts.keySet().forEach(client::seedPosts);
        List<String> stale = staleIds(1);

        SyncReport report = full(false);

        assertThat(report.wrongKind()).isTrue();
        assertThat(report.sourceKind()).isEqualTo("hexo");
        assertThat(report.pushed() + report.unchanged()).isEqualTo(2);
        assertThat(report.heldRemovals()).isEqualTo(1);
        assertThat(report.guardTripped()).isFalse();
        assertThat(report.removed()).isZero();
        assertThat(client.deletes).isEmpty();
        assertThat(report.notes()).anySatisfy(note -> assertThat(note).contains("hexo").contains("不是 halo"));
        assertThat(state.guardPending()).isTrue();

        SyncReport confirmed = full(true);
        assertThat(confirmed.removed()).isEqualTo(1);
        assertThat(client.deletedIds()).containsExactlyElementsOf(stale);
        assertThat(state.guardPending()).isFalse();
    }

    @Test
    @DisplayName("origin mismatch pushes nothing")
    void originMismatchPushesNothing() {
        client.siteUrl = "https://www.blog.example.com";
        addPublished(3, "p");
        staleIds(20);

        SyncReport report = full(true);

        assertThat(report.originBlocked()).isTrue();
        assertThat(report.origin().siteOrigin()).isEqualTo("https://blog.example.com");
        assertThat(report.origin().sourceOrigin()).isEqualTo("https://www.blog.example.com");
        assertThat(report.error()).contains("https://blog.example.com").contains("https://www.blog.example.com");
        assertThat(client.upserts).isEmpty();
        assertThat(client.deletes).isEmpty();
        assertThat(posts.bodiesRead).hasValue(0);

        // an unset external address is a mismatch too, and says where to set it
        setUp();
        posts.externalUrl = null;
        addPublished(1, "p");
        staleIds(1);
        SyncReport unset = full(true);
        assertThat(unset.originBlocked()).isTrue();
        assertThat(unset.origin().siteOrigin()).isNull();
        assertThat(unset.origin().sourceOrigin()).isEqualTo(FakePosts.SITE);
        assertThat(unset.error()).contains("系统设置 → 基本设置 → 外部访问地址").contains("halo.external-url");
        assertThat(client.upserts).isEmpty();
        assertThat(client.deletes).isEmpty();
    }

    // ------------------------------------------------------------------ streaming and batches

    @Test
    @DisplayName("reconcile streams in batches")
    void reconcileStreamsInBatches() {
        addPublished(600, "p");
        List<String> names = new ArrayList<>(posts.posts.keySet());
        names.subList(0, 550).forEach(client::seedPosts);
        List<String> stale = staleIds(150); // 700 remote ids
        int[] sent = {0};
        int[] mostHeld = {0};
        client.onUpsert = batch -> {
            int held = posts.bodiesRead.get() - sent[0];
            mostHeld[0] = Math.max(mostHeld[0], held);
            assertThat(held).isEqualTo(batch.size());
            assertThat(posts.viewsPulled.get() - sent[0]).isLessThanOrEqualTo(Reconciler.PUSH_BATCH_SIZE);
            sent[0] += batch.size();
        };

        SyncReport report = full(false);

        assertThat(report.error()).isNull();
        // remote listing: two pages of up to 500
        assertThat(client.cursors).containsExactly("", "500");
        assertThat(report.remote()).isEqualTo(700);
        assertThat(report.local()).isEqualTo(600);
        // 600 posts in 30 batches of 20, never holding more than one batch of bodies
        assertThat(client.upserts).hasSize(30).allSatisfy(batch -> assertThat(batch).hasSize(20));
        assertThat(mostHeld[0]).isEqualTo(20);
        assertThat(report.pushed()).isEqualTo(600); // 550 updated (seeded without content) + 50 created
        // 150 removals in batches of 100
        assertThat(client.deletes).extracting(List::size).containsExactly(100, 50);
        assertThat(client.deletedIds()).containsExactlyElementsOf(stale);
        assertThat(report.removed()).isEqualTo(150);
        assertThat(report.guardTripped()).isFalse();
    }

    @Test
    @DisplayName("a batch stays under 4 MiB")
    void aBatchStaysUnderFourMiB() throws Exception {
        addPublished(5, "big");
        String body = "<p>" + "字".repeat(1_500_000 / 3 * 1) + "</p>";
        posts.posts.keySet().forEach(name -> posts.html.put(name, body));
        ObjectMapper json = new ObjectMapper();

        SyncReport report = full(false);

        assertThat(report.pushed()).isEqualTo(5);
        assertThat(client.upserts).extracting(List::size).containsExactly(2, 2, 1);
        for (List<PostPayload> batch : client.upserts) {
            assertThat(json.writeValueAsBytes(Map.of("posts", batch)).length)
                .isLessThanOrEqualTo((int) Reconciler.PUSH_BATCH_BYTES);
        }
    }

    @Test
    @DisplayName("a 413 batch is retried one post at a time")
    void aTooLargeBatchIsSentOnePostAtATime() {
        addPublished(3, "p");
        String big = PostId.of("p001");
        client.tooLargeIds.add(big);

        SyncReport report = full(false);

        assertThat(client.upserts).extracting(List::size).containsExactly(3, 1, 1, 1);
        assertThat(report.pushed()).isEqualTo(2);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(state.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.name()).isEqualTo("p001");
            assertThat(failure.code()).isEqualTo("PAYLOAD_TOO_LARGE");
        });
        assertThat(report.error()).isNull();
    }

    @Test
    @DisplayName("a repeated cursor stops the run before anything is sent")
    void aRepeatedCursorStopsTheRun() {
        addPublished(2, "p");
        client.seed("1");
        client.repeatCursor = true;

        SyncReport report = full(true);

        assertThat(report.error()).contains("游标重复");
        assertThat(client.upserts).isEmpty();
        assertThat(client.deletes).isEmpty();
    }

    @Test
    @DisplayName("a network failure keeps the batch for retry and removes nothing")
    void aNetworkFailureRemovesNothing() {
        addPublished(3, "p");
        staleIds(1);
        client.upsertFailure = ConnectError.network("Connection refused", KEY);

        SyncReport report = full(true);

        assertThat(report.error()).contains("连不上 MomoBird");
        assertThat(client.deletes).isEmpty();
        assertThat(state.pending()).extracting(SyncState.Pending::name).containsExactly("p000", "p001", "p002");
        assertThat(state.lastFull()).isSameAs(report);
    }

    @Test
    @DisplayName("a post with nothing to send is skipped and taken down")
    void aPostWithNothingToSendIsSkippedAndTakenDown() {
        addPublished(3, "p");
        posts.html.put("p001", "<script>x()</script>");
        posts.posts.keySet().forEach(client::seedPosts);

        SyncReport report = full(false);

        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.local()).isEqualTo(2);
        assertThat(client.pushedIds()).doesNotContain(PostId.of("p001"));
        assertThat(client.deletedIds()).containsExactly(PostId.of("p001"));
        assertThat(report.removed()).isEqualTo(1);
    }

    @Test
    @DisplayName("a post Halo cannot read is kept, retried and never taken down")
    void anUnreadablePostIsKept() {
        PostSource broken = new FakePosts() {
            @Override
            public String releaseHtml(String name) {
                if (name.equals("p001")) {
                    throw new IllegalStateException("read failed for " + KEY + " and mbk_OTHERSECRET");
                }
                return super.releaseHtml(name);
            }
        };
        FakePosts fake = (FakePosts) broken;
        fake.addPublished("p000").addPublished("p001");
        client.seedPosts("p000", "p001");

        SyncReport report = new Reconciler(client, broken, settings(false), state, clock).fullSync(false);

        assertThat(client.deletes).isEmpty();
        assertThat(report.failed()).isEqualTo(1);
        assertThat(state.pending()).singleElement().satisfies(pending -> {
            assertThat(pending.name()).isEqualTo("p001");
            assertThat(pending.error()).doesNotContain(KEY).doesNotContain("mbk_OTHERSECRET");
        });
    }

    @Test
    @DisplayName("a post the listing skipped is not taken down")
    void aPostTheListingSkippedIsNotTakenDown() {
        // the first listing misses p001, as when a deletion shifts Halo's pages mid-run
        int[] listings = {0};
        FakePosts shifting = new FakePosts() {
            @Override
            public java.util.stream.Stream<PostView> all() {
                listings[0]++;
                return listings[0] == 1 ? super.all().filter(v -> !v.name().equals("p001")) : super.all();
            }
        };
        shifting.addPublished("p000").addPublished("p001").addPublished("p002");
        client.seedPosts("p000", "p001", "p002");

        SyncReport report = new Reconciler(client, shifting, settings(false), state, clock).fullSync(true);

        assertThat(listings[0]).isEqualTo(2);
        assertThat(client.deletes).isEmpty();
        assertThat(client.remote).containsKey(PostId.of("p001"));
        assertThat(report.local()).isEqualTo(3);
    }

    @Test
    @DisplayName("nothing to remove means no second listing")
    void noRemovalsNoSecondListing() {
        int[] listings = {0};
        FakePosts counting = new FakePosts() {
            @Override
            public java.util.stream.Stream<PostView> all() {
                listings[0]++;
                return super.all();
            }
        };
        counting.addPublished("p000");
        new Reconciler(client, counting, settings(false), state, clock).fullSync(false);
        assertThat(listings[0]).isEqualTo(1);
    }

    // ------------------------------------------------------------------ widget

    @Test
    @DisplayName("first successful sync enables the widget only when the setting is on")
    void firstSuccessfulSyncEnablesTheWidgetOnlyWhenTheSettingIsOn() {
        addPublished(2, "p");

        reconciler(false).fullSync(false);
        assertThat(client.enableCalls).hasValue(0);

        SyncReport on = reconciler(true).fullSync(false);
        assertThat(client.enableCalls).hasValue(1);
        assertThat(on.widgetEnabled()).isTrue();

        reconciler(true).fullSync(false);
        assertThat(client.enableCalls).hasValue(1);
    }

    @Test
    @DisplayName("a widget that is not ready yet is tried again on the next sync")
    void aWidgetThatIsNotReadyIsTriedAgain() {
        addPublished(1, "p");
        client.widgetNotReady = true;

        SyncReport first = reconciler(true).fullSync(false);
        assertThat(client.enableCalls).hasValue(1);
        assertThat(first.widgetEnabled()).isFalse();
        assertThat(first.error()).isNull();
        assertThat(state.widgetWaiting()).isTrue();

        client.widgetNotReady = false;
        SyncReport second = reconciler(true).fullSync(false);
        assertThat(client.enableCalls).hasValue(2);
        assertThat(second.widgetEnabled()).isTrue();
        assertThat(state.widgetWaiting()).isFalse();
    }

    @Test
    @DisplayName("a widget the owner turned off, or one already live, is left alone")
    void aWidgetTurnedOffIsLeftAlone() {
        addPublished(1, "p");
        for (String reason : new String[] {"disabled_in_console", "plan_required", "origin_not_registered", "ok"}) {
            client.widgetReason = reason;
            state = new SyncState();
            reconciler(true).fullSync(false);
            assertThat(client.enableCalls).as(reason).hasValue(0);
        }
    }

    @Test
    @DisplayName("a failed run never enables the widget")
    void aFailedRunNeverEnablesTheWidget() {
        addPublished(1, "p");
        client.siteUrl = "https://elsewhere.example.com";
        reconciler(true).fullSync(false);
        assertThat(client.enableCalls).hasValue(0);
    }

    // ------------------------------------------------------------------ incremental

    @Test
    @DisplayName("an incremental take-down is a single post and skips the guard")
    void anIncrementalTakeDownSkipsTheGuard() {
        posts.addPublished("only");
        client.seedPosts("only");
        posts.change("only", view -> view.toBuilder().visible("PRIVATE").build());

        SyncReport report = reconciler(false).syncPosts(List.of("only"));

        assertThat(client.deletedIds()).containsExactly(PostId.of("only"));
        assertThat(report.removed()).isEqualTo(1);
        assertThat(report.guardTripped()).isFalse();
        // no status call is needed to take a post down
        assertThat(client.statusCalls).hasValue(0);
    }

    @Test
    @DisplayName("an incremental push checks the origin first")
    void anIncrementalPushChecksTheOrigin() {
        posts.addPublished("p");
        client.siteUrl = "https://other.example.com";

        SyncReport report = reconciler(false).syncPosts(List.of("p"));

        assertThat(report.originBlocked()).isTrue();
        assertThat(client.upserts).isEmpty();
    }

    @Test
    @DisplayName("errors that reach the state carry no key")
    void errorsCarryNoKey() {
        posts.addPublished("p");
        client.statusFailure = new IllegalStateException("boom " + KEY);

        SyncReport report = full(false);

        assertThat(report.error()).doesNotContain(KEY).doesNotContain("mbk_");
    }
}
