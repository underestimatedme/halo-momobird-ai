package ai.momobird.halo.halo;

import ai.momobird.halo.SettingsLoader;
import ai.momobird.halo.core.AnnotationWatch;
import ai.momobird.halo.core.CategoryTree;
import ai.momobird.halo.core.ConnectApi;
import ai.momobird.halo.core.ConnectClient;
import ai.momobird.halo.core.PostSource;
import ai.momobird.halo.core.PostView;
import ai.momobird.halo.core.Reconciler;
import ai.momobird.halo.core.Redactor;
import ai.momobird.halo.core.Settings;
import ai.momobird.halo.core.SyncReport;
import ai.momobird.halo.core.SyncState;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Runs every sync on one background thread, so an incremental run and a full one never overlap
 * and no Halo request or event thread ever waits on MomoBird.
 *
 * <ul>
 *   <li>{@link #enqueue}: a post changed. Events for one post within {@link #DEBOUNCE} collapse
 *       into one read; the posts that came due together are pushed together, along with the
 *       posts waiting for a retry.
 *   <li>{@link #fullSync}: the reconcile, run {@link #STARTUP_DELAY} after the plugin starts,
 *       every day at {@link #DAILY_AT} server time, and on demand from the admin page.
 *   <li>{@link #retryFailures}: sends the refused and pending posts again.
 *   <li>{@link #postSeen}: a post was updated in a way Halo publishes no event for (its
 *       annotations, see {@link PostWatcher}); it is queued like an event only when the exclude
 *       mark or the restricted-reading switch differ from what the engine last read.
 * </ul>
 *
 * <p>Settings are read again at the start of every run, so a changed key or address applies to
 * the next run without restarting the plugin. Scheduled runs are skipped quietly while the
 * plugin has no usable settings; a manual run reports why.
 *
 * <p>The thread belongs to the plugin: it starts with the plugin's Spring context and is shut
 * down when Halo stops the plugin (which closes that context).
 */
@Component
public class SyncService implements SmartLifecycle, DisposableBean {

    public static final Duration DEBOUNCE = Duration.ofSeconds(2);
    public static final Duration STARTUP_DELAY = Duration.ofMinutes(1);
    public static final LocalTime DAILY_AT = LocalTime.of(4, 0);
    /** A short pause between push batches of a full sync, so a large site does not keep Halo busy. */
    public static final Duration BATCH_PAUSE = Duration.ofMillis(200);

    static final String STOPPED = "MomoBird 插件已停止，同步没有执行";

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    /** Where the work runs: one thread in production, virtual time in tests. */
    public interface Scheduler {

        /** Runs the task after the delay; throws {@link RejectedExecutionException} once shut down. */
        void schedule(Runnable task, Duration delay);

        /** Stops the thread; tasks not started yet are dropped, a running one is interrupted. */
        void shutdown();

        /** A daemon thread of its own, with the plugin's class loader. */
        static Scheduler singleThread(String name) {
            ClassLoader loader = SyncService.class.getClassLoader();
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(true);
                thread.setContextClassLoader(loader);
                return thread;
            });
            return new Scheduler() {
                @Override
                public void schedule(Runnable task, Duration delay) {
                    executor.schedule(task, Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
                }

                @Override
                public void shutdown() {
                    executor.shutdownNow();
                    try {
                        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                            log.warn("[MomoBird] 同步线程 10 秒内没有停下");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
        }
    }

    private final PostSource posts;
    private final Supplier<Settings> settings;
    private final Function<Settings, ConnectApi> clients;
    private final Supplier<Scheduler> schedulerFactory;
    private final Clock clock;
    private final Duration batchPause;
    private final SyncState state = new SyncState();
    /** What the engine last read of each post's sync-relevant annotations. */
    private final AnnotationWatch annotations = new AnnotationWatch();

    /** Post name → when its last event becomes due. */
    private final Map<String, Long> due = new ConcurrentHashMap<>();
    /** Calls from the admin page still waiting for their run. */
    private final Set<CompletableFuture<SyncReport>> waiting = ConcurrentHashMap.newKeySet();

    private final Object lifecycle = new Object();
    private Scheduler scheduler;
    private volatile boolean stopped;
    private boolean started;

    @Autowired
    public SyncService(HaloPosts posts, SettingsLoader settings) {
        this(new HaloPostSource(posts), () -> settings.load().block(HaloPostSource.READ_TIMEOUT),
            ConnectClient::new, () -> Scheduler.singleThread("momobird-sync"), Clock.systemDefaultZone(),
            BATCH_PAUSE);
    }

    SyncService(PostSource posts, Supplier<Settings> settings, Function<Settings, ConnectApi> clients,
                Scheduler scheduler, Clock clock, Duration batchPause) {
        this(posts, settings, clients, () -> scheduler, clock, batchPause);
    }

    private SyncService(PostSource posts, Supplier<Settings> settings, Function<Settings, ConnectApi> clients,
                        Supplier<Scheduler> schedulerFactory, Clock clock, Duration batchPause) {
        // Every post the engine reads (listed or re-read) becomes the baseline for postSeen.
        this.posts = new Recording(posts, annotations);
        this.settings = settings;
        this.clients = clients;
        this.schedulerFactory = schedulerFactory;
        this.clock = clock;
        this.batchPause = batchPause;
    }

    public SyncState state() {
        return state;
    }

    // ================================================================== work

    /**
     * A post changed. Returns at once: the post is read {@link #DEBOUNCE} after its last event,
     * on the sync thread. Safe to call from any thread, including Halo's event threads.
     */
    public void enqueue(String postName) {
        if (stopped || postName == null || postName.isEmpty()) {
            return;
        }
        due.put(postName, clock.millis() + DEBOUNCE.toMillis());
        try {
            scheduler().schedule(this::drainDue, DEBOUNCE);
        } catch (RejectedExecutionException e) {
            // stopping: the full sync at the next start covers it
        }
    }

    /**
     * Halo updated a post (any update, seen by {@link PostWatcher}). Queued like an event only when
     * its exclude mark or restricted-reading switch changed: Halo publishes no post event for an
     * annotation change. Safe to call from any thread; does nothing once the plugin stopped.
     */
    public void postSeen(String postName, Map<String, String> postAnnotations) {
        if (stopped || postName == null || postName.isEmpty()) {
            return;
        }
        if (annotations.changed(postName, postAnnotations)) {
            log.debug("[MomoBird] 文章元数据变化 {}", postName);
            enqueue(postName);
        }
    }

    /** The post no longer exists (its deletion has its own event). */
    public void postGone(String postName) {
        if (!stopped) {
            annotations.forget(postName);
        }
    }

    /** The reconcile. {@code confirmRemovals} takes down what the guard or a foreign source kind held back. */
    public Mono<SyncReport> fullSync(boolean confirmRemovals) {
        return submit(() -> runFull(confirmRemovals, true));
    }

    /** Sends the refused and pending posts again. */
    public Mono<SyncReport> retryFailures() {
        return submit(() -> runPosts(state.retryNames(), SyncReport.RETRY, true));
    }

    private Mono<SyncReport> submit(Supplier<SyncReport> work) {
        CompletableFuture<SyncReport> result = new CompletableFuture<>();
        if (stopped) {
            result.completeExceptionally(new IllegalStateException(STOPPED));
            return Mono.fromFuture(result);
        }
        waiting.add(result);
        try {
            scheduler().schedule(() -> {
                try {
                    result.complete(work.get());
                } catch (Throwable e) {
                    result.completeExceptionally(new IllegalStateException(describe(e)));
                } finally {
                    waiting.remove(result);
                }
            }, Duration.ZERO);
        } catch (RejectedExecutionException e) {
            waiting.remove(result);
            result.completeExceptionally(new IllegalStateException(STOPPED));
        }
        return Mono.fromFuture(result);
    }

    /** On the sync thread: the posts whose last event is at least {@link #DEBOUNCE} old, plus pending retries. */
    private void drainDue() {
        long now = clock.millis();
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Long> entry : due.entrySet()) {
            // remove(key, value) leaves a post alone whose newer event arrived meanwhile
            if (entry.getValue() <= now && due.remove(entry.getKey(), entry.getValue())) {
                names.add(entry.getKey());
            }
        }
        if (names.isEmpty()) {
            return;
        }
        Set<String> all = new LinkedHashSet<>(names);
        all.addAll(state.pendingNames());
        safely("增量同步", () -> runPosts(new ArrayList<>(all), SyncReport.INCREMENTAL, false));
    }

    private SyncReport runFull(boolean confirm, boolean manual) {
        Settings current = usableSettings();
        if (current == null) {
            if (!manual) {
                return null;
            }
            SyncReport refused = SyncReport.refused(SyncReport.FULL, state.configProblem(), clock.instant());
            state.recordFull(refused);
            return refused;
        }
        state.running(true);
        try {
            SyncReport report = reconciler(current).fullSync(confirm);
            logReport(report, current);
            return report;
        } finally {
            state.running(false);
        }
    }

    private SyncReport runPosts(List<String> names, String mode, boolean manual) {
        Settings current = usableSettings();
        if (current == null) {
            return manual ? SyncReport.refused(mode, state.configProblem(), clock.instant()) : null;
        }
        if (names.isEmpty()) {
            return SyncReport.builder().mode(mode).startedAt(clock.instant()).finishedAt(clock.instant()).build();
        }
        SyncReport report = reconciler(current).syncPosts(names, mode);
        logReport(report, current);
        return report;
    }

    /** The settings for this run, read fresh, or null (with the reason in the state) when they cannot be used. */
    private Settings usableSettings() {
        Settings current;
        try {
            current = settings.get();
        } catch (RuntimeException e) {
            state.configProblem("读取插件设置失败：" + describe(e));
            return null;
        }
        if (current == null) {
            state.configProblem("读取插件设置失败");
            return null;
        }
        List<String> problems = current.validate();
        state.configProblem(problems.isEmpty() ? null : problems.get(0));
        return problems.isEmpty() ? current : null;
    }

    private Reconciler reconciler(Settings current) {
        return new Reconciler(clients.apply(current), posts, current, state, clock)
            .withBatchPause(batchPause, Thread::sleep);
    }

    // ================================================================== schedule

    /** Plugin started: a full sync in a minute, then every day at 04:00. */
    @Override
    public void start() {
        synchronized (lifecycle) {
            if (started || stopped) {
                return;
            }
            started = true;
        }
        try {
            scheduler().schedule(() -> safely("启动后的全量同步", () -> runFull(false, false)), STARTUP_DELAY);
            scheduleDaily();
            log.info("[MomoBird] 同步服务已启动：1 分钟后全量同步一次，之后每天 {} 一次", DAILY_AT);
        } catch (RejectedExecutionException e) {
            // stopped meanwhile
        }
    }

    private void scheduleDaily() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime next = now.with(DAILY_AT);
        if (!next.isAfter(now)) {
            next = now.plusDays(1).with(DAILY_AT);
        }
        scheduler().schedule(() -> {
            try {
                safely("每日全量同步", () -> runFull(false, false));
            } finally {
                if (!stopped) {
                    try {
                        scheduleDaily();
                    } catch (RejectedExecutionException e) {
                        // stopping
                    }
                }
            }
        }, Duration.between(now, next));
    }

    /** Plugin stopping: drop queued work, interrupt a running sync, fail waiting calls. */
    @Override
    public void stop() {
        Scheduler current;
        synchronized (lifecycle) {
            if (stopped) {
                return;
            }
            stopped = true;
            current = scheduler;
        }
        due.clear();
        annotations.clear();
        if (current != null) {
            current.shutdown();
        }
        for (CompletableFuture<SyncReport> pending : waiting) {
            pending.completeExceptionally(new IllegalStateException(STOPPED));
        }
        waiting.clear();
    }

    @Override
    public boolean isRunning() {
        synchronized (lifecycle) {
            return started && !stopped;
        }
    }

    @Override
    public void destroy() {
        stop();
    }

    private Scheduler scheduler() {
        synchronized (lifecycle) {
            if (stopped) {
                throw new RejectedExecutionException(STOPPED);
            }
            if (scheduler == null) {
                scheduler = schedulerFactory.get();
            }
            return scheduler;
        }
    }

    // ================================================================== logging

    /** Runs scheduled work so that nothing escapes to kill the thread or reach a log unredacted. */
    private void safely(String what, Supplier<SyncReport> work) {
        try {
            work.get();
        } catch (Throwable e) {
            log.warn("[MomoBird] {}失败：{}", what, describe(e));
        }
    }

    private void logReport(SyncReport report, Settings current) {
        if (report == null) {
            return;
        }
        String summary = "[MomoBird] " + label(report.mode()) + "：推送 " + report.pushed() + " 篇，未变 "
            + report.unchanged() + " 篇，撤下 " + report.removed() + " 篇，失败 " + report.failed() + " 篇";
        if (report.error() == null) {
            log.info(summary);
        } else {
            log.warn(Redactor.clean(summary + "；" + report.error(), current.key()));
        }
        for (String note : report.notes()) {
            log.info(Redactor.clean("[MomoBird] " + note, current.key()));
        }
    }

    private static String label(String mode) {
        if (SyncReport.FULL.equals(mode)) {
            return "全量同步";
        }
        return SyncReport.RETRY.equals(mode) ? "重试失败项" : "增量同步";
    }

    /** A short description of an unexpected error, without the key or anything shaped like one. */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        String text = message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
        // A usable key always has the mbk_ shape, which Redactor removes without being told the key.
        return Redactor.clean(text, null).replaceAll("\\s+", " ").strip();
    }

    // ================================================================== reads

    /** The engine's post source, remembering the annotations of every post it reads. */
    private static final class Recording implements PostSource {

        private final PostSource source;
        private final AnnotationWatch watch;

        Recording(PostSource source, AnnotationWatch watch) {
            this.source = source;
            this.watch = watch;
        }

        @Override
        public Stream<PostView> all() {
            return source.all().peek(view -> watch.record(view.name(), view.annotations()));
        }

        @Override
        public Optional<PostView> get(String name) {
            Optional<PostView> view = source.get(name);
            if (view.isPresent()) {
                watch.record(name, view.get().annotations());
            } else {
                watch.forget(name);
            }
            return view;
        }

        @Override
        public String releaseHtml(String name) {
            return source.releaseHtml(name);
        }

        @Override
        public CategoryTree categoryTree() {
            return source.categoryTree();
        }

        @Override
        public String externalUrl() {
            return source.externalUrl();
        }
    }
}
