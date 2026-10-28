package ai.momobird.halo.halo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.momobird.halo.core.CategoryTree;
import ai.momobird.halo.core.PostView;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import run.halo.app.content.ContentWrapper;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequest;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalUrlSupplier;

class HaloPostsTest {

    private ReactiveExtensionClient client;
    private PostContentService content;
    private ExternalUrlSupplier external;
    private HaloPosts posts;

    @BeforeEach
    void setUp() {
        client = mock(ReactiveExtensionClient.class);
        content = mock(PostContentService.class);
        external = mock(ExternalUrlSupplier.class);
        posts = new HaloPosts(client, content, external);
    }

    private static Post post(String name) {
        Post post = new Post();
        Metadata metadata = new Metadata();
        metadata.setName(name);
        post.setMetadata(metadata);
        Post.PostSpec spec = new Post.PostSpec();
        spec.setTitle("标题 " + name);
        spec.setPublish(true);
        spec.setDeleted(false);
        spec.setVisible(Post.VisibleEnum.PUBLIC);
        spec.setPublishTime(Instant.parse("2026-10-01T00:00:00Z"));
        spec.setCategories(List.of("c1", "c2"));
        post.setSpec(spec);
        Post.PostStatus status = new Post.PostStatus();
        status.setPhase("PUBLISHED");
        status.setPermalink("/archives/" + name);
        status.setLastModifyTime(Instant.parse("2026-10-02T00:00:00Z"));
        post.setStatus(status);
        return post;
    }

    private static Category category(String name, String display, String... children) {
        Category category = new Category();
        Metadata metadata = new Metadata();
        metadata.setName(name);
        category.setMetadata(metadata);
        Category.CategorySpec spec = new Category.CategorySpec();
        spec.setDisplayName(display);
        spec.setChildren(List.of(children));
        category.setSpec(spec);
        return category;
    }

    @Test
    void mapsAPostToAView() {
        Post post = post("p1");
        post.getMetadata().setAnnotations(Map.of("momobird.ai/exclude", "true"));

        PostView view = HaloPosts.view(post);

        assertThat(view.name()).isEqualTo("p1");
        assertThat(view.title()).isEqualTo("标题 p1");
        assertThat(view.publish()).isTrue();
        assertThat(view.phase()).isEqualTo("PUBLISHED");
        assertThat(view.publishTime()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(view.visible()).isEqualTo("PUBLIC");
        assertThat(view.deleted()).isFalse();
        assertThat(view.annotations()).containsEntry("momobird.ai/exclude", "true");
        assertThat(view.permalink()).isEqualTo("/archives/p1");
        assertThat(view.categoryNames()).containsExactly("c1", "c2");
        assertThat(view.lastModified()).isEqualTo(Instant.parse("2026-10-02T00:00:00Z"));
    }

    @Test
    void aTrashedOrBeingDeletedPostIsDeleted() {
        Post trashed = post("t");
        trashed.getSpec().setDeleted(true);
        assertThat(HaloPosts.view(trashed).deleted()).isTrue();

        Post going = post("g");
        going.getMetadata().setDeletionTimestamp(Instant.now());
        assertThat(HaloPosts.view(going).deleted()).isTrue();
    }

    @Test
    void aPostWithMissingPartsStillMaps() {
        Post bare = new Post();
        Metadata metadata = new Metadata();
        metadata.setName("bare");
        bare.setMetadata(metadata);

        PostView view = HaloPosts.view(bare);

        assertThat(view.name()).isEqualTo("bare");
        assertThat(view.publish()).isFalse();
        assertThat(view.deleted()).isFalse();
        assertThat(view.annotations()).isEmpty();
        assertThat(view.categoryNames()).isEmpty();
        assertThat(view.visible()).isNull();
    }

    @Test
    void listPageAsksForOneFixedSizedSortedPage() {
        when(client.listBy(eq(Post.class), any(ListOptions.class), any(PageRequest.class)))
            .thenAnswer(call -> {
                PageRequest request = call.getArgument(2);
                int page = request.getPageNumber();
                List<Post> items = page == 3 ? List.of(post("p5")) : List.of(post("p" + (2 * page - 1)), post("p" + (2 * page)));
                return Mono.just(new ListResult<>(page, 2, 5, items));
            });

        HaloPosts.Page second = posts.listPage(2).block();
        HaloPosts.Page last = posts.listPage(3).block();

        assertThat(second.items()).extracting(PostView::name).containsExactly("p3", "p4");
        assertThat(second.hasNext()).isTrue();
        assertThat(last.items()).extracting(PostView::name).containsExactly("p5");
        assertThat(last.hasNext()).isFalse();
        ArgumentCaptor<PageRequest> pages = ArgumentCaptor.forClass(PageRequest.class);
        verify(client, org.mockito.Mockito.times(2)).listBy(eq(Post.class), any(ListOptions.class), pages.capture());
        assertThat(pages.getAllValues()).extracting(PageRequest::getPageNumber).containsExactly(2, 3);
        assertThat(pages.getAllValues()).extracting(PageRequest::getPageSize).containsOnly(HaloPosts.PAGE_SIZE);
        assertThat(pages.getAllValues().get(0).getSort().isSorted()).isTrue();
    }

    @Test
    void anEmptyPageEndsTheListingEvenIfTheTotalPromisesMore() {
        when(client.listBy(eq(Post.class), any(ListOptions.class), any(PageRequest.class)))
            .thenReturn(Mono.just(new ListResult<>(1, 100, 500, new ArrayList<Post>())));
        HaloPosts.Page page = posts.listPage(1).block();
        assertThat(page.items()).isEmpty();
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void getReturnsAViewOrNothing() {
        when(client.fetch(Post.class, "p1")).thenReturn(Mono.just(post("p1")));
        when(client.fetch(Post.class, "gone")).thenReturn(Mono.empty());

        assertThat(posts.get("p1").block().title()).isEqualTo("标题 p1");
        assertThat(posts.get("gone").block()).isNull();
    }

    @Test
    void releaseHtmlIsTheReleasedContent() {
        when(content.getReleaseContent("p1")).thenReturn(Mono.just(
            ContentWrapper.builder().content("<p>发布版</p>").raw("草稿").build()));
        assertThat(posts.releaseHtml("p1").block()).isEqualTo("<p>发布版</p>");
        verify(content, never()).getHeadContent(any());
    }

    @Test
    void releaseHtmlIsEmptyTextWithoutReleasedContent() {
        when(content.getReleaseContent("none")).thenReturn(Mono.empty());
        when(content.getReleaseContent("null")).thenReturn(Mono.just(ContentWrapper.builder().build()));
        assertThat(posts.releaseHtml("none").block()).isEmpty();
        assertThat(posts.releaseHtml("null").block()).isEmpty();
    }

    @Test
    void categoryTreeReadsEveryPageAndSkipsCategoriesBeingDeleted() {
        Category gone = category("gone", "已删除");
        gone.getMetadata().setDeletionTimestamp(Instant.now());
        when(client.listBy(eq(Category.class), any(ListOptions.class), any(PageRequest.class)))
            .thenAnswer(call -> {
                PageRequest request = call.getArgument(2);
                return request.getPageNumber() == 1
                    ? Mono.just(new ListResult<>(1, 2, 3, List.of(category("c1", "技术", "c2"), gone)))
                    : Mono.just(new ListResult<>(2, 2, 3, List.of(category("c2", "Java"))));
            });
        CategoryTree tree = posts.categoryTree().block();

        PostView child = PostView.builder().name("p").categoryNames(List.of("c2")).build();
        PostView removed = PostView.builder().name("p").categoryNames(List.of("gone")).build();
        assertThat(tree.topOf(child)).contains("技术");
        assertThat(tree.topOf(removed)).isEmpty();
    }

    @Test
    void externalUrlIsNullWhenUnset() {
        when(external.getRaw()).thenReturn(null);
        assertThat(posts.externalUrl()).isNull();
    }

    @Test
    void externalUrlIsTheConfiguredAddress() throws Exception {
        when(external.getRaw()).thenReturn(URI.create("https://blog.example.com/").toURL());
        assertThat(posts.externalUrl()).isEqualTo("https://blog.example.com/");
        URL none = null;
        when(external.getRaw()).thenReturn(none);
        assertThat(posts.externalUrl()).isNull();
    }
}
