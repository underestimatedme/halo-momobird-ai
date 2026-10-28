package ai.momobird.halo;

import ai.momobird.halo.core.Settings;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * Reads the plugin's setting form (see {@code extensions/settings.yaml}) through Halo's
 * {@link ReactiveSettingFetcher} and turns it into the immutable {@link Settings}.
 *
 * <p>Only {@code fetch(group, Class)} is used: it exists unchanged from Halo 2.17 on and does not
 * expose any JsonNode type, which changed packages in later Halo versions. A group that was never
 * saved, or one holding values of the wrong type, falls back to its defaults.
 */
@Component
public class SettingsLoader {

    public static final String CONNECTION = "connection";
    public static final String WIDGET = "widget";
    public static final String PODCAST = "podcast";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Connection(String baseUrl, String key) {
        @Override
        public String toString() {
            return "Connection[baseUrl=" + baseUrl + ", key=***]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Widget(Boolean enabled, String position) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Podcast(Boolean player, Boolean transcript) {
    }

    private final ReactiveSettingFetcher fetcher;

    public SettingsLoader(ReactiveSettingFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The current settings. Never empty and never an error: the form is read fresh on every call. */
    public Mono<Settings> load() {
        Mono<Connection> connection = group(CONNECTION, Connection.class, new Connection(null, null));
        Mono<Widget> widget = group(WIDGET, Widget.class, new Widget(null, null));
        Mono<Podcast> podcast = group(PODCAST, Podcast.class, new Podcast(null, null));
        return Mono.zip(connection, widget, podcast).map(t -> Settings.of(
            t.getT1().baseUrl(), t.getT1().key(),
            t.getT2().enabled(), t.getT2().position(),
            t.getT3().player(), t.getT3().transcript()));
    }

    private <T> Mono<T> group(String name, Class<T> type, T fallback) {
        return fetcher.fetch(name, type)
            .onErrorResume(e -> Mono.empty())
            .defaultIfEmpty(fallback);
    }
}
