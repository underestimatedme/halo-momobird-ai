package ai.momobird.halo.core;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

/**
 * The outcome of one sync run, for the admin page and the logs. Every text in it has been through
 * {@link Redactor}.
 *
 * @param mode           {@link #FULL}, {@link #INCREMENTAL} or {@link #RETRY}
 * @param pushed         posts MomoBird created or updated
 * @param unchanged      posts MomoBird already held as sent
 * @param removed        posts taken down
 * @param failed         posts refused one by one (see the failures in {@link SyncState}) or that
 *                       could not be read or sent this time (see the pending retries)
 * @param planRequired   posts not pushed because the plan has run out (402)
 * @param heldRemovals   posts not taken down because the source is not a {@code halo} source
 * @param guardTripped   the prune guard kept every removal back; {@code fullSync(true)} takes them down
 * @param originBlocked  Halo's external address and the source address differ: nothing was sent
 * @param wrongKind      the source's kind is not {@code halo}
 * @param startedAt      when the run started
 * @param finishedAt     when it ended
 * @param error          why the run failed, or null when it went through
 * @param local          posts Halo should have in MomoBird (full runs)
 * @param remote         posts MomoBird held before the run (full runs)
 * @param skipped        posts that pass the rules but have nothing to send (no text, no link)
 * @param wouldRemove    removals the guard held back
 * @param sourceKind     the source kind MomoBird reported, or null when the run did not get that far
 * @param origin         the address check, or null when the run did not get that far
 * @param widgetEnabled  this run switched the Q&A widget on
 * @param notes          sentences for the admin page: guard, wrong kind, plan, widget, skipped posts
 */
@Builder(toBuilder = true)
public record SyncReport(
    String mode,
    int pushed,
    int unchanged,
    int removed,
    int failed,
    int planRequired,
    int heldRemovals,
    boolean guardTripped,
    boolean originBlocked,
    boolean wrongKind,
    Instant startedAt,
    Instant finishedAt,
    String error,
    int local,
    int remote,
    int skipped,
    int wouldRemove,
    String sourceKind,
    OriginCheck origin,
    boolean widgetEnabled,
    List<String> notes) {

    public static final String FULL = "full";
    public static final String INCREMENTAL = "incremental";
    public static final String RETRY = "retry";

    public SyncReport {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** The run went through: no error, no 402, no address problem. */
    public boolean ok() {
        return error == null;
    }

    /** A run that did not start, for the given reason. */
    public static SyncReport refused(String mode, String error, Instant at) {
        return SyncReport.builder().mode(mode).error(error).startedAt(at).finishedAt(at).build();
    }
}
