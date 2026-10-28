package ai.momobird.halo.core;

import java.util.regex.Pattern;

/**
 * The podcast player's markup: the hidden element the player script looks for, and the script
 * itself. Same markup and attributes as {@code hexo-momobird-ai/lib/frontend.js}, so the one
 * player script serves every plugin. Only ids go into the markup, never the connection key.
 */
public final class PodcastMarkup {

    public static final String SCRIPT_SRC = "https://momobird.atlaspaces.com/widget/podcast.v1.js";

    private static final Pattern SOURCE_ID = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTERNAL_ID = Pattern.compile("^[0-9]{1,20}$");

    private PodcastMarkup() {
    }

    public static String scriptTag() {
        return "<script defer src=\"" + SCRIPT_SRC + "\"></script>";
    }

    /** The placeholder, or an empty string unless both ids are well formed. */
    public static String placeholder(String sourceId, String externalId, String api, boolean transcript) {
        if (sourceId == null || !SOURCE_ID.matcher(sourceId).matches()) {
            return "";
        }
        if (externalId == null || !EXTERNAL_ID.matcher(externalId).matches()) {
            return "";
        }
        String base = api == null ? "" : api.replaceAll("/+$", "");
        return "<div class=\"momobird-podcast\""
            + " data-source=\"" + escape(sourceId) + "\""
            + " data-post=\"" + escape(externalId) + "\""
            + " data-api=\"" + escape(base) + "\""
            + (transcript ? " data-transcript=\"1\"" : "")
            + "></div>";
    }

    static String escape(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}
