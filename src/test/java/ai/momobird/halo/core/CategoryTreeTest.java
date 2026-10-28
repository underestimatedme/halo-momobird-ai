package ai.momobird.halo.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CategoryTreeTest {

    private static CategoryTree tree() {
        // tech > java > spring ; life ; loop-a <-> loop-b
        return CategoryTree.of(List.of(
            new CategoryTree.Node("tech", "技术", List.of("java")),
            new CategoryTree.Node("java", "Java", List.of("spring")),
            new CategoryTree.Node("spring", "Spring", List.of()),
            new CategoryTree.Node("life", "生活", null),
            new CategoryTree.Node("loop-a", "环甲", List.of("loop-b")),
            new CategoryTree.Node("loop-b", "环乙", List.of("loop-a")),
            new CategoryTree.Node("self", "自指", List.of("self")),
            new CategoryTree.Node("blank", "  ", List.of())));
    }

    private static PostView post(String... categories) {
        return PostView.builder().name("p").categoryNames(List.of(categories)).build();
    }

    @Test
    void aTopLevelCategoryIsItsOwnTop() {
        assertThat(tree().topOf(post("life"))).contains("生活");
    }

    @Test
    void aNestedCategoryResolvesToTheRootOfItsChain() {
        assertThat(tree().topOf(post("spring"))).contains("技术");
        assertThat(tree().topOf(post("java"))).contains("技术");
    }

    @Test
    void onlyTheFirstCategoryCounts() {
        assertThat(tree().topOf(post("life", "spring"))).contains("生活");
        assertThat(tree().topOf(post("spring", "life"))).contains("技术");
    }

    @Test
    void noCategoriesMeansNoCategory() {
        assertThat(tree().topOf(post())).isEmpty();
        assertThat(tree().topOf(PostView.builder().name("p").categoryNames(null).build())).isEmpty();
    }

    @Test
    void aCategoryThatNoLongerExistsFallsToTheNextOne() {
        assertThat(tree().topOf(post("gone", "life"))).contains("生活");
        assertThat(tree().topOf(post("gone"))).isEmpty();
    }

    @Test
    void aBlankDisplayNameIsNoName() {
        assertThat(tree().topOf(post("blank"))).isEmpty();
    }

    @Test
    void cyclesTerminate() {
        // a loop has no root; whatever name is chosen, it must come back
        Optional<String> a = tree().topOf(post("loop-a"));
        assertThat(a).isPresent();
        assertThat(a.get()).isIn("环甲", "环乙");
        assertThat(tree().topOf(post("self"))).contains("自指");
    }

    @Test
    void aVeryDeepChainTerminates() {
        java.util.ArrayList<CategoryTree.Node> nodes = new java.util.ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            nodes.add(new CategoryTree.Node("c" + i, "名" + i, List.of("c" + (i + 1))));
        }
        nodes.add(new CategoryTree.Node("c5000", "名5000", List.of()));
        assertThat(CategoryTree.of(nodes).topOf(post("c5000"))).isPresent();
    }

    @Test
    void aCategoryWithTwoParentsUsesAConsistentOne() {
        CategoryTree t = CategoryTree.of(List.of(
            new CategoryTree.Node("a", "甲", List.of("c")),
            new CategoryTree.Node("b", "乙", List.of("c")),
            new CategoryTree.Node("c", "丙", List.of())));
        assertThat(t.topOf(post("c"))).isPresent();
        assertThat(t.topOf(post("c"))).isEqualTo(t.topOf(post("c")));
    }

    @Test
    void anEmptyTreeResolvesNothing() {
        assertThat(CategoryTree.of(List.of()).topOf(post("life"))).isEmpty();
        assertThat(CategoryTree.EMPTY.topOf(post("life"))).isEmpty();
    }
}
