package ai.momobird.halo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Halo turns {@code GET /apis/api.momobird.ai/v1alpha1/status} into (verb list, resource status)
 * and {@code POST .../{test,sync,retry}} into (verb create, resource of that name). The role
 * templates must grant exactly these, to nobody by default.
 */
class RoleTemplatesTest {

    private record Call(String verb, String resource) {
    }

    /** The routes of AdminEndpoint as Halo's RequestInfo reads them. */
    private static final List<Call> ROUTES = List.of(
        new Call("list", "status"), new Call("create", "test"), new Call("create", "sync"),
        new Call("create", "retry"));

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> roles() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/extensions/role-templates.yaml")) {
            List<Map<String, Object>> roles = new ArrayList<>();
            new Yaml().loadAll(in).forEach(doc -> roles.add((Map<String, Object>) doc));
            return roles;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean allows(Map<String, Object> role, Call call) {
        for (Map<String, Object> rule : (List<Map<String, Object>>) role.get("rules")) {
            List<String> groups = (List<String>) rule.getOrDefault("apiGroups", List.of());
            List<String> resources = (List<String>) rule.getOrDefault("resources", List.of());
            List<String> verbs = (List<String>) rule.getOrDefault("verbs", List.of());
            if (groups.contains("api.momobird.ai") && resources.contains(call.resource())
                && verbs.contains(call.verb())) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("both templates grant every admin route and nothing broader")
    @SuppressWarnings("unchecked")
    void grantsExactlyTheAdminRoutes() throws Exception {
        List<Map<String, Object>> roles = roles();
        assertThat(roles).hasSize(2);
        for (Map<String, Object> role : roles) {
            Map<String, Object> labels = (Map<String, Object>) ((Map<String, Object>) role.get("metadata")).get("labels");
            assertThat(role.get("kind")).isEqualTo("Role");
            assertThat(labels).containsEntry("halo.run/role-template", "true");
            for (Call call : ROUTES) {
                assertThat(allows(role, call)).as(role + " " + call).isTrue();
            }
            for (Map<String, Object> rule : (List<Map<String, Object>>) role.get("rules")) {
                assertThat(rule.get("apiGroups")).isEqualTo(List.of("api.momobird.ai"));
                assertThat((List<String>) rule.get("resources")).doesNotContain("*");
                assertThat((List<String>) rule.get("verbs")).doesNotContain("*", "delete", "update", "patch");
                assertThat(rule).doesNotContainKey("nonResourceURLs");
            }
            // no read access to anything else, no write on the route that reads
            assertThat(allows(role, new Call("create", "status"))).isFalse();
            assertThat(allows(role, new Call("list", "sync"))).isFalse();
        }
    }

    @Test
    @DisplayName("plugin managers get the page; the permission is also its own template")
    @SuppressWarnings("unchecked")
    void pluginManagersAndOwnTemplate() throws Exception {
        List<Map<String, Object>> roles = roles();
        Map<String, Object> own = roles.get(0);
        Map<String, Object> aggregate = roles.get(1);
        Map<String, Object> ownMeta = (Map<String, Object>) own.get("metadata");
        Map<String, Object> aggMeta = (Map<String, Object>) aggregate.get("metadata");

        assertThat(((Map<String, Object>) ownMeta.get("annotations")).get("rbac.authorization.halo.run/ui-permissions"))
            .asString().contains("plugin:momobird-ai:manage");
        assertThat((Map<String, Object>) aggMeta.get("labels"))
            .containsEntry("rbac.authorization.halo.run/aggregate-to-role-template-manage-plugins", "true")
            .containsEntry("halo.run/hidden", "true");
        assertThat((Map<String, Object>) ownMeta.get("labels"))
            .doesNotContainKey("rbac.authorization.halo.run/aggregate-to-anonymous")
            .doesNotContainKey("rbac.authorization.halo.run/aggregate-to-authenticated");
    }
}
