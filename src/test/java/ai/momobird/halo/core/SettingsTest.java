package ai.momobird.halo.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SettingsTest {

    static final String KEY = RedactorTest.KEY;

    static Settings withUrl(String baseUrl) {
        return new Settings(baseUrl, KEY, false, "right", true, false);
    }

    @Test
    void defaultsMatchTheSpec() {
        Settings s = Settings.of(null, null, null, null, null, null);
        assertThat(s.baseUrl()).isEqualTo("https://api.valley.atlaspaces.com");
        assertThat(s.key()).isEmpty();
        assertThat(s.widgetEnabled()).isFalse();
        assertThat(s.widgetPosition()).isEqualTo("right");
        assertThat(s.podcastEnabled()).isTrue();
        assertThat(s.podcastTranscript()).isFalse();
    }

    @Test
    void normalizesValues() {
        Settings s = Settings.of("  https://api.example.com/  ", "  " + KEY + "\n", true, "LEFT", false, true);
        assertThat(s.baseUrl()).isEqualTo("https://api.example.com");
        assertThat(s.key()).isEqualTo(KEY);
        assertThat(s.widgetEnabled()).isTrue();
        assertThat(s.widgetPosition()).isEqualTo("left");
        assertThat(s.podcastEnabled()).isFalse();
        assertThat(s.podcastTranscript()).isTrue();

        assertThat(Settings.of("   ", KEY, null, "sideways", null, null).baseUrl())
            .isEqualTo("https://api.valley.atlaspaces.com");
        assertThat(Settings.of(null, KEY, null, "sideways", null, null).widgetPosition()).isEqualTo("right");
    }

    @Test
    void aValidConfigurationHasNoProblems() {
        assertThat(withUrl("https://api.valley.atlaspaces.com").validate()).isEmpty();
        assertThat(withUrl("https://api.example.com:8443").validate()).isEmpty();
    }

    @Test
    void localHostsMayUsePlainHttp() {
        for (String url : List.of("http://localhost:8080", "http://127.0.0.1:9", "http://[::1]:8080",
            "http://host.docker.internal:18080", "http://LOCALHOST")) {
            assertThat(withUrl(url).validate()).as(url).isEmpty();
        }
    }

    @Test
    void otherHostsMustUseHttps() {
        for (String url : List.of("http://api.example.com", "http://localhost.evil.com", "http://127.0.0.1.evil.com",
            "http://192.168.1.5", "http://api.valley.atlaspaces.com")) {
            assertThat(withUrl(url).validate()).as(url).hasSize(1);
            assertThat(withUrl(url).validate().get(0)).contains("HTTPS");
        }
    }

    @Test
    void rejectsMalformedAddresses() {
        for (String url : List.of("ftp://api.example.com", "api.example.com", "https://", "https://a b.com",
            "https://user:pw@api.example.com", "https://api.example.com/v1", "https://api.example.com?x=1",
            "https://api.example.com#top", "javascript:alert(1)")) {
            assertThat(withUrl(url).validate()).as(url).isNotEmpty();
        }
    }

    @Test
    void addressMessagesNeverEchoTheAddress() {
        for (String problem : withUrl("https://user:secret@api.example.com").validate()) {
            assertThat(problem).doesNotContain("secret").doesNotContain("user:");
        }
    }

    @Test
    void theKeyIsRequiredAndMustStartWithMbk() {
        Settings missing = Settings.of(null, "", null, null, null, null);
        assertThat(missing.validate()).hasSize(1);
        assertThat(missing.validate().get(0)).contains("密钥");

        Settings wrong = Settings.of(null, "abc123secret", null, null, null, null);
        assertThat(wrong.validate()).hasSize(1);
        assertThat(wrong.validate().get(0)).contains("mbk_").doesNotContain("abc123secret");
    }

    @Test
    void reportsEveryProblemAtOnce() {
        assertThat(Settings.of("http://api.example.com", "nope", null, null, null, null).validate()).hasSize(2);
    }

    @Test
    void hasKeyReflectsConfiguration() {
        assertThat(Settings.of(null, "", null, null, null, null).hasKey()).isFalse();
        assertThat(Settings.of(null, KEY, null, null, null, null).hasKey()).isTrue();
    }

    @Test
    void toStringNeverContainsTheKey() {
        assertThat(withUrl("https://api.example.com").toString()).doesNotContain(KEY).doesNotContain("mbk_");
    }
}
