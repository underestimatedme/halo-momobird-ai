package ai.momobird.halo.halo;

import ai.momobird.halo.SettingsLoader;
import ai.momobird.halo.core.ConnectClient;
import ai.momobird.halo.core.PodcastMarkup;
import ai.momobird.halo.core.PostId;
import ai.momobird.halo.core.PostView;
import ai.momobird.halo.core.Settings;
import ai.momobird.halo.core.SyncRules;
import ai.momobird.halo.core.WidgetState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * What the visitor-facing pages get from the plugin: the widget script for the page head and the
 * podcast player for a post. Both read the cached {@link WidgetState}; asking MomoBird again
 * happens on a thread of its own ({@code momobird-widget}, not the sync thread, which can be busy
 * for minutes), so rendering a page never waits on the network. Nothing here ever fails a page:
 * any error means "show nothing".
 */
@Component
public class SiteWidget implements DisposableBean {

    /** The widget refresh asks once, quickly: the next page view tries again if it fails. */
    static final Duration REFRESH_TIMEOUT = Duration.ofSeconds(10);

    private final Supplier<Mono<Settings>> settings;
    private final WidgetState state;
    private final Clock clock;
    private final ExecutorService ownExecutor;

    @Autowired
    public SiteWidget(SettingsLoader loader) {
        this(loader, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "momobird-widget");
            thread.setDaemon(true);
            thread.setContextClassLoader(SiteWidget.class.getClassLoader());
            return thread;
        }));
    }

    private SiteWidget(SettingsLoader loader, ExecutorService executor) {
        this(loader::load,
            new WidgetState(s -> new ConnectClient(s, REFRESH_TIMEOUT, List.of()), executor),
            Clock.systemUTC(), executor);
    }

    SiteWidget(Supplier<Mono<Settings>> settings, WidgetState state, Clock clock) {
        this(settings, state, clock, null);
    }

    private SiteWidget(Supplier<Mono<Settings>> settings, WidgetState state, Clock clock,
                       ExecutorService ownExecutor) {
        this.settings = settings;
        this.state = state;
        this.clock = clock;
        this.ownExecutor = ownExecutor;
    }

    /** The widget script tag for every page head; empty when it is off or MomoBird says it is unavailable. */
    public Mono<String> headSnippet() {
        return settings.get().flatMap(s -> {
            if (!s.widgetEnabled()) {
                return Mono.<String>empty();
            }
            Instant now = clock.instant();
            state.refreshIfStale(s, now);
            return Mono.justOrEmpty(state.snippet(now, s.widgetPosition()));
        }).onErrorResume(e -> Mono.empty());
    }

    /**
     * The player placeholder and script for the end of a post's content; empty unless the post is
     * one MomoBird holds, the podcast setting is on and the source id is known.
     */
    public Mono<String> podcastHtml(PostView post) {
        return settings.get().flatMap(s -> {
            Instant now = clock.instant();
            if (!s.podcastEnabled() || SyncRules.exclusion(post, now).isPresent()) {
                return Mono.<String>empty();
            }
            state.refreshIfStale(s, now);
            String placeholder = state.sourceId(now)
                .map(source -> PodcastMarkup.placeholder(source, PostId.of(post.name()), s.baseUrl(),
                    s.podcastTranscript()))
                .orElse("");
            return placeholder.isEmpty() ? Mono.<String>empty()
                : Mono.just(placeholder + "\n" + PodcastMarkup.scriptTag());
        }).onErrorResume(e -> Mono.empty());
    }

    @Override
    public void destroy() {
        if (ownExecutor != null) {
            ownExecutor.shutdownNow();
        }
    }
}
