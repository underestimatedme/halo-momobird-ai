package ai.momobird.halo.core;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Where the sync engine reads posts from: Halo in production ({@code HaloPostSource}), a map in
 * tests. Every method blocks; the engine only calls them on its own background thread.
 */
public interface PostSource {

    /**
     * Every post Halo holds, drafts and trash included, without bodies, read page by page as the
     * stream is consumed. The caller closes the stream.
     */
    Stream<PostView> all();

    /** One post, or empty when it no longer exists. */
    Optional<PostView> get(String name);

    /** The released HTML of a post; empty text when it has none. */
    String releaseHtml(String name);

    /** The category hierarchy; read once per run. */
    CategoryTree categoryTree();

    /** Halo's external address, or null when it is not set. */
    String externalUrl();
}
