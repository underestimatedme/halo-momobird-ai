package ai.momobird.halo.halo;

import ai.momobird.halo.SettingsLoader;
import ai.momobird.halo.core.ConnectApi;
import ai.momobird.halo.core.ConnectClient;
import ai.momobird.halo.core.ConnectError;
import ai.momobird.halo.core.OriginCheck;
import ai.momobird.halo.core.Reconciler;
import ai.momobird.halo.core.Redactor;
import ai.momobird.halo.core.Settings;
import ai.momobird.halo.core.SyncReport;
import ai.momobird.halo.core.SyncRules;
import ai.momobird.halo.core.SyncState;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/**
 * The endpoints behind the "工具 → MomoBird" page, mounted by Halo at
 * {@code /apis/api.momobird.ai/v1alpha1/...}. Who may call them is decided by Halo's role
 * templates ({@code extensions/role-templates.yaml}); nothing here checks the caller.
 *
 * <ul>
 *   <li>{@code GET /status}: the plugin's state and, unless {@code ?check=false}, a live look at
 *       MomoBird (project, source, plan, widget, address check).
 *   <li>{@code POST /test}: only the live look; nothing is synced.
 *   <li>{@code POST /sync}, body {@code {"confirmRemovals": boolean}} (optional): a full sync.
 *   <li>{@code POST /retry}: sends the refused and pending posts again.
 * </ul>
 *
 * <p>{@code /sync} and {@code /retry} run on the sync service's own thread, one after the other,
 * and answer with the finished report when it comes within {@code waitLimit} (25 seconds: under the
 * 30 to 60 seconds after which a reverse proxy gives up). A longer run (a big site) is not
 * cancelled: the answer is {@code 202} with {@code accepted: true}, and the page polls
 * {@code GET /status?check=false} until {@code sync.running} is false and then reads
 * {@code sync.last}.
 *
 * <p>Only one sync at a time: while a manual run (or the scheduled full sync) is queued or running, a
 * new {@code /sync} or {@code /retry} is not queued behind it; it answers {@code 202} with
 * {@link #BUSY} and the page keeps polling the run already going.
 *
 * <p>Nothing in a response holds the connection key: every text goes through {@link Redactor}
 * (with the real key and by its {@code mbk_} shape), and the key is never put in a field.
 */
@Component
public class AdminEndpoint implements CustomEndpoint {

    public static final GroupVersion GROUP_VERSION = GroupVersion.parseAPIVersion("api.momobird.ai/v1alpha1");
    public static final Duration WAIT_LIMIT = Duration.ofSeconds(25);
    /** One quick look at MomoBird: the page asks again when the owner clicks. */
    static final Duration CHECK_TIMEOUT = Duration.ofSeconds(10);

    static final String BUSY = "已有同步在进行，请等它完成";

    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}");

    // ------------------------------------------------------------------ response shapes

    record Origin(boolean ok, String siteOrigin, String sourceOrigin, String reason) {
    }

    record Report(boolean ok, String mode, int pushed, int unchanged, int removed, int failed, int planRequired,
                  int heldRemovals, boolean guardTripped, boolean originBlocked, boolean wrongKind,
                  String startedAt, String finishedAt, String error, int local, int remote, int skipped,
                  int wouldRemove, String sourceKind, Origin origin, boolean widgetEnabled, List<String> notes) {
    }

    record Project(String name) {
    }

    record Source(String name, String kind, boolean isHalo, Integer documentCount, Integer readyDocumentCount) {
    }

    record Plan(String status, boolean active, String endsAt, String text) {
    }

    record Widget(boolean available, String reason, String text) {
    }

    /** What MomoBird said just now. {@code checked} is false when no look was taken. */
    record Connection(boolean checked, boolean ok, String error, Project project, Source source, Plan plan,
                      Widget widget, Origin origin, List<String> notes) {
    }

    /** A post that did not go through: {@code refused} (MomoBird said no, {@code code}) or {@code pending}. */
    record Failure(String name, String title, String kind, String code, String message, String at) {
    }

    record Sync(boolean running, boolean guardPending, boolean planRequired, Integer local, Integer remote,
                Report last) {
    }

    record Status(boolean configured, List<String> problems, String baseUrl, String externalUrl,
                  boolean widgetEnabled, boolean podcastEnabled, Connection connection, Sync sync,
                  List<Failure> failures) {
    }

    record TestResult(boolean configured, List<String> problems, Connection connection) {
    }

    /** The answer to {@code /sync} and {@code /retry}. */
    record RunResult(boolean ok, boolean accepted, boolean running, String message, Report report) {
    }

    record Message(boolean ok, String message) {
    }

    record SyncBody(Boolean confirmRemovals) {
    }

    // ------------------------------------------------------------------

    private final SyncService sync;
    private final Supplier<Mono<Settings>> settings;
    private final Function<Settings, ConnectApi> clients;
    private final Supplier<String> externalUrl;
    private final Duration waitLimit;

    /** Manual runs started here that have not finished yet. */
    private final AtomicInteger manualRuns = new AtomicInteger();
    private volatile SyncReport lastManual;

    @Autowired
    public AdminEndpoint(SyncService sync, SettingsLoader settings, HaloPosts posts) {
        this(sync, settings::load, s -> new ConnectClient(s, CHECK_TIMEOUT, List.of()), posts::externalUrl,
            WAIT_LIMIT);
    }

    AdminEndpoint(SyncService sync, Supplier<Mono<Settings>> settings, Function<Settings, ConnectApi> clients,
                  Supplier<String> externalUrl, Duration waitLimit) {
        this.sync = sync;
        this.settings = settings;
        this.clients = clients;
        this.externalUrl = externalUrl;
        this.waitLimit = waitLimit;
    }

    @Override
    public GroupVersion groupVersion() {
        return GROUP_VERSION;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("/status", this::status)
            .POST("/test", this::test)
            .POST("/sync", this::fullSync)
            .POST("/retry", this::retry)
            .build();
    }

    // ================================================================== handlers

    private Mono<ServerResponse> status(ServerRequest request) {
        boolean check = !"false".equalsIgnoreCase(request.queryParam("check").orElse("true"));
        return settings.get().flatMap(s -> {
            List<String> problems = s.validate();
            Mono<Connection> connection = check && problems.isEmpty() ? connection(s) : Mono.just(unchecked());
            return connection.map(c -> new Status(problems.isEmpty(), problems(problems, s), clean(s.baseUrl(), s),
                clean(externalUrl.get(), s), s.widgetEnabled(), s.podcastEnabled(), c, syncView(s), failures(s)));
        }).flatMap(body -> json(HttpStatus.OK, body));
    }

    private Mono<ServerResponse> test(ServerRequest request) {
        return settings.get().flatMap(s -> {
            List<String> problems = s.validate();
            Mono<Connection> connection = problems.isEmpty() ? connection(s) : Mono.just(unchecked());
            return connection.map(c -> new TestResult(problems.isEmpty(), problems(problems, s), c));
        }).flatMap(body -> json(HttpStatus.OK, body));
    }

    private Mono<ServerResponse> fullSync(ServerRequest request) {
        return request.bodyToMono(SyncBody.class)
            .defaultIfEmpty(new SyncBody(false))
            .flatMap(body -> run(() -> sync.fullSync(Boolean.TRUE.equals(body.confirmRemovals()))))
            .onErrorResume(AdminEndpoint::isBadBody, e -> json(HttpStatus.BAD_REQUEST,
                new Message(false, "请求内容不正确：应为 {\"confirmRemovals\": true} 或 {\"confirmRemovals\": false}")));
    }

    private Mono<ServerResponse> retry(ServerRequest request) {
        return run(sync::retryFailures);
    }

    private static boolean isBadBody(Throwable e) {
        return e instanceof ServerWebInputException || e instanceof DecodingException;
    }

    /**
     * Starts the run and waits for it up to {@code waitLimit}. The run itself is never tied to the
     * request: it goes on if the wait ends or the caller goes away, and its report is kept.
     */
    private Mono<ServerResponse> run(Supplier<Mono<SyncReport>> start) {
        // One sync at a time: a second click, or a click during the scheduled run, is not queued.
        if (sync.state().running() || !manualRuns.compareAndSet(0, 1)) {
            return json(HttpStatus.ACCEPTED, new RunResult(true, true, true, BUSY, null));
        }
        Mono<SyncReport> job;
        try {
            job = start.get()
                .doOnNext(report -> lastManual = report)
                .doFinally(signal -> manualRuns.decrementAndGet())
                .cache();
        } catch (RuntimeException e) {
            manualRuns.decrementAndGet();
            return json(HttpStatus.SERVICE_UNAVAILABLE, new Message(false, describe(e, null)));
        }
        job.subscribe(report -> { }, error -> { });
        return settings.get().flatMap(s -> job
                .timeout(waitLimit)
                .map(report -> {
                    Report view = report(report, s);
                    return new RunResult(report.ok(), false, false, report.ok() ? "完成" : view.error(), view);
                })
                .onErrorResume(TimeoutException.class, e -> Mono.just(new RunResult(true, true, true,
                    "同步仍在进行，完成后结果会显示在页面上", null)))
                .onErrorResume(e -> !(e instanceof TimeoutException),
                    e -> Mono.just(new RunResult(false, false, false, describe(e, s), null))))
            .flatMap(result -> json(result.accepted() ? HttpStatus.ACCEPTED
                : result.report() == null ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK, result));
    }

    // ================================================================== building the answers

    private Mono<ServerResponse> json(HttpStatus status, Object body) {
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    private static Connection unchecked() {
        return new Connection(false, false, null, null, null, null, null, null, List.of());
    }

    /** The live look at MomoBird, on a thread that may block. Never fails: a problem is the answer. */
    private Mono<Connection> connection(Settings s) {
        return Mono.fromCallable(() -> clients.apply(s).status())
            .subscribeOn(Schedulers.boundedElastic())
            .map(status -> describeStatus(status, s))
            .onErrorResume(ConnectError.class, e -> Mono.just(failed(clean(e.getMessage(), s))))
            .onErrorResume(e -> Mono.just(failed("检查连接时遇到意外错误：" + describe(e, s))));
    }

    private static Connection failed(String error) {
        return new Connection(true, false, error, null, null, null, null, null, List.of());
    }

    private Connection describeStatus(JsonNode status, Settings s) {
        JsonNode source = status == null ? null : status.get("source");
        if (source == null || !source.isObject()) {
            return failed("MomoBird 返回的状态里没有知识源信息");
        }
        String kind = text(source.get("kind"));
        boolean halo = Reconciler.SOURCE_KIND.equals(kind);
        List<String> notes = new ArrayList<>();
        if (!halo) {
            notes.add("这个知识源的类型是 " + (kind.isEmpty() ? "未知" : clean(kind, s))
                + "，不是 halo：照常推送，但不自动撤下 Halo 里没有的文章");
        }
        OriginCheck origin = SyncRules.origin(externalUrl.get(), text(source.get("site_url")));
        return new Connection(true, true, null,
            new Project(clean(text(status.path("project").get("name")), s)),
            new Source(clean(text(source.get("name")), s), clean(kind, s), halo,
                count(source.get("document_count")), count(source.get("ready_document_count"))),
            plan(status.get("entitlement"), s),
            widget(status.get("widget"), s),
            new Origin(origin.ok(), clean(origin.siteOrigin(), s), clean(origin.sourceOrigin(), s),
                clean(origin.reason(), s)),
            notes);
    }

    private static Plan plan(JsonNode entitlement, Settings s) {
        if (entitlement == null || !entitlement.isObject()) {
            return new Plan("", true, null, "未知");
        }
        String status = clean(text(entitlement.get("status")), s);
        boolean expired = !entitlement.path("active").asBoolean(true) || "expired".equals(status);
        String ends = date(entitlement.get("pro".equals(status) ? "pro_ends_at" : "trial_ends_at"));
        String text;
        if (expired) {
            text = ends == null ? "已到期" : "已到期（" + ends + "）";
        } else {
            String suffix = ends == null ? "" : "，" + ends + " 到期";
            text = "pro".equals(status) ? "专业版" + suffix : "trial".equals(status) ? "试用中" + suffix : "未知";
        }
        return new Plan(status, !expired, ends, text);
    }

    private static Widget widget(JsonNode widget, Settings s) {
        if (widget == null || !widget.isObject()) {
            return new Widget(false, "", "未知");
        }
        boolean available = widget.path("available").asBoolean(false);
        String reason = clean(text(widget.get("reason")), s);
        String text;
        if (available) {
            text = "可用";
        } else {
            text = switch (reason) {
                case "not_published" -> "还没上线（第一次同步完成并建好索引后会自动开启）";
                case "disabled_in_console" -> "已在 MomoBird 控制台关闭";
                case "plan_required" -> "套餐已到期";
                case "origin_not_registered" -> "站点地址还没登记到 MomoBird";
                case "" -> "不可用";
                default -> "不可用（" + reason + "）";
            };
        }
        return new Widget(available, reason, text);
    }

    private Sync syncView(Settings s) {
        SyncState state = sync.state();
        SyncReport full = state.lastFull();
        SyncReport last = latest(full, state.lastIncremental(), lastManual);
        // The counts only mean something when the last full sync got as far as comparing the two sides.
        boolean counted = full != null && (full.error() == null || full.local() > 0 || full.remote() > 0);
        return new Sync(state.running() || manualRuns.get() > 0, state.guardPending(), state.planRequired(),
            counted ? full.local() : null, counted ? full.remote() : null,
            last == null ? null : report(last, s));
    }

    private static SyncReport latest(SyncReport... reports) {
        SyncReport best = null;
        for (SyncReport report : reports) {
            if (report != null && (best == null || !time(report).isBefore(time(best)))) {
                best = report;
            }
        }
        return best;
    }

    private static Instant time(SyncReport report) {
        return report.finishedAt() != null ? report.finishedAt()
            : report.startedAt() != null ? report.startedAt() : Instant.EPOCH;
    }

    private List<Failure> failures(Settings s) {
        SyncState state = sync.state();
        List<Failure> all = new ArrayList<>();
        for (SyncState.Failure f : state.failures()) {
            all.add(new Failure(f.name(), clean(f.title(), s), "refused", clean(f.code(), s), null, iso(f.at())));
        }
        for (SyncState.Pending p : state.pending()) {
            all.add(new Failure(p.name(), clean(p.title(), s), "pending", null, clean(p.error(), s), iso(p.at())));
        }
        return all;
    }

    private static Report report(SyncReport r, Settings s) {
        OriginCheck o = r.origin();
        return new Report(r.ok(), r.mode(), r.pushed(), r.unchanged(), r.removed(), r.failed(), r.planRequired(),
            r.heldRemovals(), r.guardTripped(), r.originBlocked(), r.wrongKind(), iso(r.startedAt()),
            iso(r.finishedAt()), r.error() == null ? null : clean(r.error(), s), r.local(), r.remote(), r.skipped(),
            r.wouldRemove(), r.sourceKind() == null ? null : clean(r.sourceKind(), s),
            o == null ? null : new Origin(o.ok(), clean(o.siteOrigin(), s), clean(o.sourceOrigin(), s),
                clean(o.reason(), s)),
            r.widgetEnabled(), r.notes().stream().map(n -> clean(n, s)).toList());
    }

    private static List<String> problems(List<String> problems, Settings s) {
        return problems.stream().map(p -> clean(p, s)).toList();
    }

    // ================================================================== small helpers

    private static String clean(String text, Settings s) {
        return text == null ? null : Redactor.clean(text, s == null ? null : s.key());
    }

    private static String describe(Throwable e, Settings s) {
        String message = e.getMessage();
        String text = message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
        return Redactor.clean(text, s == null ? null : s.key()).replaceAll("\\s+", " ").strip();
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : "";
    }

    private static Integer count(JsonNode node) {
        return node != null && node.isInt() && node.asInt() >= 0 ? node.asInt() : null;
    }

    private static String date(JsonNode node) {
        String value = text(node);
        return DATE.matcher(value).find() ? value.substring(0, 10) : null;
    }

    private static String iso(Instant at) {
        return at == null ? null : at.toString();
    }
}
