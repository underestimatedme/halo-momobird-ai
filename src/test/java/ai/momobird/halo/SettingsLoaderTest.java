package ai.momobird.halo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.momobird.halo.core.Settings;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;
import run.halo.app.plugin.ReactiveSettingFetcher;

class SettingsLoaderTest {

    static final String KEY = "mbk_HALOLOCALTESTKEYHALOLOCALTESTKEYHALOLOCALTE";
    static final String SECRET = "secret-momobird";

    final ReactiveSettingFetcher fetcher = mock(ReactiveSettingFetcher.class);
    final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);

    static Secret secret(Map<String, String> stringData, Map<String, byte[]> data) {
        Secret secret = new Secret();
        Metadata metadata = new Metadata();
        metadata.setName(SECRET);
        secret.setMetadata(metadata);
        secret.setType(Secret.SECRET_TYPE_OPAQUE);
        secret.setStringData(stringData);
        secret.setData(data);
        return secret;
    }

    void connection(String baseUrl, String keySecret) {
        when(fetcher.fetch(eq("connection"), eq(SettingsLoader.Connection.class)))
            .thenReturn(Mono.just(new SettingsLoader.Connection(baseUrl, keySecret)));
    }

    void otherGroupsUnsaved() {
        when(fetcher.fetch(eq("widget"), eq(SettingsLoader.Widget.class))).thenReturn(Mono.empty());
        when(fetcher.fetch(eq("podcast"), eq(SettingsLoader.Podcast.class))).thenReturn(Mono.empty());
    }

    Settings load() {
        return new SettingsLoader(fetcher, client).load().block();
    }

    @Test
    void readsTheThreeGroupsAndTheKeyFromTheSecretTheFormNames() {
        connection("https://api.example.com/", SECRET);
        when(fetcher.fetch(eq("widget"), eq(SettingsLoader.Widget.class)))
            .thenReturn(Mono.just(new SettingsLoader.Widget(true, "left")));
        when(fetcher.fetch(eq("podcast"), eq(SettingsLoader.Podcast.class)))
            .thenReturn(Mono.just(new SettingsLoader.Podcast(false, true)));
        when(client.fetch(Secret.class, SECRET)).thenReturn(Mono.just(secret(Map.of("key", " " + KEY + "\n"), null)));

        Settings s = load();

        assertThat(s).isNotNull();
        assertThat(s.baseUrl()).isEqualTo("https://api.example.com");
        assertThat(s.key()).isEqualTo(KEY);
        assertThat(s.widgetEnabled()).isTrue();
        assertThat(s.widgetPosition()).isEqualTo("left");
        assertThat(s.podcastEnabled()).isFalse();
        assertThat(s.podcastTranscript()).isTrue();
    }

    @Test
    void readsTheBinaryDataWhenTheSecretHasNoStringData() {
        connection(null, SECRET);
        otherGroupsUnsaved();
        when(client.fetch(Secret.class, SECRET))
            .thenReturn(Mono.just(secret(null, Map.of("key", KEY.getBytes(StandardCharsets.UTF_8)))));

        assertThat(load().key()).isEqualTo(KEY);
    }

    @Test
    void noSecretChosenMeansNoKeyAndNoSecretIsRead() {
        connection("https://api.example.com", "  ");
        otherGroupsUnsaved();

        Settings s = load();

        assertThat(s.hasKey()).isFalse();
        verify(client, never()).fetch(eq(Secret.class), any(String.class));
    }

    @Test
    void aMissingSecretOrOneWithoutTheKeyEntryMeansNoKey() {
        connection(null, SECRET);
        otherGroupsUnsaved();
        when(client.fetch(Secret.class, SECRET)).thenReturn(Mono.empty());
        assertThat(load().hasKey()).isFalse();

        when(client.fetch(Secret.class, SECRET)).thenReturn(Mono.just(secret(Map.of("token", KEY), null)));
        assertThat(load().hasKey()).isFalse();
    }

    @Test
    void aFailingSecretReadMeansNoKeyNotAnError() {
        connection(null, SECRET);
        otherGroupsUnsaved();
        when(client.fetch(Secret.class, SECRET)).thenReturn(Mono.error(new IllegalStateException("boom " + KEY)));

        Settings s = load();

        assertThat(s).isNotNull();
        assertThat(s.hasKey()).isFalse();
    }

    @Test
    void aKeyTypedIntoTheConfigMapByHandIsIgnored() throws Exception {
        // The ConfigMap is readable by anyone with Plugin View; the key must never be taken from it.
        SettingsLoader.Connection c = new ObjectMapper().readValue(
            "{\"baseUrl\":\"https://a.test\",\"key\":\"" + KEY + "\"}", SettingsLoader.Connection.class);
        when(fetcher.fetch(eq("connection"), eq(SettingsLoader.Connection.class))).thenReturn(Mono.just(c));
        otherGroupsUnsaved();

        assertThat(load().hasKey()).isFalse();
    }

    @Test
    void anUnsavedFormGivesTheDefaults() {
        when(fetcher.fetch(any(String.class), any(Class.class))).thenReturn(Mono.empty());

        Settings s = load();

        assertThat(s).isEqualTo(Settings.of(null, null, null, null, null, null));
        assertThat(s.hasKey()).isFalse();
    }

    @Test
    void aBrokenGroupFallsBackToItsDefaultsWithoutFailingTheOthers() {
        connection("https://api.example.com", SECRET);
        when(fetcher.fetch(eq("widget"), eq(SettingsLoader.Widget.class)))
            .thenReturn(Mono.error(new IllegalStateException("bad value")));
        when(fetcher.fetch(eq("podcast"), eq(SettingsLoader.Podcast.class)))
            .thenReturn(Mono.just(new SettingsLoader.Podcast(true, true)));
        when(client.fetch(Secret.class, SECRET)).thenReturn(Mono.just(secret(Map.of("key", KEY), null)));

        Settings s = load();

        assertThat(s.key()).isEqualTo(KEY);
        assertThat(s.widgetEnabled()).isFalse();
        assertThat(s.podcastTranscript()).isTrue();
    }

    @Test
    void theFormValuesDeserializeFromStoredJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SettingsLoader.Connection c = mapper.readValue(
            "{\"baseUrl\":\"https://a.test\",\"keySecret\":\"" + SECRET + "\",\"unknown\":1}", SettingsLoader.Connection.class);
        SettingsLoader.Widget w = mapper.readValue("{\"enabled\":true,\"position\":\"left\"}", SettingsLoader.Widget.class);
        SettingsLoader.Podcast p = mapper.readValue("{\"player\":false,\"transcript\":true}", SettingsLoader.Podcast.class);

        assertThat(c.baseUrl()).isEqualTo("https://a.test");
        assertThat(c.keySecret()).isEqualTo(SECRET);
        assertThat(w.enabled()).isTrue();
        assertThat(w.position()).isEqualTo("left");
        assertThat(p.player()).isFalse();
        assertThat(p.transcript()).isTrue();
        assertThat(mapper.readValue("{}", SettingsLoader.Podcast.class).player()).isNull();
    }
}
