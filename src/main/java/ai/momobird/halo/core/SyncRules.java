package ai.momobird.halo.core;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Which Halo posts MomoBird should hold, and whether the site address is one MomoBird will accept.
 * Pure functions: everything Halo is asked is already in {@link PostView}.
 *
 * <p>Like the Hexo plugin these checks fail closed: a value that is not clearly "yes, sync" keeps
 * the post out.
 */
public final class SyncRules {

    public static final String EXCLUDE_ANNOTATION = "momobird.ai/exclude";

    public static final String IN_TRASH = "在回收站";
    public static final String NOT_PUBLISHED = "未发布";
    public static final String SCHEDULED = "定时发布未到时间";
    public static final String NOT_PUBLIC = "不是公开可见";
    public static final String EXCLUDED = "已排除（" + EXCLUDE_ANNOTATION + "）";

    private static final Set<String> TRUE_WORDS = Set.of("true", "yes", "on", "1");

    private SyncRules() {
    }

    public static Optional<String> exclusion(PostView post) {
        return exclusion(post, Instant.now());
    }

    /**
     * Why the post stays out of MomoBird, or empty when it should be there. The first rule that
     * applies wins: trash, not published, scheduled, not public, excluded by metadata.
     */
    public static Optional<String> exclusion(PostView post, Instant now) {
        if (post.deleted()) {
            // Trashing also unpublishes the post; the trash is the reason an admin looks for.
            return Optional.of(IN_TRASH);
        }
        if (!isPublished(post)) {
            return Optional.of(isScheduled(post, now) ? SCHEDULED : NOT_PUBLISHED);
        }
        if (!isPublic(post)) {
            return Optional.of(NOT_PUBLIC);
        }
        if (isExcluded(post)) {
            return Optional.of(EXCLUDED);
        }
        return Optional.empty();
    }

    /** Halo serves a post once its reconciler has moved it to PUBLISHED, and not after the author unpublishes. */
    private static boolean isPublished(PostView post) {
        return post.publish() && "PUBLISHED".equalsIgnoreCase(post.phase());
    }

    /**
     * The author asked for publication at a time that has not come: Halo holds the post back and
     * publishes it itself then (an already published post is never held back).
     */
    private static boolean isScheduled(PostView post, Instant now) {
        return post.publish() && post.publishTime() != null && post.publishTime().isAfter(now);
    }

    private static boolean isPublic(PostView post) {
        String visible = post.visible();
        // Halo reads a missing value as PUBLIC; any value it does not know is not.
        return visible == null || "PUBLIC".equalsIgnoreCase(visible.trim());
    }

    private static boolean isExcluded(PostView post) {
        String value = post.annotations().get(EXCLUDE_ANNOTATION);
        return value != null && TRUE_WORDS.contains(value.strip().toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------

    /**
     * Compares Halo's external address with the source address MomoBird holds: scheme, host and
     * port must all match (paths, trailing slashes and letter case do not count, nor do the
     * default ports). An unset external address never passes.
     */
    public static OriginCheck origin(String externalUrl, String sourceUrl) {
        String site = originOf(externalUrl);
        String source = originOf(sourceUrl);
        if (site == null) {
            String reason = blank(externalUrl)
                ? "Halo 的外部访问地址还没有设置，请到「系统设置 → 基本设置」填写网站地址（旧版本在配置里设置 halo.external-url）"
                : "Halo 的外部访问地址不是有效的网址，请到「系统设置 → 基本设置」检查";
            return new OriginCheck(false, null, source, reason);
        }
        if (source == null) {
            String reason = blank(sourceUrl)
                ? "MomoBird 里这个来源还没有填写网站地址"
                : "MomoBird 里这个来源的网站地址不是有效的网址";
            return new OriginCheck(false, site, null, reason);
        }
        if (!site.equals(source)) {
            return new OriginCheck(false, site, source,
                "Halo 的外部访问地址（" + site + "）和 MomoBird 来源的网站地址（" + source + "）不一致");
        }
        return new OriginCheck(true, site, source, null);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** {@code scheme://host[:port]} in lower case with the default port left out, or null. */
    static String originOf(String value) {
        if (blank(value)) {
            return null;
        }
        String text = value.strip();
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            // e.g. a non-ASCII host or an unescaped character in the path: look at the authority only
            uri = authorityOnly(text);
            if (uri == null) {
                return null;
            }
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            host = hostFromAuthority(uri.getRawAuthority());
        }
        if (host == null || host.isEmpty()) {
            return null;
        }
        String ascii;
        try {
            ascii = host.startsWith("[") ? host : IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int port = uri.getPort();
        boolean implied = port == -1 || ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + ascii.toLowerCase(Locale.ROOT) + (implied ? "" : ":" + port);
    }

    /** Re-parses {@code scheme://authority} when the whole text is not a valid URI (non-ASCII host). */
    private static URI authorityOnly(String text) {
        int sep = text.indexOf("://");
        if (sep <= 0) {
            return null;
        }
        int end = text.length();
        for (char stop : new char[] {'/', '?', '#'}) {
            int at = text.indexOf(stop, sep + 3);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        String scheme = text.substring(0, sep);
        String authority = text.substring(sep + 3, end);
        String host = hostFromAuthority(authority);
        if (host == null || host.isEmpty()) {
            return null;
        }
        String ascii;
        try {
            ascii = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int colon = authority.lastIndexOf(':');
        String portText = colon >= 0 && !authority.endsWith("]") ? authority.substring(colon) : "";
        try {
            return new URI(scheme + "://" + ascii + portText);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String hostFromAuthority(String authority) {
        if (authority == null) {
            return null;
        }
        String host = authority;
        int at = host.lastIndexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1);
        }
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            return close < 0 ? null : host.substring(0, close + 1);
        }
        int colon = host.lastIndexOf(':');
        return colon >= 0 ? host.substring(0, colon) : host;
    }
}
