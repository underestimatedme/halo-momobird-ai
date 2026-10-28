package ai.momobird.halo.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * What MomoBird last said about the site's Q&A widget and source, kept in memory so that
 * rendering a page never waits on the network (the same idea as the WordPress plugin's
 * {@code MomoBirdWP_WidgetState}).
 *
 * <ul>
 *   <li>{@link #snippet} and {@link #sourceId} only read the cache.
 *   <li>{@link #refreshIfStale} asks MomoBird again on the given executor when the answer is
 *       older than {@link #TTL} (or there is none yet; then a failed attempt is retried after
 *       {@link #RETRY_WITHOUT_STATE}). At most one refresh is in flight, however many pages are
 *       rendered meanwhile; the executor must not be the sync thread.
 *   <li>The widget is available only when MomoBird says so: project published, widget on, source
 *       not disabled, plan active. A refused or unauthorized answer hides it at once. A network
 *       failure keeps the last answer until it is {@link #MAX_AGE} old, then everything hides.
 * </ul>
 */
public final class WidgetState {

    public static final Duration TTL = Duration.ofMinutes(10);
    public static final Duration MAX_AGE = Duration.ofHours(24);
    public static final Duration RETRY_WITHOUT_STATE = Duration.ofMinutes(1);
    public static final String LOADER_URL = "https://momobird.atlaspaces.com/widget/loader.v1.js";

    private static final Pattern PUBLIC_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    /** The last answer. The fingerprint (address and key) ties it to the settings it came from. */
    private record Snapshot(String fingerprint, String publicId, String sourceId, boolean available,
                            Instant verifiedAt) {
        @Override
        public String toString() {
            return "Snapshot[available=" + available + ", verifiedAt=" + verifiedAt + "]";
        }
    }

    private final Function<Settings, ConnectApi> clients;
    private final Executor executor;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private volatile Snapshot snapshot;
    private volatile Instant lastAttempt;

    public WidgetState(Function<Settings, ConnectApi> clients, Executor executor) {
        this.clients = clients;
        this.executor = executor;
    }

    // ------------------------------------------------------------------ reading

    /** The loader script tag when the widget is available and the answer is not too old. */
    public Optional<String> snippet(Instant now, String position) {
        Snapshot current = fresh(now);
        if (current == null || !current.available() || !validPublicId(current.publicId())) {
            return Optional.empty();
        }
        String side = Settings.LEFT.equals(position) ? Settings.LEFT : Settings.RIGHT;
        return Optional.of("<script src=\"" + LOADER_URL + "\" data-project=\"" + current.publicId()
            + "\" data-position=\"" + side + "\" defer></script>");
    }

    /** The source id MomoBird gave for this site, for the podcast player. Empty when unknown or too old. */
    public Optional<String> sourceId(Instant now) {
        Snapshot current = fresh(now);
        return current == null || current.sourceId().isEmpty() ? Optional.empty() : Optional.of(current.sourceId());
    }

    private Snapshot fresh(Instant now) {
        Snapshot current = snapshot;
        if (current == null || Duration.between(current.verifiedAt(), now).compareTo(MAX_AGE) > 0) {
            return null;
        }
        return current;
    }

    // ------------------------------------------------------------------ refreshing

    /**
     * Starts a background refresh when one is due and none is running. Returns at once. Does
     * nothing when the settings switch both the widget and the podcast off; forgets the answer
     * when the settings cannot connect or point to another account.
     */
    public void refreshIfStale(Settings settings, Instant now) {
        if (!settings.widgetEnabled() && !settings.podcastEnabled()) {
            return;
        }
        if (!settings.validate().isEmpty()) {
            snapshot = null;
            return;
        }
        String fingerprint = settings.baseUrl() + '\n' + settings.key();
        Snapshot current = snapshot;
        if (current != null && !current.fingerprint().equals(fingerprint)) {
            snapshot = null;
            lastAttempt = null;
        }
        if (!due(now)) {
            return;
        }
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }
        lastAttempt = now;
        try {
            executor.execute(() -> refresh(settings, fingerprint, now));
        } catch (RuntimeException e) {
            inFlight.set(false);
        }
    }

    private boolean due(Instant now) {
        Instant attempt = lastAttempt;
        if (attempt == null) {
            return true;
        }
        Duration wait = snapshot == null ? RETRY_WITHOUT_STATE : TTL;
        return Duration.between(attempt, now).compareTo(wait) > 0;
    }

    private void refresh(Settings settings, String fingerprint, Instant now) {
        try {
            JsonNode status = clients.apply(settings).status();
            snapshot = parse(status, fingerprint, now);
        } catch (ConnectError e) {
            switch (e.kind()) {
                case NETWORK, HTTP, TOO_LARGE -> {
                    // no verdict: keep what we knew until it is too old
                }
                default -> snapshot = new Snapshot(fingerprint, "", "", false, now);
            }
        } catch (RuntimeException e) {
            // no verdict either; never let anything escape into the executor
        } finally {
            inFlight.set(false);
        }
    }

    private static Snapshot parse(JsonNode status, String fingerprint, Instant now) {
        JsonNode source = status == null ? null : status.path("source");
        JsonNode widget = status == null ? null : status.path("widget");
        if (source == null || widget == null) {
            return new Snapshot(fingerprint, "", "", false, now);
        }
        String sourceId = text(source.path("id"));
        String publicId = text(widget.path("public_id"));
        boolean available = widget.path("available").asBoolean(false)
            && status.path("entitlement").path("active").asBoolean(true)
            && source.path("enabled").asBoolean(true)
            && !"disabled".equals(text(source.path("status")))
            && validPublicId(publicId);
        return new Snapshot(fingerprint, available ? publicId : "", sourceId, available, now);
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : "";
    }

    /** A public id that cannot break out of an attribute and cannot look like a key. */
    private static boolean validPublicId(String id) {
        return PUBLIC_ID.matcher(id).matches() && Redactor.clean(id, null).equals(id);
    }
}
