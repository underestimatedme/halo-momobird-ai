package ai.momobird.halo.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The plugin settings as one immutable value. It is built from what Halo's setting form stores
 * (see {@code SettingsLoader}) so everything that uses it can be tested without Halo.
 *
 * <p>{@link #toString()} leaves the key out on purpose; a record would print every component.
 */
public record Settings(
    String baseUrl,
    String key,
    boolean widgetEnabled,
    String widgetPosition,
    boolean podcastEnabled,
    boolean podcastTranscript) {

    public static final String DEFAULT_BASE_URL = "https://api.valley.atlaspaces.com";
    public static final String RIGHT = "right";
    public static final String LEFT = "left";

    /** What a real key looks like: the prefix, then URL-safe characters only. */
    private static final Pattern KEY_SHAPE = Pattern.compile("mbk_[A-Za-z0-9_-]+");

    /** Hosts that may be reached over plain http: local runs and the Docker integration test. */
    private static final Set<String> PLAIN_HTTP_HOSTS =
        Set.of("localhost", "127.0.0.1", "[::1]", "host.docker.internal");

    public Settings {
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        baseUrl = url.isEmpty() ? DEFAULT_BASE_URL : url;
        key = key == null ? "" : key.trim();
        widgetPosition = widgetPosition != null && LEFT.equals(widgetPosition.trim().toLowerCase(Locale.ROOT))
            ? LEFT : RIGHT;
    }

    /** Builds settings from form values where {@code null} means "not saved yet": defaults apply. */
    public static Settings of(String baseUrl, String key, Boolean widgetEnabled, String widgetPosition,
                              Boolean podcastEnabled, Boolean podcastTranscript) {
        return new Settings(baseUrl, key,
            Boolean.TRUE.equals(widgetEnabled),
            widgetPosition,
            podcastEnabled == null || podcastEnabled,
            Boolean.TRUE.equals(podcastTranscript));
    }

    public boolean hasKey() {
        return !key.isEmpty();
    }

    /**
     * Problems that stop the plugin from talking to MomoBird, as Chinese sentences for the admin
     * page. Empty means usable. No message repeats the address or the key.
     */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        String urlProblem = urlProblem();
        if (urlProblem != null) {
            problems.add(urlProblem);
        }
        if (key.isEmpty()) {
            problems.add("还没有填写连接密钥，请在 MomoBird 控制台的知识源页面生成后填入");
        } else if (!KEY_SHAPE.matcher(key).matches()) {
            // Also keeps anything that is not header-safe (newline, space, non-ASCII) out of the
            // Authorization header. The message never repeats the key.
            problems.add("连接密钥格式不对：应以 mbk_ 开头，只含字母、数字、下划线和短横线，请检查是否复制完整");
        }
        return problems;
    }

    private String urlProblem() {
        String general = "Base URL 不合法：必须是 HTTPS 地址（本地联调可以用 http://localhost 或 http://127.0.0.1），"
            + "并且不能带路径、账号、查询参数或锚点";
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException e) {
            return general;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean shapeOk = (scheme.equals("https") || scheme.equals("http"))
            && !host.isEmpty()
            && uri.getRawUserInfo() == null
            && uri.getRawQuery() == null
            && uri.getRawFragment() == null
            && (uri.getRawPath() == null || uri.getRawPath().isEmpty());
        if (!shapeOk) {
            return general;
        }
        if (scheme.equals("http") && !PLAIN_HTTP_HOSTS.contains(host)) {
            return "Base URL 必须使用 HTTPS，否则连接密钥会明文发送";
        }
        return null;
    }

    @Override
    public String toString() {
        return "Settings[baseUrl=" + baseUrl + ", key=" + (hasKey() ? "***" : "") + ", widgetEnabled="
            + widgetEnabled + ", widgetPosition=" + widgetPosition + ", podcastEnabled=" + podcastEnabled
            + ", podcastTranscript=" + podcastTranscript + "]";
    }
}
