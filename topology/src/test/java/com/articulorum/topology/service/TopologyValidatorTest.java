package com.articulorum.topology.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.articulorum.topology.model.ChildRule;
import com.articulorum.topology.model.FileUse;
import com.articulorum.topology.model.MetadataField;
import com.articulorum.topology.model.Topology;
import com.articulorum.topology.model.TopologyNode;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link TopologyValidator}.
 */
class TopologyValidatorTest {

    private static TopologyNode node(final String key, final String type, final ChildRule... children) {
        return new TopologyNode(key, key.toUpperCase(), type, List.of(), List.of(), List.of(), List.of(children));
    }

    @Test
    void acceptsAValidTopology() {
        final Topology topology = new Topology("album", "Photo album", null, false,
            node("album", TopologyNode.OBJECT, new ChildRule(0, -1, 2, null, node("photo", TopologyNode.OBJECT))));
        assertThat(TopologyValidator.validate(topology)).isEmpty();
    }

    @Test
    void reportsEveryProblem() {
        final TopologyNode bad = new TopologyNode("Bad Key", "", "Thing", List.of("not an iri"),
            List.of(new MetadataField("dc:title", "", false, false, null, "not a datatype")),
            List.of(new FileUse("original", "x", null)),
            List.of(new ChildRule(-1, 0, -1, null, node("dup", TopologyNode.OBJECT)),
                new ChildRule(0, 1, 0, null, node("dup", TopologyNode.OBJECT)),
                new ChildRule(0, 1, 0, null, null)));
        final List<String> errors = TopologyValidator.validate(new Topology("Not Valid", " ", null, false, bad));

        assertThat(errors).anyMatch(e -> e.startsWith("id"))
            .anyMatch(e -> e.startsWith("name"))
            .anyMatch(e -> e.contains("key must be a lower-case slug"))
            .anyMatch(e -> e.contains("label is required"))
            .anyMatch(e -> e.contains("pcdmType"))
            .anyMatch(e -> e.contains("is not an absolute IRI"))
            .anyMatch(e -> e.contains("needs a label"))
            .anyMatch(e -> e.contains("datatype"))
            .anyMatch(e -> e.contains("file use"))
            .anyMatch(e -> e.contains("must not be negative"))
            .anyMatch(e -> e.contains("key is used twice"))
            .anyMatch(e -> e.contains("has no node"));
    }

    @Test
    void objectsCannotContainCollections() {
        final Topology topology = new Topology("x", "X", null, false,
            node("obj", TopologyNode.OBJECT, new ChildRule(0, -1, 0, null, node("coll", TopologyNode.COLLECTION))));
        assertThatThrownBy(() -> TopologyValidator.check(topology))
            .isInstanceOf(InvalidTopologyException.class)
            .hasMessageContaining("cannot contain a collection");
    }

    @Test
    void depthIsLimited() {
        TopologyNode deepest = node("n9", TopologyNode.OBJECT);
        for (int i = 8; i >= 0; i--) {
            deepest = node("n" + i, TopologyNode.OBJECT, new ChildRule(0, -1, 0, null, deepest));
        }
        assertThat(TopologyValidator.validate(new Topology("deep", "Deep", null, false, deepest)))
            .anyMatch(e -> e.contains("deeper than"));
        assertThat(TopologyValidator.validate(null)).containsExactly("A topology is required");
        assertThat(TopologyValidator.validate(new Topology("a", "A", null, false, null)))
            .containsExactly("root node is required");
    }

}
