package ai.momobird.halo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.momobird.halo.core.Settings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

class SettingsLoaderTest {

    static final String KEY = "mbk_HALOLOCALTESTKEYHALOLOCALTESTKEYHALOLOCALTE";

    @Test
    void readsTheThreeGroupsFromThePluginSettings() {
        ReactiveSettingFetcher fetcher = mock(ReactiveSettingFetcher.class);
        when(fetcher.fetch(eq("connection"), eq(SettingsLoader.Connection.class)))
            .thenReturn(Mono.just(new SettingsLoader.Connection("https://api.example.com/", KEY)));
        when(fetcher.fetch(eq("widget"), eq(SettingsLoader.Widget.class)))
            .thenReturn(Mono.just(new SettingsLoader.Widget(true, "left")));
        when(fetcher.fetch(eq("podcast"), eq(SettingsLoader.Podcast.class)))
            .thenReturn(Mono.just(new SettingsLoader.Podcast(false, true)));

        Settings s = new SettingsLoader(fetcher).load().block();

        assertThat(s).isNotNull();
        assertThat(s.baseUrl()).isEqualTo("https://api.example.com");
        assertThat(s.key()).isEqualTo(KEY);
        assertThat(s.widgetEnabled()).isTrue();
        assertThat(s.widgetPosition()).isEqualTo("left");
        assertThat(s.podcastEnabled()).isFalse();
        assertThat(s.podcastTranscript()).isTrue();
    }

    @Test
    void anUnsavedFormGivesTheDefaults() {
        ReactiveSettingFetcher fetcher = mock(ReactiveSettingFetcher.class);
        when(fetcher.fetch(any(String.class), any(Class.class))).thenReturn(Mono.empty());

        Settings s = new SettingsLoader(fetcher).load().block();

        assertThat(s).isEqualTo(Settings.of(null, null, null, null, null, null));
        assertThat(s.hasKey()).isFalse();
    }

    @Test
    void aBrokenGroupFallsBackToItsDefaultsWithoutFailingTheOthers() {
        ReactiveSettingFetcher fetcher = mock(ReactiveSettingFetcher.class);
        when(fetcher.fetch(eq("connection"), eq(SettingsLoader.Connection.class)))
            .thenReturn(Mono.just(new SettingsLoader.Connection("https://api.example.com", KEY)));
        when(fetcher.fetch(eq("widget"), eq(SettingsLoader.Widget.class)))
            .thenReturn(Mono.error(new IllegalStateException("bad value")));
        when(fetcher.fetch(eq("podcast"), eq(SettingsLoader.Podcast.class)))
            .thenReturn(Mono.just(new SettingsLoader.Podcast(true, true)));

        Settings s = new SettingsLoader(fetcher).load().block();

        assertThat(s.key()).isEqualTo(KEY);
        assertThat(s.widgetEnabled()).isFalse();
        assertThat(s.podcastTranscript()).isTrue();
    }

    @Test
    void theFormValuesDeserializeFromStoredJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SettingsLoader.Connection c = mapper.readValue(
            "{\"baseUrl\":\"https://a.test\",\"key\":\"" + KEY + "\",\"unknown\":1}", SettingsLoader.Connection.class);
        SettingsLoader.Widget w = mapper.readValue("{\"enabled\":true,\"position\":\"left\"}", SettingsLoader.Widget.class);
        SettingsLoader.Podcast p = mapper.readValue("{\"player\":false,\"transcript\":true}", SettingsLoader.Podcast.class);

        assertThat(c.baseUrl()).isEqualTo("https://a.test");
        assertThat(c.key()).isEqualTo(KEY);
        assertThat(w.enabled()).isTrue();
        assertThat(w.position()).isEqualTo("left");
        assertThat(p.player()).isFalse();
        assertThat(p.transcript()).isTrue();
        assertThat(mapper.readValue("{}", SettingsLoader.Podcast.class).player()).isNull();
    }

    @Test
    void theConnectionToStringDoesNotShowTheKey() {
        assertThat(new SettingsLoader.Connection("https://a.test", KEY).toString()).doesNotContain(KEY);
    }
}
