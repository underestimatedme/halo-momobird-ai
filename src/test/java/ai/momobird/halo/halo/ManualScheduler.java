package ai.momobird.halo.halo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.PriorityQueue;
import java.util.concurrent.RejectedExecutionException;

/**
 * A {@link SyncService.Scheduler} on virtual time: nothing runs until the test advances the
 * clock, and then every task that has come due runs in order, on the test's thread.
 */
class ManualScheduler implements SyncService.Scheduler {

    private record Task(long at, long seq, Runnable body) {
    }

    private final PriorityQueue<Task> queue = new PriorityQueue<>(
        (a, b) -> a.at != b.at ? Long.compare(a.at, b.at) : Long.compare(a.seq, b.seq));
    private final Instant origin;
    private final ZoneId zone;
    private long now;
    private long seq;
    private boolean shut;

    ManualScheduler(Instant origin, ZoneId zone) {
        this.origin = origin;
        this.zone = zone;
    }

    final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId other) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return origin.plusMillis(now);
        }
    };

    @Override
    public void schedule(Runnable task, Duration delay) {
        if (shut) {
            throw new RejectedExecutionException("shut down");
        }
        queue.add(new Task(now + Math.max(0, delay.toMillis()), seq++, task));
    }

    @Override
    public void shutdown() {
        shut = true;
        queue.clear();
    }

    /** Moves time forward, running every task that comes due on the way. */
    void advance(Duration by) {
        long target = now + by.toMillis();
        while (!queue.isEmpty() && queue.peek().at <= target) {
            Task task = queue.poll();
            now = Math.max(now, task.at);
            task.body.run();
        }
        now = target;
    }

    /** Runs what is due now. */
    void runDue() {
        advance(Duration.ZERO);
    }

    int queued() {
        return queue.size();
    }

    Instant now() {
        return clock.instant();
    }
}
