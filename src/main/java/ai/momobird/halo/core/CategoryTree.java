package ai.momobird.halo.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

/**
 * Halo's category hierarchy, read once per sync run. A category lists its children
 * ({@code spec.children}); its parent is the category whose list holds it.
 */
public final class CategoryTree {

    /** One category: its name, its display name and the names of its children. */
    public record Node(String name, String displayName, List<String> children) {
        public Node {
            children = children == null ? List.of() : List.copyOf(children);
        }
    }

    public static final CategoryTree EMPTY = new CategoryTree(Map.of(), Map.of());

    private final Map<String, Node> nodes;
    private final Map<String, String> parents;

    private CategoryTree(Map<String, Node> nodes, Map<String, String> parents) {
        this.nodes = nodes;
        this.parents = parents;
    }

    public static CategoryTree of(List<Node> list) {
        Map<String, Node> nodes = new HashMap<>();
        for (Node node : list) {
            if (node.name() != null) {
                nodes.put(node.name(), node);
            }
        }
        // A category with two parents (not something Halo's editor produces) keeps the one whose
        // name sorts first, so the answer does not depend on listing order.
        Map<String, String> parents = new HashMap<>();
        for (Node node : nodes.values()) {
            for (String child : node.children()) {
                parents.merge(child, node.name(), (a, b) -> a.compareTo(b) <= 0 ? a : b);
            }
        }
        return new CategoryTree(Map.copyOf(nodes), Map.copyOf(parents));
    }

    /**
     * The display name of the root of the chain the post's first category belongs to. A category
     * that no longer exists, or has no display name, is skipped in favour of the post's next one.
     * A cycle ends where it closes instead of looping.
     */
    public Optional<String> topOf(PostView post) {
        for (String name : post.categoryNames()) {
            Optional<String> top = topOf(name);
            if (top.isPresent()) {
                return top;
            }
        }
        return Optional.empty();
    }

    private Optional<String> topOf(String name) {
        Node current = nodes.get(name);
        if (current == null) {
            return Optional.empty();
        }
        Set<String> seen = new HashSet<>();
        seen.add(current.name());
        while (true) {
            String parentName = parents.get(current.name());
            Node parent = parentName == null ? null : nodes.get(parentName);
            if (parent == null || !seen.add(parent.name())) {
                break;
            }
            current = parent;
        }
        String display = current.displayName();
        return display == null || display.isBlank() ? Optional.empty() : Optional.of(display.strip());
    }
}
