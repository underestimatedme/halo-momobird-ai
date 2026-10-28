package ai.momobird.halo.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * The calls the sync engine makes to MomoBird's connect API. {@link ConnectClient} is the real
 * one; tests use an in-memory fake. Every method blocks and throws {@link ConnectError}.
 */
public interface ConnectApi {

    /** {@code GET /status}: project, source (kind, site_url), entitlement and widget state. */
    JsonNode status();

    /** {@code GET /posts}: one page ({@code items}, {@code next_cursor}); null cursor for the first. */
    JsonNode listPosts(String cursor);

    /** {@code PUT /posts}: at most 20 posts; answers {@code results}. */
    JsonNode upsert(List<PostPayload> posts);

    /** {@code POST /posts/delete}: at most 100 ids; answers {@code results}. */
    JsonNode delete(List<String> ids);

    /** {@code POST /widget/enable}. */
    JsonNode enableWidget();
}
