package ai.momobird.halo.halo;

import ai.momobird.halo.core.CategoryTree;
import ai.momobird.halo.core.PostView;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalUrlSupplier;

/**
 * The only place that talks to Halo about posts. It reads and copies fields; every decision
 * (which posts sync, how they map) lives in {@code core} and is tested without Halo.
 *
 * <p>Everything here is read-only and uses what Halo 2.17 already has: {@code listBy} with
 * {@code ListOptions} and {@code PageRequestImpl}, {@code PostContentService} and
 * {@code ExternalUrlSupplier}.
 */
@Component
public class HaloPosts {

    /** Posts and categories are listed this many at a time; bodies are never part of a listing. */
    public static final int PAGE_SIZE = 100;

    private static final Sort BY_NAME = Sort.by(Sort.Order.asc("metadata.name"));

    private final ReactiveExtensionClient client;
    private final PostContentService contentService;
    private final ExternalUrlSupplier externalUrl;

    public HaloPosts(ReactiveExtensionClient client, PostContentService contentService,
                     ExternalUrlSupplier externalUrl) {
        this.client = client;
        this.contentService = contentService;
        this.externalUrl = externalUrl;
    }

    /** One page of the post listing: the posts and whether another page follows. */
    public record Page(List<PostView> items, boolean hasNext) {
        public Page {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * Page {@code number} (1-based) of every post Halo holds (drafts, private, trashed too:
     * {@code SyncRules} decides), without bodies. For a caller that blocks page by page with a
     * timeout on each read ({@code HaloPostSource}).
     */
    public Mono<Page> listPage(int number) {
        return page(Post.class, number).map(result -> new Page(
            result.getItems().stream().map(HaloPosts::view).toList(),
            result.hasNext() && !result.getItems().isEmpty()));
    }

    /** One post, or empty when it no longer exists (a permanently deleted post). */
    public Mono<PostView> get(String name) {
        return client.fetch(Post.class, name).map(HaloPosts::view);
    }

    /**
     * The released HTML of a post: what visitors see, not the draft being edited. Empty text when
     * the post has no released content.
     */
    public Mono<String> releaseHtml(String name) {
        return contentService.getReleaseContent(name)
            .flatMap(wrapper -> Mono.justOrEmpty(wrapper.getContent()))
            .defaultIfEmpty("");
    }

    /**
     * The category hierarchy, read fresh. Read it once per sync run and ask
     * {@link CategoryTree#topOf} for every post of that run.
     */
    public Mono<CategoryTree> categoryTree() {
        return page(Category.class, 1)
            .expand(result -> result.hasNext() && !result.getItems().isEmpty()
                ? page(Category.class, result.getPage() + 1) : Mono.empty())
            .concatMapIterable(ListResult::getItems)
            .filter(category -> !category.isDeleted() && category.getSpec() != null)
            .map(category -> new CategoryTree.Node(
                category.getMetadata().getName(),
                category.getSpec().getDisplayName(),
                category.getSpec().getChildren()))
            .collectList()
            .map(CategoryTree::of);
    }

    /**
     * Halo's external address, or null when it is not set. Read on every call so a change in
     * Halo's settings is picked up without restarting the plugin.
     */
    public String externalUrl() {
        URL raw = externalUrl.getRaw();
        return raw == null ? null : raw.toString();
    }

    // ------------------------------------------------------------------

    private <E extends Extension> Mono<ListResult<E>> page(Class<E> type, int number) {
        return client.listBy(type, new ListOptions(), PageRequestImpl.of(number, PAGE_SIZE, BY_NAME));
    }

    /** Copies what the sync needs out of a {@link Post}. */
    static PostView view(Post post) {
        Post.PostSpec spec = post.getSpec();
        Post.PostStatus status = post.getStatus();
        Map<String, String> annotations = post.getMetadata().getAnnotations();
        List<String> categories = spec == null ? null : spec.getCategories();
        return PostView.builder()
            .name(post.getMetadata().getName())
            .title(spec == null ? null : spec.getTitle())
            .publish(spec != null && Boolean.TRUE.equals(spec.getPublish()))
            .phase(status == null ? null : status.getPhase())
            .publishTime(spec == null ? null : spec.getPublishTime())
            .visible(spec == null || spec.getVisible() == null ? null : spec.getVisible().name())
            .deleted((spec != null && Boolean.TRUE.equals(spec.getDeleted()))
                || post.getMetadata().getDeletionTimestamp() != null)
            .annotations(annotations)
            .permalink(status == null ? null : status.getPermalink())
            .categoryNames(categories)
            .lastModified(status == null ? null : status.getLastModifyTime())
            .build();
    }
}
