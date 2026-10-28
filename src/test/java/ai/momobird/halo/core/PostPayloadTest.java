package ai.momobird.halo.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PostPayloadTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SITE = "https://blog.example.com";

    private static PostView.PostViewBuilder view() {
        return PostView.builder()
            .name("0a1b2c3d-post")
            .title("你好，世界")
            .publish(true)
            .phase("PUBLISHED")
            .visible("PUBLIC")
            .permalink("/archives/hello")
            .publishTime(Instant.parse("2026-10-01T00:00:00Z"))
            .lastModified(Instant.parse("2026-10-02T00:00:00Z"));
    }

    private static PostPayload payload(PostView view, String html, String category) {
        return PostPayload.of(view, html, category, SITE).orElseThrow();
    }

    @Test
    void mapsTheFieldsConnectExpects() {
        PostPayload p = payload(view().build(), "<h2>小节</h2><p>正文</p>", "技术");

        assertThat(p.externalId()).isEqualTo(PostId.of("0a1b2c3d-post"));
        assertThat(p.title()).isEqualTo("你好，世界");
        assertThat(p.url()).isEqualTo("https://blog.example.com/archives/hello");
        assertThat(p.content()).isEqualTo("# 你好，世界\n\n## 小节\n\n正文");
        assertThat(p.category()).isEqualTo("技术");
        assertThat(p.modifiedAt()).isEqualTo("2026-10-02T00:00:00.000Z");
    }

    @Test
    void serializesWithConnectsFieldNames() throws Exception {
        JsonNode json = JSON.readTree(JSON.writeValueAsString(payload(view().build(), "<p>正文</p>", "技术")));

        assertThat(json.fieldNames()).toIterable()
            .containsExactlyInAnyOrder("external_id", "title", "content", "url", "category", "modified_at");
        assertThat(json.get("external_id").asText()).isEqualTo(PostId.of("0a1b2c3d-post"));
        assertThat(json.get("modified_at").asText()).isEqualTo("2026-10-02T00:00:00.000Z");
    }

    @Test
    void aMissingCategoryIsNotSent() throws Exception {
        for (String none : new String[] {null, "", "   "}) {
            PostPayload p = payload(view().build(), "<p>正文</p>", none);
            assertThat(p.category()).isNull();
            JsonNode json = JSON.readTree(JSON.writeValueAsString(p));
            assertThat(json.has("category")).isFalse();
        }
    }

    @Test
    void theCategoryIsCutAtSixtyFourCharactersWithoutSplittingPairs() {
        String long70 = "分".repeat(70);
        assertThat(payload(view().build(), "<p>正文</p>", long70).category()).isEqualTo("分".repeat(64));
        String emoji = "😀".repeat(70);
        assertThat(payload(view().build(), "<p>正文</p>", emoji).category()).isEqualTo("😀".repeat(64));
    }

    @Test
    void theTitleIsCutAtTwoHundredFiftyFiveCharacters() {
        PostPayload p = payload(view().title("题".repeat(300)).build(), "<p>正文</p>", null);
        assertThat(p.title()).isEqualTo("题".repeat(255));
    }

    @Test
    void aBlankTitleFallsBackToThePostName() {
        assertThat(payload(view().title("  ").build(), "<p>正文</p>", null).title()).isEqualTo("0a1b2c3d-post");
        assertThat(payload(view().title(null).build(), "<p>正文</p>", null).title()).isEqualTo("0a1b2c3d-post");
    }

    // ---- url ----

    @Test
    void anAbsolutePermalinkIsNotPrefixedAgain() {
        PostPayload p = payload(view().permalink("https://blog.example.com/archives/hello").build(), "<p>x</p>", null);
        assertThat(p.url()).isEqualTo("https://blog.example.com/archives/hello");
        PostPayload q = payload(view().permalink("HTTP://other.example.org/a").build(), "<p>x</p>", null);
        assertThat(q.url()).isEqualTo("HTTP://other.example.org/a");
    }

    @Test
    void slashesBetweenTheOriginAndThePermalinkAreNormalized() {
        assertThat(PostPayload.of(view().build(), "<p>x</p>", null, SITE + "/").orElseThrow().url())
            .isEqualTo("https://blog.example.com/archives/hello");
        assertThat(PostPayload.of(view().permalink("archives/hello").build(), "<p>x</p>", null, SITE).orElseThrow().url())
            .isEqualTo("https://blog.example.com/archives/hello");
        assertThat(PostPayload.of(view().permalink("//archives/hello").build(), "<p>x</p>", null, SITE + "//")
            .orElseThrow().url()).isEqualTo("https://blog.example.com/archives/hello");
    }

    @Test
    void percentEncodedPermalinksAreKeptAsIs() {
        PostPayload p = payload(view().permalink("/archives/%E4%BD%A0%E5%A5%BD").build(), "<p>x</p>", null);
        assertThat(p.url()).isEqualTo("https://blog.example.com/archives/%E4%BD%A0%E5%A5%BD");
    }

    @Test
    void withoutAPermalinkOrASiteThereIsNothingToPush() {
        assertThat(PostPayload.of(view().permalink(null).build(), "<p>x</p>", null, SITE)).isEmpty();
        assertThat(PostPayload.of(view().permalink("  ").build(), "<p>x</p>", null, SITE)).isEmpty();
        assertThat(PostPayload.of(view().build(), "<p>x</p>", null, null)).isEmpty();
        assertThat(PostPayload.of(view().build(), "<p>x</p>", null, " ")).isEmpty();
    }

    @Test
    void aPostWithNoTextIsSkippedLikeInHexo() {
        assertThat(PostPayload.of(view().build(), "", null, SITE)).isEqualTo(Optional.empty());
        assertThat(PostPayload.of(view().build(), null, null, SITE)).isEmpty();
        assertThat(PostPayload.of(view().build(), "<script>x()</script><p> </p>", null, SITE)).isEmpty();
    }

    // ---- modified_at ----

    @Test
    void modifiedAtIsTheLaterOfPublishTimeAndLastModified() {
        PostView later = view().publishTime(Instant.parse("2026-10-05T10:00:00.123Z"))
            .lastModified(Instant.parse("2026-10-02T00:00:00Z")).build();
        assertThat(payload(later, "<p>x</p>", null).modifiedAt()).isEqualTo("2026-10-05T10:00:00.123Z");

        PostView edited = view().publishTime(Instant.parse("2026-10-01T00:00:00Z"))
            .lastModified(Instant.parse("2026-10-09T23:59:59.999Z")).build();
        assertThat(payload(edited, "<p>x</p>", null).modifiedAt()).isEqualTo("2026-10-09T23:59:59.999Z");
    }

    @Test
    void modifiedAtUsesWhicheverTimeExists() {
        assertThat(payload(view().lastModified(null).build(), "<p>x</p>", null).modifiedAt())
            .isEqualTo("2026-10-01T00:00:00.000Z");
        assertThat(payload(view().publishTime(null).build(), "<p>x</p>", null).modifiedAt())
            .isEqualTo("2026-10-02T00:00:00.000Z");
    }

    @Test
    void modifiedAtIsLeftOutWhenHaloGaveNoTime() throws Exception {
        PostPayload p = payload(view().publishTime(null).lastModified(null).build(), "<p>x</p>", null);
        assertThat(p.modifiedAt()).isNull();
        assertThat(JSON.readTree(JSON.writeValueAsString(p)).has("modified_at")).isFalse();
    }

    @Test
    void modifiedAtIsAlwaysUtcWithMilliseconds() {
        assertThat(payload(view().lastModified(Instant.parse("2026-10-09T01:02:03Z")).publishTime(null).build(),
            "<p>x</p>", null).modifiedAt()).isEqualTo("2026-10-09T01:02:03.000Z");
        assertThat(payload(view().lastModified(Instant.parse("2026-10-09T01:02:03.4567891Z")).publishTime(null)
            .build(), "<p>x</p>", null).modifiedAt()).isEqualTo("2026-10-09T01:02:03.456Z");
    }

    // ---- hidden content ----

    @Test
    void textBehindAReadingRestrictionNeverLeavesHalo() {
        PostView restricted = view().annotations(java.util.Map.of("restrict.enabled", "true")).build();
        String html = "<p>公开的前言</p><!-- separator --><p>会员内容</p>";

        PostPayload p = payload(restricted, html, null);

        assertThat(p.content()).isEqualTo("# 你好，世界\n\n公开的前言");
        assertThat(p.content()).doesNotContain("会员内容");
        // locked entirely: nothing public to index
        assertThat(PostPayload.of(restricted, "<p>整篇锁着</p>", null, SITE)).isEmpty();
    }
}
