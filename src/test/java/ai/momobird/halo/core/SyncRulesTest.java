package ai.momobird.halo.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SyncRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-28T08:00:00Z");

    /** A published, public, ordinary post: the one that syncs. */
    private static PostView.PostViewBuilder post() {
        return PostView.builder()
            .name("post-1")
            .title("你好")
            .publish(true)
            .phase("PUBLISHED")
            .publishTime(NOW.minusSeconds(3600))
            .visible("PUBLIC")
            .permalink("/archives/hello");
    }

    private static Optional<String> why(PostView view) {
        return SyncRules.exclusion(view, NOW);
    }

    @Test
    void anOrdinaryPublishedPublicPostSyncs() {
        assertThat(why(post().build())).isEmpty();
    }

    @Test
    void aDraftIsNotPublished() {
        assertThat(why(post().publish(false).phase("DRAFT").build())).contains("未发布");
    }

    @Test
    void aPostBeingUnpublishedIsNotPublishedYet() {
        // spec.publish already false, the reconciler has not moved the phase
        assertThat(why(post().publish(false).build())).contains("未发布");
    }

    @Test
    void aPostWaitingForApprovalOrFailedIsNotPublished() {
        assertThat(why(post().phase("PENDING_APPROVAL").build())).contains("未发布");
        assertThat(why(post().phase("FAILED").build())).contains("未发布");
        assertThat(why(post().phase(null).build())).contains("未发布");
    }

    @Test
    void aScheduledPostIsHeldBackUntilItsTime() {
        PostView scheduled = post().phase("DRAFT").publishTime(NOW.plusSeconds(60)).build();
        assertThat(why(scheduled)).contains("定时发布未到时间");
    }

    @Test
    void aScheduledPostIsNotScheduledOnceItsTimeHasPassedButItStillWaitsForHalo() {
        // Halo has not published it yet: not a schedule any more, but not published either
        PostView late = post().phase("DRAFT").publishTime(NOW.minusSeconds(1)).build();
        assertThat(why(late)).contains("未发布");
    }

    @Test
    void aPublishedPostWithAFuturePublishTimeIsStillShownByHaloSoItSyncs() {
        // the author moved the time of an already published post; Halo keeps serving it
        assertThat(why(post().publishTime(NOW.plusSeconds(3600)).build())).isEmpty();
    }

    @Test
    void aDraftWithoutPublishTimeIsNotPublished() {
        assertThat(why(post().publish(false).phase("DRAFT").publishTime(null).build())).contains("未发布");
    }

    @Test
    void internalAndPrivatePostsAreNotPublic() {
        assertThat(why(post().visible("INTERNAL").build())).contains("不是公开可见");
        assertThat(why(post().visible("PRIVATE").build())).contains("不是公开可见");
        assertThat(why(post().visible("private").build())).contains("不是公开可见");
    }

    @Test
    void aMissingVisibilityMeansPublicAsInHalo() {
        assertThat(why(post().visible(null).build())).isEmpty();
    }

    @Test
    void anUnknownVisibilityFailsClosed() {
        assertThat(why(post().visible("SOMETHING_NEW").build())).contains("不是公开可见");
    }

    @Test
    void aTrashedPostIsInTheTrashWhateverElseIsTrue() {
        assertThat(why(post().deleted(true).build())).contains("在回收站");
        // trashing also unpublishes it; the trash is the more useful reason
        assertThat(why(post().deleted(true).publish(false).phase("DRAFT").build())).contains("在回收站");
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "True", " true ", "\ttrue\n", "yes", "YES", "on", "On", "1", " 1 "})
    void theExcludeMetadataExcludesForTheTrueWords(String value) {
        PostView excluded = post().annotations(Map.of("momobird.ai/exclude", value)).build();
        assertThat(why(excluded)).contains("已排除（momobird.ai/exclude）");
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "FALSE", "no", "off", "0", "", "  ", "maybe", "2", "truee"})
    void anyOtherExcludeValueDoesNotExclude(String value) {
        PostView kept = post().annotations(Map.of("momobird.ai/exclude", value)).build();
        assertThat(why(kept)).isEmpty();
    }

    @Test
    void otherAnnotationsAndNullAnnotationsDoNotExclude() {
        assertThat(why(post().annotations(Map.of("momobird.ai/other", "true")).build())).isEmpty();
        assertThat(why(post().annotations(null).build())).isEmpty();
    }

    @Test
    void theFirstRuleThatAppliesWins() {
        PostView everything = post().deleted(true).publish(false).phase("DRAFT").visible("PRIVATE")
            .annotations(Map.of("momobird.ai/exclude", "true")).build();
        assertThat(why(everything)).contains("在回收站");
        assertThat(why(post().visible("PRIVATE").annotations(Map.of("momobird.ai/exclude", "true")).build()))
            .contains("不是公开可见");
    }

    @Test
    void exclusionWithoutAClockUsesNow() {
        PostView far = post().phase("DRAFT").publishTime(Instant.now().plusSeconds(86_400)).build();
        assertThat(SyncRules.exclusion(far)).contains("定时发布未到时间");
        assertThat(SyncRules.exclusion(post().build())).isEmpty();
    }

    // ---- origin ----

    @Test
    void sameOriginPasses() {
        OriginCheck check = SyncRules.origin("https://blog.example.com", "https://blog.example.com");
        assertThat(check.ok()).isTrue();
        assertThat(check.siteOrigin()).isEqualTo("https://blog.example.com");
        assertThat(check.sourceOrigin()).isEqualTo("https://blog.example.com");
        assertThat(check.reason()).isNull();
    }

    @Test
    void pathsTrailingSlashesAndCaseDoNotMatter() {
        assertThat(SyncRules.origin("https://Blog.Example.com/", "https://blog.example.com").ok()).isTrue();
        assertThat(SyncRules.origin("https://blog.example.com/halo/", "https://blog.example.com//").ok()).isTrue();
        assertThat(SyncRules.origin("  https://blog.example.com  ", "https://blog.example.com/x?y=1#z").ok()).isTrue();
        assertThat(SyncRules.origin("HTTPS://blog.example.com", "https://blog.example.com").ok()).isTrue();
    }

    @Test
    void wwwIsADifferentOrigin() {
        OriginCheck check = SyncRules.origin("https://www.example.com", "https://example.com");
        assertThat(check.ok()).isFalse();
        assertThat(check.siteOrigin()).isEqualTo("https://www.example.com");
        assertThat(check.sourceOrigin()).isEqualTo("https://example.com");
        assertThat(check.reason()).contains("https://www.example.com").contains("https://example.com");
    }

    @Test
    void httpAndHttpsAreDifferentOrigins() {
        assertThat(SyncRules.origin("http://blog.example.com", "https://blog.example.com").ok()).isFalse();
    }

    @Test
    void portsMatterButDefaultPortsAreImplied() {
        assertThat(SyncRules.origin("https://blog.example.com:8443", "https://blog.example.com").ok()).isFalse();
        assertThat(SyncRules.origin("https://blog.example.com:8443", "https://blog.example.com:8443/").ok()).isTrue();
        assertThat(SyncRules.origin("https://blog.example.com:443", "https://blog.example.com").ok()).isTrue();
        assertThat(SyncRules.origin("http://blog.example.com:80", "http://blog.example.com").ok()).isTrue();
        assertThat(SyncRules.origin("http://blog.example.com:443", "http://blog.example.com").ok()).isFalse();
        assertThat(SyncRules.origin("http://localhost:8090", "http://localhost:8090").ok()).isTrue();
    }

    @Test
    void anUnsetExternalUrlNeverPasses() {
        for (String empty : new String[] {null, "", "   "}) {
            OriginCheck check = SyncRules.origin(empty, "https://blog.example.com");
            assertThat(check.ok()).isFalse();
            assertThat(check.siteOrigin()).isNull();
            assertThat(check.sourceOrigin()).isEqualTo("https://blog.example.com");
            assertThat(check.reason()).contains("系统设置 → 基本设置 → 外部访问地址").contains("halo.external-url");
        }
        // two unset addresses are not "the same"
        assertThat(SyncRules.origin(null, null).ok()).isFalse();
        assertThat(SyncRules.origin("", "").ok()).isFalse();
    }

    @Test
    void anUnusableAddressNeverPasses() {
        assertThat(SyncRules.origin("not a url", "not a url").ok()).isFalse();
        assertThat(SyncRules.origin("ftp://blog.example.com", "ftp://blog.example.com").ok()).isFalse();
        assertThat(SyncRules.origin("blog.example.com", "blog.example.com").ok()).isFalse();
        assertThat(SyncRules.origin("https://blog.example.com", null).ok()).isFalse();
        assertThat(SyncRules.origin("https://blog.example.com", "").reason()).contains("来源");
    }

    @Test
    void internationalHostsCompareInTheirAsciiForm() {
        assertThat(SyncRules.origin("https://博客.例子.cn", "https://xn--9krq6q.xn--fsqu00a.cn/").ok()).isTrue();
    }
}
