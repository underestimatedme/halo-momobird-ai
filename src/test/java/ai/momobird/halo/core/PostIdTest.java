package ai.momobird.halo.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import org.junit.jupiter.api.Test;

/**
 * The vectors are copied from hexo-momobird-ai test/post-id.test.js so both plugins
 * give the same post the same numeric id.
 */
class PostIdTest {

    @Test
    void matchesTheKnownVectors() {
        assertThat(PostId.of("_posts/hello-world.md")).isEqualTo("2771473582013371");
        assertThat(PostId.of("_posts/中文 文章.md")).isEqualTo("3673941822749037");
    }

    @Test
    void matchesTheForwardSlashVectorOfTheBackslashCase() {
        // The Hexo test also feeds '_posts\\a\\b.md' (backslashes become '/' there because its
        // input is a file path). Halo's input is metadata.name, so that vector is skipped here
        // and only the forward-slash spelling, which gives the same number, is kept.
        assertThat(PostId.of("_posts/a/b.md")).isEqualTo("3429000370032694");
    }

    @Test
    void isAlwaysOneToSixteenDecimalDigits() {
        String[] inputs = {
            "",
            "a",
            "_posts/hello-world.md",
            "_posts/中文 文章.md",
            "_posts/2026/10/very/deep/path/with-a-long-name-that-goes-on-and-on.md",
            "🚀.md"
        };
        for (String input : inputs) {
            assertThat(PostId.of(input)).as(input).matches("[0-9]{1,16}");
        }
    }

    @Test
    void isTheSameForNfcAndNfdSpellings() {
        String nfc = "_posts/café.md";
        String nfd = "_posts/café.md";
        assertThat(nfc).isNotEqualTo(nfd);
        assertThat(PostId.of(nfd)).isEqualTo(PostId.of(nfc));

        String hangulNfc = "_posts/한글.md";
        String hangulNfd = Normalizer.normalize(hangulNfc, Normalizer.Form.NFD);
        assertThat(hangulNfc).isNotEqualTo(hangulNfd);
        assertThat(PostId.of(hangulNfd)).isEqualTo(PostId.of(hangulNfc));
    }
}
