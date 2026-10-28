package ai.momobird.halo.halo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.momobird.halo.core.PostView;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequest;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalUrlSupplier;

class HaloPostSourceTest {

    private static Post post(String name) {
        Post post = new Post();
        Metadata metadata = new Metadata();
        metadata.setName(name);
        post.setMetadata(metadata);
        return post;
    }

    @Test
    void readsTheNextPageOnlyWhenThePreviousOneIsUsedUp() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        List<Integer> asked = new ArrayList<>();
        when(client.listBy(eq(Post.class), any(ListOptions.class), any(PageRequest.class)))
            .thenAnswer(call -> {
                int page = ((PageRequest) call.getArgument(2)).getPageNumber();
                asked.add(page);
                List<Post> items = page == 3 ? List.of(post("p5"))
                    : List.of(post("p" + (2 * page - 1)), post("p" + (2 * page)));
                return Mono.just(new ListResult<>(page, 2, 5, items));
            });
        HaloPostSource source = new HaloPostSource(
            new HaloPosts(client, mock(PostContentService.class), mock(ExternalUrlSupplier.class)));

        try (Stream<PostView> all = source.all()) {
            Iterator<PostView> it = all.iterator();
            assertThat(it.next().name()).isEqualTo("p1");
            assertThat(asked).containsExactly(1);
            assertThat(it.next().name()).isEqualTo("p2");
            assertThat(asked).containsExactly(1);
            assertThat(it.next().name()).isEqualTo("p3");
            assertThat(asked).containsExactly(1, 2);
            List<String> rest = new ArrayList<>();
            it.forEachRemaining(v -> rest.add(v.name()));
            assertThat(rest).containsExactly("p4", "p5");
            assertThat(asked).containsExactly(1, 2, 3);
        }
    }

    @Test
    void anEmptySiteListsNothing() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.listBy(eq(Post.class), any(ListOptions.class), any(PageRequest.class)))
            .thenReturn(Mono.just(new ListResult<>(1, 100, 0, List.of())));
        HaloPostSource source = new HaloPostSource(
            new HaloPosts(client, mock(PostContentService.class), mock(ExternalUrlSupplier.class)));
        assertThat(source.all().count()).isZero();
    }
}
