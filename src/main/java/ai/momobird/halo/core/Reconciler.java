package ai.momobird.halo.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Decides what MomoBird should hold and makes it so. One instance serves one run: it is built
 * with the settings read for that run and called on the sync engine's single background thread.
 *
 * <p>The full sync mirrors {@code hexo-momobird-ai/lib/reconcile.js}, without its digest cache:
 *
 * <ol>
 *   <li>{@code GET /status}: the source kind and the address check. Different origins (or no
 *       external address in Halo) stop the run before anything is listed, pushed or removed.
 *   <li>The remote listing, every page; a cursor handed back twice stops the run.
 *   <li>Halo's posts, page by page: every post the rules let through is read, mapped and pushed in
 *       batches of at most 20 posts and 4 MiB, so only one batch of bodies is ever held. The
 *       server answers {@code unchanged} for what it already has.
 *   <li>Removals: what MomoBird holds that is not in the local set, 100 per call. The prune guard
 *       holds them all back when the local set is empty, or when they are more than 10 and more
 *       than half of the remote; a source that is not {@code halo} holds them back too. Only a
 *       confirmed run ({@code fullSync(true)}) takes them down then.
 * </ol>
 *
 * <p>Any error before the removals (a failed push, an unreadable listing, a Halo read failing
 * while posts are listed) ends the run with nothing taken down. A 402 is the exception: pushing
 * stops, the posts left are counted in {@code planRequired}, and the removals still go out, guard
 * included, so a post made private does not stay in MomoBird's answers until the plan is renewed.
 *
 * <p>The incremental path ({@link #syncPosts}) re-reads each named post: a post that passes the
 * rules and has something to send is pushed, anything else (gone, trashed, private, unpublished,
 * excluded, nothing to send) is taken down by its id. Single take-downs skip the prune guard.
 */
public final class Reconciler {

    public static final int PUSH_BATCH_SIZE = 20;
    /** The server takes 8 MiB per request; batches are cut at half that. */
    public static final long PUSH_BATCH_BYTES = 4L * 1024 * 1024;
    public static final int DELETE_BATCH_SIZE = 100;
    public static final int PRUNE_GUARD_MIN_REMOVALS = 10;
    public static final String SOURCE_KIND = "halo";

    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ConnectApi client;
    private final PostSource posts;
    private final Settings settings;
    private final SyncState state;
    private final Clock clock;
    private Duration batchPause = Duration.ZERO;
    private ConnectClient.Sleeper sleeper = Thread::sleep;

    public Reconciler(ConnectApi client, PostSource posts, Settings settings, SyncState state, Clock clock) {
        this.client = client;
        this.posts = posts;
        this.settings = settings;
        this.state = state;
        this.clock = clock;
    }

    /** A pause between push batches of a full sync, so a large site does not keep Halo busy. */
    public Reconciler withBatchPause(Duration pause, ConnectClient.Sleeper sleeper) {
        this.batchPause = pause == null ? Duration.ZERO : pause;
        this.sleeper = sleeper;
        return this;
    }

    // ================================================================== full sync

    public SyncReport fullSync(boolean confirmRemovals) {
        Run run = new Run(SyncReport.FULL, clock.instant());
        try {
            full(run, confirmRemovals);
        } catch (Abort e) {
            run.fail(e.getMessage());
        } catch (ConnectError e) {
            run.fail(clean(e.getMessage()));
        } catch (RuntimeException e) {
            run.fail("同步时遇到意外错误：" + describe(e));
        }
        SyncReport report = run.finish(clock.instant());
        state.recordFull(report);
        return report;
    }

    private void full(Run run, boolean confirm) {
        JsonNode status = client.status();
        String external = checkSource(run, status);
        if (external == null) {
            return;
        }

        Set<String> remote = listRemote();
        run.remote = remote.size();

        CategoryTree tree = posts.categoryTree();
        Set<String> local = new HashSet<>();
        Set<String> seen = new HashSet<>();
        Batch batch = new Batch(run, true);
        try (Stream<PostView> all = posts.all()) {
            Iterator<PostView> it = all.iterator();
            while (it.hasNext()) {
                PostView view = it.next();
                String name = view.name();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                seen.add(name);
                if (SyncRules.exclusion(view, clock.instant()).isPresent()) {
                    state.settled(name);
                    continue;
                }
                Optional<PostPayload> payload;
                try {
                    payload = map(view, tree, external);
                } catch (RuntimeException e) {
                    // Hexo's "unreadable": keep it out of the removals and try again later.
                    local.add(PostId.of(name));
                    run.failed++;
                    state.retryLater(name, view.title(), "读取文章内容失败：" + describe(e), clock.instant());
                    continue;
                }
                if (payload.isEmpty()) {
                    run.skipped++;
                    state.settled(name);
                    continue;
                }
                local.add(payload.get().externalId());
                batch.add(new Item(name, payload.get()));
            }
        }
        batch.flush();
        run.local = local.size();
        // The listing went through: failures of posts that are gone go away.
        state.keepOnly(seen);

        if (run.planRequiredCount > 0) {
            state.planRequired(true);
            run.fail("套餐已到期，同步需要专业版或试用：" + run.planRequiredCount + " 篇文章没有推送");
            run.note("套餐已到期，已停止推送，本地已没有的文章照常撤下；续费后下次同步会补上");
        } else {
            state.planRequired(false);
        }
        if (run.skipped > 0) {
            run.note(run.skipped + " 篇文章没有可同步的正文或链接，已跳过");
        }

        List<String> removals = new ArrayList<>();
        for (String id : remote) {
            if (!local.contains(id)) {
                removals.add(id);
            }
        }
        if (!removals.isEmpty()) {
            // Halo lists by offset: a post deleted while the listing ran shifts the pages and can
            // hide another post. A second listing (no bodies) keeps any post the first one missed.
            Set<String> missed = missedByListing(seen);
            if (!missed.isEmpty()) {
                local.addAll(missed);
                run.local = local.size();
                removals.removeIf(missed::contains);
            }
        }
        boolean held = false;
        if (!confirm && run.wrongKind) {
            run.heldRemovals = removals.size();
            if (!removals.isEmpty()) {
                held = true;
                run.note("MomoBird 上有 " + removals.size() + " 篇文章 Halo 里没有，因为知识源不是 halo 类型，本次没有撤下。"
                    + "确认要撤下，请在 MomoBird 页面点「确认撤下」");
            }
            removals = List.of();
        } else if (!confirm && !removals.isEmpty()
            && (local.isEmpty()
                || (removals.size() > PRUNE_GUARD_MIN_REMOVALS && removals.size() * 2L > remote.size()))) {
            run.guardTripped = true;
            run.wouldRemove = removals.size();
            held = true;
            run.note("清理保护：Halo 应同步 " + local.size() + " 篇，MomoBird 上有 " + remote.size() + " 篇，其中 "
                + removals.size() + " 篇 Halo 里已没有，已跳过全部撤下。确认要撤下，请在 MomoBird 页面点「确认撤下」");
            removals = List.of();
        }

        remove(run, removals);
        state.guardPending(held);

        if (run.error == null) {
            enableWidgetOnce(run, status);
        }
    }

    /** Ids of posts the rules let through that the first listing never saw. */
    private Set<String> missedByListing(Set<String> seen) {
        Set<String> missed = new HashSet<>();
        try (Stream<PostView> all = posts.all()) {
            Iterator<PostView> it = all.iterator();
            while (it.hasNext()) {
                PostView view = it.next();
                String name = view.name();
                if (name != null && !name.isEmpty() && !seen.contains(name)
                    && SyncRules.exclusion(view, clock.instant()).isEmpty()) {
                    missed.add(PostId.of(name));
                }
            }
        }
        return missed;
    }

    /**
     * Reads the source part of the status: kind and address check. Returns Halo's external
     * address when the run may go on, or null after recording why it may not.
     */
    private String checkSource(Run run, JsonNode status) {
        JsonNode source = status == null ? null : status.get("source");
        if (source == null || !source.isObject()) {
            throw new Abort("MomoBird 返回的状态里没有知识源信息，本次没有同步");
        }
        JsonNode kindNode = source.get("kind");
        String kind = kindNode != null && kindNode.isTextual() ? clean(kindNode.asText()) : null;
        run.sourceKind = kind;
        run.wrongKind = !SOURCE_KIND.equals(kind);
        if (run.wrongKind) {
            run.note("这个知识源的类型是 " + (kind == null ? "未知" : kind) + "，不是 halo：照常推送，但不自动撤下 Halo 里没有的文章");
        }
        String external = posts.externalUrl();
        JsonNode siteNode = source.get("site_url");
        String site = siteNode != null && siteNode.isTextual() ? siteNode.asText() : null;
        OriginCheck origin = SyncRules.origin(external, site);
        run.origin = new OriginCheck(origin.ok(), clean(origin.siteOrigin()), clean(origin.sourceOrigin()),
            clean(origin.reason()));
        if (!origin.ok()) {
            run.originBlocked = true;
            run.fail(clean(origin.reason()) + "。本次没有推送也没有撤下任何文章");
            return null;
        }
        return external;
    }

    /** Every id MomoBird holds for this source, in listing order. */
    private Set<String> listRemote() {
        Set<String> ids = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        String cursor = null;
        while (true) {
            JsonNode page = client.listPosts(cursor);
            JsonNode items = page == null ? null : page.get("items");
            if (items == null || !items.isArray()) {
                throw new Abort("MomoBird 返回的文章列表格式不正确，本次没有同步");
            }
            for (JsonNode item : items) {
                JsonNode id = item == null ? null : item.get("external_id");
                if (id != null && id.isTextual()) {
                    ids.add(id.asText());
                }
            }
            JsonNode next = page.get("next_cursor");
            if (next == null || next.isNull() || (next.isTextual() && next.asText().isEmpty())) {
                return ids;
            }
            if (!next.isTextual()) {
                throw new Abort("文章列表的分页游标格式不正确，本次没有同步");
            }
            if (!seen.add(next.asText())) {
                throw new Abort("文章列表的分页游标重复，无法读完 MomoBird 上的文章列表，本次没有同步");
            }
            cursor = next.asText();
        }
    }

    /** Takes posts down, 100 per call. Errors propagate: the run fails. */
    private void remove(Run run, List<String> ids) {
        for (int start = 0; start < ids.size(); start += DELETE_BATCH_SIZE) {
            List<String> batch = ids.subList(start, Math.min(ids.size(), start + DELETE_BATCH_SIZE));
            JsonNode response = client.delete(new ArrayList<>(batch));
            Map<String, JsonNode> results = resultsById(response);
            List<String> gone = new ArrayList<>();
            for (String id : batch) {
                JsonNode result = results.get(id);
                String status = result == null ? null : result.path("status").asText(null);
                if ("deleted".equals(status)) {
                    run.removed++;
                    gone.add(id);
                } else if ("not_found".equals(status)) {
                    gone.add(id);
                }
            }
            state.settledIds(gone);
        }
    }

    /**
     * After the first full sync that goes through, switches on the Q&A widget when the settings
     * ask for it and MomoBird says it was never published. Any other state (live, turned off by
     * the owner, plan, address) is left as it is. WIDGET_NOT_READY is tried again next time.
     */
    private void enableWidgetOnce(Run run, JsonNode status) {
        if (!settings.widgetEnabled() || state.widgetSettled()) {
            return;
        }
        JsonNode reasonNode = status.path("widget").path("reason");
        if (!reasonNode.isTextual()) {
            return;
        }
        if (!"not_published".equals(reasonNode.asText())) {
            state.widgetSettled(true);
            return;
        }
        try {
            client.enableWidget();
            state.widgetSettled(true);
            run.widgetEnabled = true;
            run.note("已开启问答助手");
        } catch (ConnectError e) {
            if (e.isWidgetNotReady()) {
                state.widgetWaiting(true);
                run.note("文章还在建索引，问答助手下次同步时再开启");
            } else {
                run.note("开启问答助手失败：" + clean(e.getMessage()) + "，下次同步时再试");
            }
        }
    }

    // ================================================================== incremental

    /** Re-reads the named posts and pushes or takes down each one. For events and retries. */
    public SyncReport syncPosts(Collection<String> names) {
        return syncPosts(names, SyncReport.INCREMENTAL);
    }

    public SyncReport syncPosts(Collection<String> names, String mode) {
        Run run = new Run(mode, clock.instant());
        Set<String> unique = new LinkedHashSet<>();
        for (String name : names) {
            if (name != null && !name.isEmpty()) {
                unique.add(name);
            }
        }
        List<String> takeDown = new ArrayList<>();
        Batch batch = new Batch(run, false);
        CategoryTree tree = null;
        String external = null;
        boolean externalRead = false;

        for (String name : unique) {
            Optional<PostPayload> payload;
            PostView view;
            try {
                view = posts.get(name).orElse(null);
                if (view == null || SyncRules.exclusion(view, clock.instant()).isPresent()) {
                    takeDown.add(name);
                    continue;
                }
                if (tree == null) {
                    tree = posts.categoryTree();
                }
                if (!externalRead) {
                    external = posts.externalUrl();
                    externalRead = true;
                }
                payload = map(view, tree, external);
            } catch (RuntimeException e) {
                run.failed++;
                state.retryLater(name, null, "读取文章失败：" + describe(e), clock.instant());
                continue;
            }
            if (payload.isEmpty()) {
                takeDown.add(name);
                continue;
            }
            batch.add(new Item(name, payload.get()));
        }
        batch.flush();

        if (!takeDown.isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (String name : takeDown) {
                ids.add(PostId.of(name));
            }
            try {
                remove(run, ids);
                takeDown.forEach(state::settled);
            } catch (ConnectError e) {
                run.fail(clean(e.getMessage()));
                for (String name : takeDown) {
                    state.retryLater(name, null, "撤下失败：" + clean(e.getMessage()), clock.instant());
                }
            }
        }

        if (run.planRequiredCount > 0) {
            state.planRequired(true);
            run.fail("套餐已到期，同步需要专业版或试用：" + run.planRequiredCount + " 篇文章没有推送");
        } else if (run.pushed + run.unchanged > 0) {
            state.planRequired(false);
        }
        SyncReport report = run.finish(clock.instant());
        state.recordIncremental(report);
        return report;
    }

    /**
     * The incremental path checks the source once, just before its first push: removals need no
     * status call. Returns false when nothing may be pushed (the reason is on the run).
     */
    private boolean incrementalPushAllowed(Run run) {
        if (run.pushChecked) {
            return run.pushAllowed;
        }
        run.pushChecked = true;
        try {
            run.pushAllowed = checkSource(run, client.status()) != null;
        } catch (Abort e) {
            run.fail(e.getMessage());
            run.pushAllowed = false;
        } catch (ConnectError e) {
            run.fail(clean(e.getMessage()));
            run.pushAllowed = false;
        }
        return run.pushAllowed;
    }

    // ================================================================== pushing

    private Optional<PostPayload> map(PostView view, CategoryTree tree, String external) {
        String html = posts.releaseHtml(view.name());
        String category = tree.topOf(view).orElse(null);
        return PostPayload.of(view, html, category, external);
    }

    private record Item(String name, PostPayload payload) {
    }

    /** A push batch: at most 20 posts and 4 MiB; sent as soon as either limit would be passed. */
    private final class Batch {
        private final Run run;
        private final boolean full;
        private List<Item> items = new ArrayList<>();
        private long bytes;
        private boolean sentOne;
        /** The incremental path stopped pushing (an error): everything after goes to pending. */
        private boolean broken;

        Batch(Run run, boolean full) {
            this.run = run;
            this.full = full;
        }

        void add(Item item) {
            long size = sizeOf(item.payload());
            if (!items.isEmpty() && (items.size() >= PUSH_BATCH_SIZE || bytes + size > PUSH_BATCH_BYTES)) {
                flush();
            }
            items.add(item);
            bytes += size;
            if (items.size() >= PUSH_BATCH_SIZE) {
                flush();
            }
        }

        void flush() {
            if (items.isEmpty()) {
                return;
            }
            List<Item> sending = items;
            items = new ArrayList<>();
            bytes = 0;
            if (full) {
                pauseBetweenBatches();
                sendFull(sending);
            } else {
                sendIncremental(sending);
            }
        }

        private void pauseBetweenBatches() {
            if (sentOne && !batchPause.isZero() && !run.planRequired) {
                try {
                    sleeper.sleep(batchPause.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new Abort("同步被中断");
                }
            }
            sentOne = true;
        }

        private void sendFull(List<Item> sending) {
            try {
                send(run, sending);
            } catch (ConnectError e) {
                if (transientError(e)) {
                    for (Item item : sending) {
                        state.retryLater(item.name(), item.payload().title(), clean(e.getMessage()), clock.instant());
                    }
                }
                throw e;
            }
        }

        private void sendIncremental(List<Item> sending) {
            if (!broken && incrementalPushAllowed(run)) {
                try {
                    send(run, sending);
                    return;
                } catch (ConnectError e) {
                    run.fail(clean(e.getMessage()));
                }
            }
            if (run.originBlocked) {
                // Nothing is pushed to a source at another address; a full sync after the fix covers it.
                return;
            }
            broken = true;
            for (Item item : sending) {
                state.retryLater(item.name(), item.payload().title(),
                    run.error == null ? "等待重试" : run.error, clock.instant());
            }
        }
    }

    /**
     * Sends one batch. A 413 on several posts is retried one post at a time; a 413 on one post
     * marks just that post. A 402 stops pushing for the rest of the run. Other errors propagate.
     */
    private void send(Run run, List<Item> items) {
        if (run.planRequired) {
            run.planRequiredCount += items.size();
            return;
        }
        JsonNode response;
        try {
            response = client.upsert(payloads(items));
        } catch (ConnectError e) {
            if (e.kind() == ConnectError.Kind.PLAN_REQUIRED) {
                run.planRequired = true;
                run.planRequiredCount += items.size();
                return;
            }
            if (e.kind() != ConnectError.Kind.TOO_LARGE) {
                throw e;
            }
            if (items.size() == 1) {
                refuse(run, items.get(0), "PAYLOAD_TOO_LARGE");
                return;
            }
            for (Item item : items) {
                send(run, List.of(item));
            }
            return;
        }
        Map<String, JsonNode> results = resultsById(response);
        for (Item item : items) {
            JsonNode result = results.get(item.payload().externalId());
            String status = result == null ? null : result.path("status").asText(null);
            if ("created".equals(status) || "updated".equals(status)) {
                run.pushed++;
                state.settled(item.name());
            } else if ("unchanged".equals(status)) {
                run.unchanged++;
                state.settled(item.name());
            } else {
                refuse(run, item, rejectionCode(result));
            }
        }
    }

    private void refuse(Run run, Item item, String code) {
        run.failed++;
        state.failed(item.name(), item.payload().title(), code, clock.instant());
    }

    private static List<PostPayload> payloads(List<Item> items) {
        List<PostPayload> list = new ArrayList<>(items.size());
        for (Item item : items) {
            list.add(item.payload());
        }
        return list;
    }

    private static long sizeOf(PostPayload payload) {
        try {
            // +1 for the comma between items of the posts array
            return JSON.writeValueAsBytes(payload).length + 1L;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("文章无法转换为 JSON");
        }
    }

    private static Map<String, JsonNode> resultsById(JsonNode response) {
        Map<String, JsonNode> byId = new HashMap<>();
        JsonNode results = response == null ? null : response.get("results");
        if (results != null && results.isArray()) {
            for (JsonNode result : results) {
                JsonNode id = result == null ? null : result.get("external_id");
                if (id != null && id.isTextual()) {
                    byId.put(id.asText(), result);
                }
            }
        }
        return byId;
    }

    /** The code shown for a refused post: only a short identifier that cannot be a key. */
    private static String rejectionCode(JsonNode result) {
        if (result == null) {
            return "NO_RESULT";
        }
        if (!"rejected".equals(result.path("status").asText(null))) {
            return "UNKNOWN_STATUS";
        }
        JsonNode code = result.get("error_code");
        if (code != null && code.isTextual() && CODE_PATTERN.matcher(code.asText()).matches()
            && !code.asText().contains("mbk_")) {
            return code.asText();
        }
        return "REJECTED";
    }

    private static boolean transientError(ConnectError e) {
        return e.kind() == ConnectError.Kind.NETWORK || e.kind() == ConnectError.Kind.HTTP;
    }

    private String clean(String text) {
        return text == null ? null : Redactor.clean(text, settings.key());
    }

    /** A short, key-free description of an unexpected exception. */
    private String describe(Throwable e) {
        String message = e.getMessage();
        String text = message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
        text = clean(text).replaceAll("\\s+", " ").strip();
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }

    /** Ends a run with a message for the admin page; nothing after it is sent. */
    private static final class Abort extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Abort(String message) {
            super(message, null, false, false);
        }
    }

    /** The counters of one run. */
    private static final class Run {
        final String mode;
        final Instant startedAt;
        int pushed;
        int unchanged;
        int removed;
        int failed;
        int planRequiredCount;
        boolean planRequired;
        int heldRemovals;
        boolean guardTripped;
        boolean originBlocked;
        boolean wrongKind;
        String error;
        int local;
        int remote;
        int skipped;
        int wouldRemove;
        String sourceKind;
        OriginCheck origin;
        boolean widgetEnabled;
        boolean pushChecked;
        boolean pushAllowed;
        final List<String> notes = new ArrayList<>();

        Run(String mode, Instant startedAt) {
            this.mode = mode;
            this.startedAt = startedAt;
        }

        void fail(String message) {
            if (error == null) {
                error = message;
            }
        }

        void note(String text) {
            notes.add(text);
        }

        SyncReport finish(Instant at) {
            return SyncReport.builder()
                .mode(mode)
                .pushed(pushed)
                .unchanged(unchanged)
                .removed(removed)
                .failed(failed)
                .planRequired(planRequiredCount)
                .heldRemovals(heldRemovals)
                .guardTripped(guardTripped)
                .originBlocked(originBlocked)
                .wrongKind(wrongKind)
                .startedAt(startedAt)
                .finishedAt(at)
                .error(error)
                .local(local)
                .remote(remote)
                .skipped(skipped)
                .wouldRemove(wouldRemove)
                .sourceKind(sourceKind)
                .origin(origin)
                .widgetEnabled(widgetEnabled)
                .notes(notes)
                .build();
        }
    }
}
