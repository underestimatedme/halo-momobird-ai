package ai.momobird.halo.core;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps text that a Halo plugin hides from visitors out of MomoBird, where the AI assistant would
 * quote it to anyone.
 *
 * <p>Known plugin: <em>plugin-restricted-reading</em> (xgs87762GH, Halo 2.25 or later). A post
 * whose annotation {@code restrict.enabled} is {@code true} shows visitors only what stands before
 * the first separator marker until they unlock it; without a marker the whole text is locked. The
 * stored release content keeps all of it, so it is cut here. The three marker spellings are the
 * ones that plugin recognizes.
 *
 * <p>No other hide-until-reply or login plugin for Halo 2 with a documented marker was found; see
 * the plugin README. Text such plugins keep out of the rendered page by other means is not covered.
 */
public final class HiddenContent {

    static final String RESTRICT_ENABLED = "restrict.enabled";

    private static final Pattern SEPARATOR = Pattern.compile(
        "(?:"
            + "<separator\\b[^>]*(?:/>|>\\s*</separator\\s*>)"
            + "|"
            + "&lt;separator\\b[^&]*(?:/\\s*&gt;|&gt;\\s*&lt;/separator\\s*&gt;)"
            + "|"
            + "<!--\\s*separator\\s*-->"
            + ")",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private HiddenContent() {
    }

    /** The part of the released HTML that any visitor can read. */
    public static String visibleHtml(Map<String, String> annotations, String html) {
        if (html == null) {
            return "";
        }
        Matcher marker = SEPARATOR.matcher(html);
        if (isRestricted(annotations)) {
            return marker.find() ? html.substring(0, marker.start()) : "";
        }
        return marker.replaceAll("");
    }

    private static boolean isRestricted(Map<String, String> annotations) {
        return annotations != null && "true".equalsIgnoreCase(annotations.get(RESTRICT_ENABLED));
    }
}
