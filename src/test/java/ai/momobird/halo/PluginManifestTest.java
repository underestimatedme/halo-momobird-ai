package ai.momobird.halo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The links Halo shows on the plugin's page must lead somewhere public: the repository is private. */
class PluginManifestTest {

    @Test
    @SuppressWarnings("unchecked")
    void linksPointAtMomoBirdNotAPrivateRepository() throws Exception {
        Map<String, Object> plugin = SettingsFormTest.yaml("/plugin.yaml");
        Map<String, Object> spec = (Map<String, Object>) plugin.get("spec");

        assertThat(spec.get("homepage")).isEqualTo("https://momobird.atlaspaces.com/docs/halo");
        assertThat(((Map<String, Object>) spec.get("author")).get("website")).isEqualTo("https://momobird.atlaspaces.com");
        // optional in Halo's Plugin spec: left out rather than pointing at a repository nobody can open
        assertThat(spec).doesNotContainKeys("repo", "issues");
        List<Map<String, Object>> licenses = (List<Map<String, Object>>) spec.get("license");
        assertThat(licenses).extracting(l -> l.get("name")).containsExactly("GPL-3.0");
        assertThat(licenses).extracting(l -> l.get("url")).containsExactly("https://www.gnu.org/licenses/gpl-3.0.html");
        assertThat(plugin.toString()).doesNotContain("github.com");
    }
}
