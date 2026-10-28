package ai.momobird.halo.halo;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;

/**
 * Watches every change Halo makes to a post. Halo's own post events only follow the spec (title,
 * content, visibility, publishing): adding {@code momobird.ai/exclude} or turning on restricted
 * reading changes annotations only and publishes nothing, so without this a marked post would stay
 * in MomoBird until the next full sync.
 *
 * <p>Halo starts this controller when the plugin has started and disposes it when the plugin
 * stops (PluginControllerManager, 2.17 and later). It does not list every post on start: the
 * plugin's own startup full sync reads them all and sets the baseline. Each update only hands the
 * post's annotations to {@link SyncService#postSeen}, which decides whether the engine should
 * look again; the engine's debounce merges it with Halo's event for the same change.
 *
 * <p>Runs on Halo's controller thread and never throws or asks to be retried.
 */
@Component
public class PostWatcher implements Reconciler<Reconciler.Request> {

    private static final Logger log = LoggerFactory.getLogger(PostWatcher.class);

    private final ExtensionClient client;
    private final SyncService sync;

    public PostWatcher(ExtensionClient client, SyncService sync) {
        this.client = client;
        this.sync = sync;
    }

    @Override
    public Result reconcile(Request request) {
        try {
            if (request == null || !sync.isRunning()) {
                return Result.doNotRetry();
            }
            String name = request.name();
            Optional<Post> post = client.fetch(Post.class, name);
            if (post.isEmpty()) {
                sync.postGone(name);
            } else {
                sync.postSeen(name, post.get().getMetadata().getAnnotations());
            }
        } catch (Throwable e) {
            // Post names hold no secrets; the message of an unexpected error is left out on purpose.
            log.debug("[MomoBird] 读取文章变化失败：{}", e.getClass().getSimpleName());
        }
        return Result.doNotRetry();
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder
            .extension(new Post())
            .syncAllOnStart(false)
            .build();
    }
}
