package com.articulorum.topology.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link ChildRule}, {@link TopologyNode} and {@link TopologySummary}.
 */
class TopologyModelTest {

    static TopologyNode page() {
        return new TopologyNode("page", "Page", TopologyNode.OBJECT, null, null, null, null);
    }

    static TopologyNode book() {
        return new TopologyNode("book", "Book", TopologyNode.OBJECT, List.of("http://purl.org/ontology/bibo/Book"),
            List.of(), List.of(), List.of(new ChildRule(1, 10, 3, "page-{n}", page())));
    }

    @Test
    void childCountsAreClamped() {
        final ChildRule rule = new ChildRule(1, 10, 3, "page-{n}", page());
        assertThat(rule.count(null)).isEqualTo(3);
        assertThat(rule.count(0)).isEqualTo(1);
        assertThat(rule.count(50)).isEqualTo(10);
        assertThat(new ChildRule(0, ChildRule.UNLIMITED, 0, null, page()).count(200)).isEqualTo(200);
    }

    @Test
    void slugsUseThePatternOrTheKey() {
        assertThat(new ChildRule(0, -1, 0, "folio-{n}", page()).slug(7)).isEqualTo("folio-7");
        assertThat(new ChildRule(0, -1, 0, null, page()).slug(2)).isEqualTo("page-2");
        assertThat(new ChildRule(0, 1, 1, "front", page()).slug(1)).isEqualTo("front");
    }

    @Test
    void nodesAreFoundInTheTree() {
        final TopologyNode book = book();
        assertThat(book.find("page")).contains(page());
        assertThat(book.find("missing")).isEmpty();
        assertThat(book.childRule("page")).isPresent();
        assertThat(book.childRule("book")).isEmpty();
        assertThat(book.flatten()).extracting(TopologyNode::key).containsExactly("book", "page");
        assertThat(page().types()).isEmpty();
    }

    @Test
    void summariesCountLevels() {
        final TopologySummary summary = TopologySummary.of(new Topology("book", "Book", "d", true, book()));
        assertThat(summary.depth()).isEqualTo(2);
        assertThat(summary.rootLabel()).isEqualTo("Book");
        assertThat(summary.builtIn()).isTrue();
    }

}
