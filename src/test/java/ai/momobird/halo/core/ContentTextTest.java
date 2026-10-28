package ai.momobird.halo.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContentTextTest {

    @Test
    void putsTheTitleOnTheFirstLineThenTheBody() {
        assertThat(ContentText.document("你好", "<p>第一段</p><p>第二段</p>"))
            .isEqualTo("# 你好\n\n第一段\n\n第二段");
    }

    @Test
    void keepsHeadingLevelsAsHashMarks() {
        String html = "<h1>一</h1><p>a</p><h2>二</h2><p>b</p><h3>三</h3><h6>六</h6>";
        assertThat(ContentText.document("T", html))
            .isEqualTo("# T\n\n# 一\n\na\n\n## 二\n\nb\n\n### 三\n\n###### 六");
    }

    @Test
    void stripsInlineTagsInsideHeadings() {
        assertThat(ContentText.document("T", "<h2>Hello <em>big</em> <a href=\"/x\">world</a></h2>"))
            .isEqualTo("# T\n\n## Hello big world");
    }

    @Test
    void keepsTheTextOfCodeBlocksAndTheirLineBreaks() {
        String html = "<pre><code class=\"language-java\">int a = 1;\nif (a &lt; 2) {\n  a++;\n}\n</code></pre>";
        assertThat(ContentText.document("T", html))
            .isEqualTo("# T\n\nint a = 1;\nif (a < 2) {\na++;\n}");
    }

    @Test
    void dropsScriptStyleAndIframeWithTheirContent() {
        String html = "<p>前</p><script>alert('x')</script><style>p{color:red}</style>"
            + "<iframe src=\"https://e.com\">fallback text</iframe><p>后</p>";
        assertThat(ContentText.document("T", html)).isEqualTo("# T\n\n前\n\n后");
    }

    @Test
    void dropsTheLineNumberGutterOfHighlightedCode() {
        String table = "<figure class=\"highlight\"><table><tr>"
            + "<td class=\"gutter\"><pre><span class=\"line\">1</span><br><span class=\"line\">2</span></pre></td>"
            + "<td class=\"code\"><pre><span class=\"line\">a</span><br><span class=\"line\">b</span></pre></td>"
            + "</tr></table></figure>";
        assertThat(ContentText.document("T", table)).isEqualTo("# T\n\na\nb");

        String lineNumbers = "<pre><code><span class=\"line-numbers-rows\"><span></span><span></span></span>"
            + "x = 1\ny = 2</code></pre>";
        assertThat(ContentText.document("T", lineNumbers)).isEqualTo("# T\n\nx = 1\ny = 2");

        String lineNo = "<pre><code><span class=\"line-number\">1</span>x = 1\n"
            + "<span class=\"line-number\">2</span>y = 2</code></pre>";
        assertThat(ContentText.document("T", lineNo)).isEqualTo("# T\n\nx = 1\ny = 2");
    }

    @Test
    void decodesEntitiesOnce() {
        assertThat(ContentText.document("T", "<p>a &amp; b &lt;c&gt; &quot;d&quot; &#39;e&#39; &#x4e2d;&nbsp;x</p>"))
            .isEqualTo("# T\n\na & b <c> \"d\" 'e' 中 x");
        assertThat(ContentText.document("T", "<p>&amp;lt;</p>")).isEqualTo("# T\n\n&lt;");
    }

    @Test
    void collapsesRunsOfWhitespaceAndBlankLines() {
        String html = "<p>a   \t b</p>\n\n\n\n<p>  c  </p><br><br><br><br><p>d</p>";
        assertThat(ContentText.document("T", html)).isEqualTo("# T\n\na b\n\nc\n\nd");
    }

    @Test
    void returnsAnEmptyStringWhenThereIsNoBodyText() {
        assertThat(ContentText.document("T", "")).isEmpty();
        assertThat(ContentText.document("T", null)).isEmpty();
        assertThat(ContentText.document("T", "  \n ")).isEmpty();
        assertThat(ContentText.document("T", "<p> </p><script>x</script><!-- c -->")).isEmpty();
    }

    @Test
    void trimsTheTitleAndToleratesAMissingOne() {
        assertThat(ContentText.document("  T  ", "<p>a</p>")).isEqualTo("# T\n\na");
        assertThat(ContentText.document(null, "<p>a</p>")).isEqualTo("# \n\na");
    }

    @Test
    void separatesListItemsAndBlockquotes() {
        assertThat(ContentText.document("T", "<ul><li>一</li><li>二</li></ul><blockquote>引用</blockquote>"))
            .isEqualTo("# T\n\n一\n\n二\n\n引用");
    }
}
