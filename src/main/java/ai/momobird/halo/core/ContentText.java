package ai.momobird.halo.core;

import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * Turns the rendered HTML of a post into the plain-text document MomoBird indexes: a "# title"
 * line, then the body with heading levels kept as "#" marks and code text kept.
 *
 * <p>The rules mirror {@code lib/content.js} of hexo-momobird-ai: script, style and iframe are
 * dropped with their content, the line-number gutter of highlighted code is dropped, blocks are
 * separated by a blank line and whitespace runs are collapsed.
 */
public final class ContentText {

    private static final String DROPPED = "script, style, iframe, noscript";

    /** Line-number markup added by common code highlighters (Hexo, Prism, highlight.js, Pygments). */
    private static final String GUTTERS = "td.gutter, .gutter, .line-numbers-rows, .line-number, "
        + ".linenumber, .linenos, .hljs-ln-numbers";

    private static final Set<String> BLOCKS = Set.of(
        "p", "div", "li", "blockquote", "pre", "section", "article", "ul", "ol", "table", "tr",
        "figure", "figcaption");

    private static final Pattern HEADING = Pattern.compile("h[1-6]");
    private static final Pattern HORIZONTAL_SPACE = Pattern.compile("[\\t ]+");
    private static final Pattern BLANK_RUNS = Pattern.compile("\\n{3,}");

    private ContentText() {
    }

    public static String document(String title, String html) {
        String body = body(html);
        if (body.isEmpty()) {
            return "";
        }
        String heading = title == null ? "" : title.strip();
        return "# " + heading + "\n\n" + body;
    }

    private static String body(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Element root = Jsoup.parseBodyFragment(html).body();
        root.select(DROPPED).remove();
        root.select(GUTTERS).remove();

        StringBuilder text = new StringBuilder();
        for (Node child : root.childNodes()) {
            append(child, text);
        }
        return normalize(text.toString());
    }

    private static void append(Node node, StringBuilder out) {
        if (node instanceof TextNode textNode) {
            out.append(textNode.getWholeText());
            return;
        }
        if (!(node instanceof Element element)) {
            // comments, data nodes, doctype
            return;
        }
        String tag = element.normalName();
        if (HEADING.matcher(tag).matches()) {
            out.append("\n\n").append("#".repeat(tag.charAt(1) - '0')).append(' ')
                .append(inlineText(element)).append("\n\n");
            return;
        }
        if ("br".equals(tag)) {
            out.append('\n');
            return;
        }
        for (Node child : element.childNodes()) {
            append(child, out);
        }
        if (BLOCKS.contains(tag)) {
            out.append("\n\n");
        }
    }

    /** Heading text: tags removed, entities decoded, whitespace collapsed to single spaces. */
    private static String inlineText(Element element) {
        return element.text().replace(' ', ' ').strip();
    }

    private static String normalize(String raw) {
        String text = raw.replace(' ', ' ').replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(text.length());
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(HORIZONTAL_SPACE.matcher(lines[i]).replaceAll(" ").strip());
        }
        return BLANK_RUNS.matcher(out).replaceAll("\n\n").strip();
    }
}
