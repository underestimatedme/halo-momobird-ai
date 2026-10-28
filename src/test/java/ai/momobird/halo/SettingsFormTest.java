package ai.momobird.halo;

import static org.assertj.core.api.Assertions.assertThat;

import ai.momobird.halo.core.Settings;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Keeps the form in settings.yaml, plugin.yaml and {@link SettingsLoader} in step. */
class SettingsFormTest {

    @SuppressWarnings("unchecked")
    static Map<String, Object> yaml(String resource) throws Exception {
        try (InputStream in = SettingsFormTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> fields() throws Exception {
        Map<String, Object> doc = yaml("/extensions/settings.yaml");
        assertThat(doc).containsEntry("kind", "Setting").containsEntry("apiVersion", "v1alpha1");
        Map<String, Map<String, Object>> byName = new java.util.LinkedHashMap<>();
        List<Map<String, Object>> forms = (List<Map<String, Object>>) ((Map<String, Object>) doc.get("spec")).get("forms");
        for (Map<String, Object> form : forms) {
            for (Map<String, Object> field : (List<Map<String, Object>>) form.get("formSchema")) {
                byName.put(form.get("group") + "." + field.get("name"), field);
            }
        }
        return byName;
    }

    @Test
    @SuppressWarnings("unchecked")
    void pluginYamlPointsAtTheSettingAndItsConfigMap() throws Exception {
        Map<String, Object> plugin = yaml("/plugin.yaml");
        Map<String, Object> spec = (Map<String, Object>) plugin.get("spec");
        Map<String, Object> setting = yaml("/extensions/settings.yaml");
        assertThat(spec.get("settingName")).isEqualTo(((Map<String, Object>) setting.get("metadata")).get("name"));
        assertThat(spec.get("configMapName")).isEqualTo("momobird-ai-configmap");
    }

    @Test
    @SuppressWarnings("unchecked")
    void hasThreeGroupsInOrder() throws Exception {
        Map<String, Object> doc = yaml("/extensions/settings.yaml");
        List<Map<String, Object>> forms = (List<Map<String, Object>>) ((Map<String, Object>) doc.get("spec")).get("forms");
        assertThat(forms).extracting(f -> f.get("group"))
            .containsExactly(SettingsLoader.CONNECTION, SettingsLoader.WIDGET, SettingsLoader.PODCAST);
        assertThat(forms).extracting(f -> f.get("label")).containsExactly("连接", "问答助手", "播客");
    }

    @Test
    void fieldsMatchWhatTheLoaderReads() throws Exception {
        assertThat(fields().keySet()).containsExactly(
            "connection.baseUrl", "connection.keySecret",
            "widget.enabled", "widget.position",
            "podcast.player", "podcast.transcript");
    }

    /**
     * The key lives in a Halo Secret, which only super administrators can read: the form only holds
     * the Secret's name. Halo 2.17 to 2.22 read {@code requiredKey}, 2.23 and later
     * {@code requiredKeys}; both are given so the input asks for the same entry everywhere.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theKeyIsASecretReferenceNotAValue() throws Exception {
        Map<String, Object> field = fields().get("connection.keySecret");
        assertThat(field).containsEntry("$formkit", "secret")
            .containsEntry("requiredKey", SettingsLoader.SECRET_KEY);
        assertThat((List<Map<String, Object>>) field.get("requiredKeys"))
            .extracting(k -> k.get("key")).containsExactly(SettingsLoader.SECRET_KEY);
        assertThat(String.valueOf(field.get("help"))).contains("超级管理员");
        assertThat(fields().values()).noneMatch(f -> "password".equals(f.get("$formkit")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void formDefaultsMatchSettingsDefaults() throws Exception {
        Map<String, Map<String, Object>> f = fields();
        Settings defaults = Settings.of(null, null, null, null, null, null);
        assertThat(f.get("connection.baseUrl").get("value")).isEqualTo(defaults.baseUrl());
        assertThat(f.get("widget.enabled").get("value")).isEqualTo(defaults.widgetEnabled());
        assertThat(f.get("widget.position").get("value")).isEqualTo(defaults.widgetPosition());
        assertThat(f.get("podcast.player").get("value")).isEqualTo(defaults.podcastEnabled());
        assertThat(f.get("podcast.transcript").get("value")).isEqualTo(defaults.podcastTranscript());
        List<Map<String, Object>> options = (List<Map<String, Object>>) f.get("widget.position").get("options");
        assertThat(options).extracting(o -> o.get("value")).containsExactly("right", "left");
    }
}
