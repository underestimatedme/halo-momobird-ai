package ai.momobird.halo.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HiddenContentTest {

    private static final Map<String, String> RESTRICTED = Map.of("restrict.enabled", "true");

    @ParameterizedTest
    @ValueSource(strings = {
        "<separator></separator>",
        "<separator/>",
        "<SEPARATOR > </SEPARATOR>",
        "<!-- separator -->",
        "<!--separator-->",
        "&lt;separator&gt;&lt;/separator&gt;"})
    void aRestrictedPostKeepsOnlyWhatComesBeforeTheFirstSeparator(String marker) {
        String html = "<p>公开的前言</p>" + marker + "<p>只给解锁的人看</p>" + marker + "<p>更多隐藏</p>";
        assertThat(HiddenContent.visibleHtml(RESTRICTED, html)).isEqualTo("<p>公开的前言</p>");
    }

    @Test
    void aRestrictedPostWithoutASeparatorIsLockedEntirely() {
        assertThat(HiddenContent.visibleHtml(RESTRICTED, "<p>整篇都锁着</p>")).isEmpty();
    }

    @Test
    void theEnabledFlagIsReadLikeTheRestrictPluginDoes() {
        for (String on : new String[] {"true", "TRUE", "True"}) {
            assertThat(HiddenContent.visibleHtml(Map.of("restrict.enabled", on), "<p>x</p>")).isEmpty();
        }
    }

    @Test
    void anUnrestrictedPostKeepsEverythingButTheMarkers() {
        String html = "<p>前</p><!-- separator --><p>后</p><separator></separator><p>末</p>";
        assertThat(HiddenContent.visibleHtml(Map.of(), html)).isEqualTo("<p>前</p><p>后</p><p>末</p>");
        assertThat(HiddenContent.visibleHtml(Map.of("restrict.enabled", "false"), html))
            .isEqualTo("<p>前</p><p>后</p><p>末</p>");
        assertThat(HiddenContent.visibleHtml(Map.of("restrict.enabled", ""), "<p>x</p>")).isEqualTo("<p>x</p>");
        assertThat(HiddenContent.visibleHtml(null, "<p>x</p>")).isEqualTo("<p>x</p>");
    }

    @Test
    void textThatOnlyMentionsSeparatorsIsLeftAlone() {
        String html = "<p>用 separator 分割，或者写 &lt;hr&gt;</p>";
        assertThat(HiddenContent.visibleHtml(RESTRICTED, html + "<!-- separator --><p>x</p>")).isEqualTo(html);
        assertThat(HiddenContent.visibleHtml(Map.of(), html)).isEqualTo(html);
    }

    @Test
    void nullAndBlankHtmlStayEmpty() {
        assertThat(HiddenContent.visibleHtml(RESTRICTED, null)).isEmpty();
        assertThat(HiddenContent.visibleHtml(Map.of(), null)).isEmpty();
        assertThat(HiddenContent.visibleHtml(Map.of(), "")).isEmpty();
    }

    @Test
    void aMarkerInsideAnElementStillCutsAndTheFragmentStaysParseable() {
        String cut = HiddenContent.visibleHtml(RESTRICTED, "<div><p>前<!-- separator -->后</p></div>");
        assertThat(ContentText.document("T", cut)).isEqualTo("# T\n\n前");
    }
}
