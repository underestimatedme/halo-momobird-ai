package ai.momobird.halo.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * One item of connect's {@code PUT /posts}. Field names are the API's; {@code category} and
 * {@code modified_at} are left out of the JSON when absent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostPayload(
    @JsonProperty("external_id") String externalId,
    @JsonProperty("title") String title,
    @JsonProperty("content") String content,
    @JsonProperty("url") String url,
    @JsonProperty("category") String category,
    @JsonProperty("modified_at") String modifiedAt) {

    static final int MAX_TITLE_CHARS = 255;
    static final int MAX_CATEGORY_CHARS = 64;

    private static final DateTimeFormatter ISO_UTC_MILLIS =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    /**
     * Maps a post that {@link SyncRules} lets through.
     *
     * @param html      the released HTML ({@code PostContentService.getReleaseContent})
     * @param category  the top category's display name; null or blank sends none
     * @param siteBase  Halo's external address (an origin is enough); the permalink is appended to it
     * @return empty when there is nothing MomoBird could accept: no text, no permalink or no site
     *         address. Like the Hexo plugin a post with no text is skipped without an error.
     */
    public static Optional<PostPayload> of(PostView post, String html, String category, String siteBase) {
        String url = url(siteBase, post.permalink());
        if (url == null) {
            return Optional.empty();
        }
        String title = post.title() == null ? "" : post.title().strip();
        if (title.isEmpty()) {
            title = post.name() == null ? "" : post.name();
        }
        title = truncate(title, MAX_TITLE_CHARS);
        String content = ContentText.document(title, HiddenContent.visibleHtml(post.annotations(), html));
        if (content.isEmpty()) {
            return Optional.empty();
        }
        String topCategory = category == null || category.isBlank() ? null : truncate(category.strip(), MAX_CATEGORY_CHARS);
        return Optional.of(new PostPayload(PostId.of(post.name()), title, content, url, topCategory,
            modifiedAt(post.publishTime(), post.lastModified())));
    }

    /** The later of the two, as UTC ISO-8601 with milliseconds (what Hexo's toISOString gives). */
    static String modifiedAt(Instant publishTime, Instant lastModified) {
        Instant later = publishTime;
        if (later == null || (lastModified != null && lastModified.isAfter(later))) {
            later = lastModified;
        }
        return later == null ? null : ISO_UTC_MILLIS.format(later);
    }

    /** The post's address, or null when it cannot be built (no permalink, or a relative one and no site address). */
    static String url(String siteBase, String permalink) {
        if (permalink == null || permalink.isBlank()) {
            return null;
        }
        String path = permalink.strip();
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return path;
        }
        if (siteBase == null || siteBase.isBlank()) {
            return null;
        }
        String base = siteBase.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start++;
        }
        return base + "/" + path.substring(start);
    }

    /** By code point, so a surrogate pair is never cut in half. */
    private static String truncate(String text, int max) {
        if (text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max));
    }
}
