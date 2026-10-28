package ai.momobird.halo;

import ai.momobird.halo.core.Settings;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * Reads the plugin's setting form (see {@code extensions/settings.yaml}) through Halo's
 * {@link ReactiveSettingFetcher} and turns it into the immutable {@link Settings}.
 *
 * <p>The connection key is not in the form. Halo lets anyone with Plugin View read a plugin's
 * settings ConfigMap, so the form only holds the name of a Halo {@link Secret} (the {@code secret}
 * input), and the key is the Secret's {@link #SECRET_KEY} entry. No role template Halo ships grants
 * Secrets: only super administrators can read or write them through Halo's API. The plugin reads
 * the Secret server side with the extension client, which has no caller to check.
 *
 * <p>Only {@code fetch(group, Class)} is used: it exists unchanged from Halo 2.17 on and does not
 * expose any JsonNode type, which changed packages in later Halo versions. A group that was never
 * saved, or one holding values of the wrong type, falls back to its defaults. A Secret that is not
 * chosen, missing, unreadable or without the entry gives no key; nothing here fails.
 */
@Component
public class SettingsLoader {

    public static final String CONNECTION = "connection";
    public static final String WIDGET = "widget";
    public static final String PODCAST = "podcast";
    /** The entry of the Secret that holds the connection key. */
    public static final String SECRET_KEY = "key";

    /** The connection group: the address, and the name of the Secret holding the key (not the key). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Connection(String baseUrl, String keySecret) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Widget(Boolean enabled, String position) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Podcast(Boolean player, Boolean transcript) {
    }

    private final ReactiveSettingFetcher fetcher;
    private final ReactiveExtensionClient client;

    public SettingsLoader(ReactiveSettingFetcher fetcher, ReactiveExtensionClient client) {
        this.fetcher = fetcher;
        this.client = client;
    }

    /** The current settings. Never empty and never an error: the form and the Secret are read fresh on every call. */
    public Mono<Settings> load() {
        Mono<Connection> connection = group(CONNECTION, Connection.class, new Connection(null, null));
        Mono<Widget> widget = group(WIDGET, Widget.class, new Widget(null, null));
        Mono<Podcast> podcast = group(PODCAST, Podcast.class, new Podcast(null, null));
        return Mono.zip(connection, widget, podcast).flatMap(t -> key(t.getT1().keySecret()).map(key -> Settings.of(
            t.getT1().baseUrl(), key,
            t.getT2().enabled(), t.getT2().position(),
            t.getT3().player(), t.getT3().transcript())));
    }

    /** The key held by the named Secret, or empty text. */
    private Mono<String> key(String secretName) {
        if (secretName == null || secretName.isBlank()) {
            return Mono.just("");
        }
        return client.fetch(Secret.class, secretName.strip())
            .map(SettingsLoader::keyOf)
            .onErrorResume(e -> Mono.empty())
            .defaultIfEmpty("");
    }

    private static String keyOf(Secret secret) {
        Map<String, String> strings = secret.getStringData();
        if (strings != null && strings.get(SECRET_KEY) != null) {
            return strings.get(SECRET_KEY);
        }
        Map<String, byte[]> data = secret.getData();
        if (data != null && data.get(SECRET_KEY) != null) {
            return new String(data.get(SECRET_KEY), StandardCharsets.UTF_8);
        }
        return "";
    }

    private <T> Mono<T> group(String name, Class<T> type, T fallback) {
        return fetcher.fetch(name, type)
            .onErrorResume(e -> Mono.empty())
            .defaultIfEmpty(fallback);
    }
}
