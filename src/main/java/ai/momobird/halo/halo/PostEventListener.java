package ai.momobird.halo.halo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import run.halo.app.event.post.PostDeletedEvent;
import run.halo.app.event.post.PostPublishedEvent;
import run.halo.app.event.post.PostUnpublishedEvent;
import run.halo.app.event.post.PostUpdatedEvent;
import run.halo.app.event.post.PostVisibleChangedEvent;

/**
 * Halo's post events (shared with plugins since 2.17). Halo calls these on its own threads, so each
 * only queues the post's name and returns; {@link SyncService} re-reads the post on its own
 * thread and decides whether to push it or take it down, whatever the event was.
 */
@Component
public class PostEventListener {

    private static final Logger log = LoggerFactory.getLogger(PostEventListener.class);

    private final SyncService sync;

    public PostEventListener(SyncService sync) {
        this.sync = sync;
    }

    @EventListener(PostPublishedEvent.class)
    public void onPublished(PostPublishedEvent event) {
        seen("PostPublishedEvent", event.getName());
    }

    @EventListener(PostUpdatedEvent.class)
    public void onUpdated(PostUpdatedEvent event) {
        seen("PostUpdatedEvent", event.getName());
    }

    @EventListener(PostUnpublishedEvent.class)
    public void onUnpublished(PostUnpublishedEvent event) {
        seen("PostUnpublishedEvent", event.getName());
    }

    @EventListener(PostDeletedEvent.class)
    public void onDeleted(PostDeletedEvent event) {
        seen("PostDeletedEvent", event.getName());
    }

    @EventListener(PostVisibleChangedEvent.class)
    public void onVisibleChanged(PostVisibleChangedEvent event) {
        seen("PostVisibleChangedEvent", event.getName());
    }

    /** Debug level only (the integration test turns it on): which event reached the plugin. Post names hold no secrets. */
    private void seen(String event, String postName) {
        log.debug("[MomoBird] 收到文章事件 {} {}", event, postName);
        sync.enqueue(postName);
    }
}
