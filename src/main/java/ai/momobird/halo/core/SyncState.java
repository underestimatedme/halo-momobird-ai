package ai.momobird.halo.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the sync engine remembers between runs, in memory only (a restart is covered by the full
 * sync that follows it). The engine writes it from its single background thread; the admin page
 * reads it from request threads, so every method is synchronized and every getter returns a copy.
 *
 * <ul>
 *   <li><b>failures</b>: posts MomoBird refused one by one (title, error code, time). Shown on the
 *       admin page; tried again by the next full sync and by "重试失败项".
 *   <li><b>pending</b>: posts that could not be read or sent this time (network, 5xx, Halo read
 *       errors). Tried again with the next event, the next full sync or "重试失败项".
 *   <li><b>guard pending</b>: the last full sync held removals back (prune guard or a source that
 *       is not {@code halo}); the admin page offers "确认撤下".
 * </ul>
 *
 * <p>All text is cleaned by {@link Redactor} on the way in, even when the caller already did.
 */
public final class SyncState {

    /** A post MomoBird refused. */
    public record Failure(String name, String externalId, String title, String code, Instant at) {
    }

    /** A post to send again. */
    public record Pending(String name, String externalId, String title, String error, Instant at) {
    }

    private final Map<String, Failure> failures = new LinkedHashMap<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private SyncReport lastFull;
    private SyncReport lastIncremental;
    private boolean guardPending;
    private boolean planRequired;
    private boolean running;
    private boolean widgetSettled;
    private boolean widgetWaiting;
    private String configProblem;

    // ------------------------------------------------------------------ posts

    public synchronized void failed(String name, String title, String code, Instant at) {
        pending.remove(name);
        failures.put(name, new Failure(name, PostId.of(name), clean(title), clean(code), at));
    }

    public synchronized void retryLater(String name, String title, String error, Instant at) {
        failures.remove(name);
        pending.put(name, new Pending(name, PostId.of(name), clean(title), clean(error), at));
    }

    /** The post went through, or no longer needs to: it leaves both lists. */
    public synchronized void settled(String name) {
        failures.remove(name);
        pending.remove(name);
    }

    /** Posts taken down by id leave both lists. */
    public synchronized void settledIds(Collection<String> externalIds) {
        Set<String> ids = new java.util.HashSet<>(externalIds);
        failures.values().removeIf(f -> ids.contains(f.externalId()));
        pending.values().removeIf(p -> ids.contains(p.externalId()));
    }

    /** After a complete listing of Halo: posts that no longer exist leave both lists. */
    public synchronized void keepOnly(Set<String> names) {
        failures.keySet().retainAll(names);
        pending.keySet().retainAll(names);
    }

    public synchronized List<Failure> failures() {
        return List.copyOf(failures.values());
    }

    public synchronized List<Pending> pending() {
        return List.copyOf(pending.values());
    }

    public synchronized List<String> pendingNames() {
        return new ArrayList<>(pending.keySet());
    }

    /** Failures, then pending posts: what "重试失败项" sends again. */
    public synchronized List<String> retryNames() {
        Set<String> names = new LinkedHashSet<>(failures.keySet());
        names.addAll(pending.keySet());
        return new ArrayList<>(names);
    }

    // ------------------------------------------------------------------ runs

    public synchronized void recordFull(SyncReport report) {
        lastFull = report;
    }

    public synchronized void recordIncremental(SyncReport report) {
        lastIncremental = report;
    }

    public synchronized SyncReport lastFull() {
        return lastFull;
    }

    public synchronized SyncReport lastIncremental() {
        return lastIncremental;
    }

    public synchronized void guardPending(boolean value) {
        guardPending = value;
    }

    /** The last full sync held removals back; "确认撤下" runs {@code fullSync(true)}. */
    public synchronized boolean guardPending() {
        return guardPending;
    }

    public synchronized void planRequired(boolean value) {
        planRequired = value;
    }

    /** The last push was refused with 402. */
    public synchronized boolean planRequired() {
        return planRequired;
    }

    public synchronized void running(boolean value) {
        running = value;
    }

    /** A full sync is running now. */
    public synchronized boolean running() {
        return running;
    }

    public synchronized void configProblem(String value) {
        configProblem = value == null ? null : clean(value);
    }

    /** Why the plugin cannot sync (settings missing or invalid), or null. */
    public synchronized String configProblem() {
        return configProblem;
    }

    // ------------------------------------------------------------------ widget

    /** The first-sync widget decision has been made: enabled, or left as the owner has it. */
    public synchronized boolean widgetSettled() {
        return widgetSettled;
    }

    public synchronized void widgetSettled(boolean value) {
        widgetSettled = value;
        if (value) {
            widgetWaiting = false;
        }
    }

    /** MomoBird answered WIDGET_NOT_READY; the next full sync tries again. */
    public synchronized boolean widgetWaiting() {
        return widgetWaiting;
    }

    public synchronized void widgetWaiting(boolean value) {
        widgetWaiting = value;
    }

    private static String clean(String text) {
        return text == null ? null : Redactor.clean(text, null);
    }
}
