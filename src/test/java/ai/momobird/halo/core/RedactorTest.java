package ai.momobird.halo.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RedactorTest {

    static final String KEY = "mbk_HALOLOCALTESTKEYHALOLOCALTESTKEYHALOLOCALTE";

    @Test
    void removesTheKeyAndEverythingShapedLikeOne() {
        assertThat(Redactor.clean("Authorization: Bearer " + KEY + " rejected", KEY))
            .isEqualTo("Authorization: Bearer *** rejected");
        assertThat(Redactor.clean("other key mbk_live_abc-DEF_123 here", KEY))
            .isEqualTo("other key *** here");
        assertThat(Redactor.clean("twice " + KEY + " and " + KEY, KEY)).doesNotContain("mbk_");
    }

    @Test
    void removesMbkStringsEvenWithoutAKey() {
        assertThat(Redactor.clean("x mbk_secret y", null)).isEqualTo("x *** y");
        assertThat(Redactor.clean("x mbk_secret y", "")).isEqualTo("x *** y");
    }

    @Test
    void leavesTheBarePrefixInAnExplanation() {
        String hint = "连接密钥格式不对：应以 mbk_ 开头，只含字母、数字、下划线和短横线";
        assertThat(Redactor.clean(hint, null)).isEqualTo(hint);
        assertThat(Redactor.clean("prefix mbk_", null)).isEqualTo("prefix mbk_");
        // one character after the prefix is already a token
        assertThat(Redactor.clean("x mbk_a y", null)).isEqualTo("x *** y");
        assertThat(Redactor.clean("x mbk__ y", null)).isEqualTo("x *** y");
        assertThat(Redactor.clean("x mbk_-abc y", null)).isEqualTo("x *** y");
    }

    @Test
    void theSettingsKeyHintSurvivesRedaction() {
        Settings wrong = Settings.of("https://api.example.com", "abc123secret", false, null, null, null);
        String problem = wrong.validate().get(0);
        assertThat(Redactor.clean(problem, "abc123secret")).isEqualTo(problem).contains("mbk_");
    }

    @Test
    void removesAKeyThatDoesNotStartWithMbk() {
        assertThat(Redactor.clean("token=abc.def+ghi", "abc.def+ghi")).isEqualTo("token=***");
    }

    @Test
    void leavesOrdinaryTextAndHandlesNull() {
        assertThat(Redactor.clean("connect ECONNREFUSED 127.0.0.1:9", KEY))
            .isEqualTo("connect ECONNREFUSED 127.0.0.1:9");
        assertThat(Redactor.clean(null, KEY)).isEmpty();
    }
}
