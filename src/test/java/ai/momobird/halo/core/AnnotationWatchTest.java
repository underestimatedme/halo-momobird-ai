package ai.momobird.halo.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnnotationWatchTest {

    static final String EXCLUDE = SyncRules.EXCLUDE_ANNOTATION;
    static final String RESTRICT = HiddenContent.RESTRICT_ENABLED;

    final AnnotationWatch watch = new AnnotationWatch();

    @Test
    void aChangeOfTheExcludeMarkCountsBothWays() {
        watch.record("p1", Map.of());
        assertThat(watch.changed("p1", Map.of(EXCLUDE, "true"))).isTrue();
        assertThat(watch.changed("p1", Map.of(EXCLUDE, "true"))).isFalse();
        assertThat(watch.changed("p1", Map.of())).isTrue();
        assertThat(watch.changed("p1", Map.of())).isFalse();
    }

    @Test
    void aChangeOfTheRestrictedReadingMarkCounts() {
        watch.record("p1", Map.of(RESTRICT, "false"));
        assertThat(watch.changed("p1", Map.of(RESTRICT, "true"))).isTrue();
        assertThat(watch.changed("p1", Map.of(RESTRICT, "false"))).isTrue();
        assertThat(watch.changed("p1", Map.of())).isTrue();
    }

    @Test
    void otherAnnotationsAreNoise() {
        watch.record("p1", Map.of(EXCLUDE, "true"));
        Map<String, String> noisy = new HashMap<>(Map.of(EXCLUDE, "true"));
        noisy.put("content.halo.run/last-released-snapshot", "snap-2");
        noisy.put("checksum/config", "abc");
        assertThat(watch.changed("p1", noisy)).isFalse();
        assertThat(watch.changed("p1", null)).isTrue();
    }

    @Test
    void theFirstSightingOnlyCountsWhenAMarkIsThere() {
        // Nothing known about these posts (the engine has not read them since the plugin started).
        assertThat(watch.changed("plain", Map.of("other", "x"))).isFalse();
        assertThat(watch.changed("plain", Map.of("other", "y"))).isFalse();
        // A mark on a post seen for the first time may have just been added: let the engine decide.
        assertThat(watch.changed("marked", Map.of(EXCLUDE, "true"))).isTrue();
        assertThat(watch.changed("marked", Map.of(EXCLUDE, "true"))).isFalse();
    }

    @Test
    void whatTheEngineReadIsTheBaseline() {
        watch.record("p1", Map.of(EXCLUDE, "true"));
        // The engine has already acted on this state: nothing to do.
        assertThat(watch.changed("p1", Map.of(EXCLUDE, "true"))).isFalse();
        watch.record("p1", Map.of());
        assertThat(watch.changed("p1", Map.of())).isFalse();
    }

    @Test
    void forgettingAndClearing() {
        watch.record("p1", Map.of());
        watch.record("p2", Map.of());
        watch.forget("p1");
        assertThat(watch.changed("p1", Map.of())).isFalse();
        assertThat(watch.size()).isEqualTo(2);
        watch.clear();
        assertThat(watch.size()).isZero();
        watch.forget(null);
        watch.record(null, Map.of());
        assertThat(watch.changed(null, Map.of(EXCLUDE, "true"))).isFalse();
    }
}
