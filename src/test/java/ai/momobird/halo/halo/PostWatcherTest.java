package ai.momobird.halo.halo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;

class PostWatcherTest {

    final ExtensionClient client = mock(ExtensionClient.class);
    final SyncService sync = mock(SyncService.class);
    final PostWatcher watcher = new PostWatcher(client, sync);

    static Post post(String name, Map<String, String> annotations) {
        Post post = new Post();
        Metadata metadata = new Metadata();
        metadata.setName(name);
        metadata.setAnnotations(annotations);
        post.setMetadata(metadata);
        return post;
    }

    @Test
    void handsTheAnnotationsOfTheUpdatedPostToTheSyncService() {
        when(sync.isRunning()).thenReturn(true);
        when(client.fetch(Post.class, "p1")).thenReturn(Optional.of(post("p1", Map.of("momobird.ai/exclude", "true"))));

        Reconciler.Result result = watcher.reconcile(new Reconciler.Request("p1"));

        verify(sync).postSeen("p1", Map.of("momobird.ai/exclude", "true"));
        assertThat(result.reEnqueue()).isFalse();
    }

    @Test
    void aPostThatIsGoneIsForgotten() {
        when(sync.isRunning()).thenReturn(true);
        when(client.fetch(Post.class, "p1")).thenReturn(Optional.empty());

        watcher.reconcile(new Reconciler.Request("p1"));

        verify(sync).postGone("p1");
        verify(sync, never()).postSeen(anyString(), any());
    }

    @Test
    void doesNothingOnceThePluginStopped() {
        when(sync.isRunning()).thenReturn(false);

        Reconciler.Result result = watcher.reconcile(new Reconciler.Request("p1"));

        verifyNoInteractions(client);
        assertThat(result.reEnqueue()).isFalse();
    }

    @Test
    void neverThrowsAndNeverRetries() {
        when(sync.isRunning()).thenReturn(true);
        when(client.fetch(Post.class, "p1")).thenThrow(new IllegalStateException("boom"));
        assertThat(watcher.reconcile(new Reconciler.Request("p1")).reEnqueue()).isFalse();

        when(client.fetch(Post.class, "p2")).thenReturn(Optional.of(post("p2", null)));
        org.mockito.Mockito.doThrow(new LinkageError("odd")).when(sync).postSeen(anyString(), any());
        assertThat(watcher.reconcile(new Reconciler.Request("p2")).reEnqueue()).isFalse();

        assertThat(watcher.reconcile(null).reEnqueue()).isFalse();
    }

    @Test
    void watchesPostsWithoutSyncingAllOnStart() {
        ControllerBuilder builder = mock(ControllerBuilder.class, org.mockito.Mockito.RETURNS_SELF);
        Controller controller = mock(Controller.class);
        when(builder.build()).thenReturn(controller);

        assertThat(watcher.setupWith(builder)).isSameAs(controller);

        ArgumentCaptor<Post> extension = ArgumentCaptor.forClass(Post.class);
        verify(builder).extension(extension.capture());
        assertThat(GroupVersionKind.fromExtension(Post.class)).isEqualTo(extension.getValue().groupVersionKind());
        verify(builder).syncAllOnStart(false);
    }
}
