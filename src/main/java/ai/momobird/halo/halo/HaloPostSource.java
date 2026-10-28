package ai.momobird.halo.halo;

import ai.momobird.halo.core.CategoryTree;
import ai.momobird.halo.core.PostSource;
import ai.momobird.halo.core.PostView;
import java.time.Duration;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * {@link HaloPosts} seen through the blocking {@link PostSource} the sync engine uses. Only ever
 * called on the engine's own background thread, never on a Reactor or request thread.
 */
final class HaloPostSource implements PostSource {

    /** One Halo read; a stuck read fails the run instead of holding the sync thread forever. */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private final HaloPosts posts;

    HaloPostSource(HaloPosts posts) {
        this.posts = posts;
    }

    /**
     * Reads the next page only when the engine has used up the previous one, so at most one page
     * of views (no bodies) is held, and each page read has its own timeout however long the
     * engine takes to push in between.
     */
    @Override
    public Stream<PostView> all() {
        Iterator<PostView> pages = new Iterator<>() {
            private Iterator<PostView> current = Collections.emptyIterator();
            private int next = 1;
            private boolean more = true;

            @Override
            public boolean hasNext() {
                while (!current.hasNext() && more) {
                    HaloPosts.Page page = posts.listPage(next).block(READ_TIMEOUT);
                    next++;
                    if (page == null) {
                        more = false;
                    } else {
                        current = page.items().iterator();
                        more = page.hasNext();
                    }
                }
                return current.hasNext();
            }

            @Override
            public PostView next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return current.next();
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(pages, Spliterator.ORDERED), false);
    }

    @Override
    public Optional<PostView> get(String name) {
        return posts.get(name).blockOptional(READ_TIMEOUT);
    }

    @Override
    public String releaseHtml(String name) {
        String html = posts.releaseHtml(name).block(READ_TIMEOUT);
        return html == null ? "" : html;
    }

    @Override
    public CategoryTree categoryTree() {
        CategoryTree tree = posts.categoryTree().block(READ_TIMEOUT);
        return tree == null ? CategoryTree.EMPTY : tree;
    }

    @Override
    public String externalUrl() {
        return posts.externalUrl();
    }
}
