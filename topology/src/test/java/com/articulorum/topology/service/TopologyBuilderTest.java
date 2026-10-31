package com.articulorum.topology.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.articulorum.domain.rdf.ResourcePaths;
import com.articulorum.domain.vocabulary.Articulorum;
import com.articulorum.domain.vocabulary.Ldp;
import com.articulorum.domain.vocabulary.Pcdm;
import com.articulorum.topology.config.TopologyProperties;
import com.articulorum.topology.model.AddMemberRequest;
import com.articulorum.topology.model.BuildRequest;
import com.articulorum.topology.model.BuildResult;
import com.articulorum.topology.model.ChildRule;
import com.articulorum.topology.model.MetadataField;
import com.articulorum.topology.model.Topology;
import com.articulorum.topology.model.TopologyNode;
import com.articulorum.topology.platform.PlatformClient;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests {@link TopologyBuilder} with a mocked platform.
 */
class TopologyBuilderTest {

    private static final String CREATOR = "http://purl.org/dc/terms/creator";

    private PlatformClient platform;

    private TopologyBuilder builder;

    private final Topology book = new Topology("book", "Book", null, true,
        new TopologyNode("book", "Book", TopologyNode.OBJECT, List.of("http://purl.org/ontology/bibo/Book"),
            List.of(new MetadataField(CREATOR, "Creator", false, true, null, null),
                new MetadataField("http://purl.org/dc/terms/language", "Language", false, false, "en", null)),
            List.of(),
            List.of(new ChildRule(1, 3, 2, "page-{n}", new TopologyNode("page", "Page", TopologyNode.OBJECT, null, null,
                List.of(new com.articulorum.topology.model.FileUse(Pcdm.ORIGINAL_FILE, "Image", null)), null)))));

    @BeforeEach
    void setUp() {
        platform = mock(PlatformClient.class);
        when(platform.url(any())).thenAnswer(call -> "http://platform.test/" + call.getArgument(0));
        when(platform.create(anyString(), anyString(), anyString(), anyList(), any()))
            .thenAnswer(call -> ResourcePaths.child(call.getArgument(0), call.getArgument(1)));
        builder = new TopologyBuilder(platform, new TopologyProperties());
    }

    @Test
    void turtleCarriesTypesTitleTopologyPositionAndMetadata() {
        final String turtle = TopologyBuilder.turtle(book, book.root(), "Moby \"Dick\"",
            Map.of(CREATOR, List.of("Melville")), 3);

        assertThat(turtle)
            .contains("<> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <" + Pcdm.OBJECT + "> .")
            .contains("<http://purl.org/ontology/bibo/Book>")
            .contains("\"Moby \\\"Dick\\\"\"")
            .contains("<" + Articulorum.TOPOLOGY + "> \"book\"")
            .contains("<" + Articulorum.TOPOLOGY_NODE + "> \"book\"")
            .contains("<http://schema.org/position> \"3\"^^<http://www.w3.org/2001/XMLSchema#integer>")
            .contains("<" + CREATOR + "> \"Melville\"")
            .contains("<http://purl.org/dc/terms/language> \"en\"");
    }

    @Test
    void slugsAreDerivedFromTitles() {
        assertThat(TopologyBuilder.slugOf("Café: Société Générale!")).isEqualTo("cafe-societe-generale");
        assertThat(TopologyBuilder.slugOf("***")).isEqualTo("***");
    }

    @Test
    void directContainersPointAtTheirOwnerRelatively() {
        assertThat(TopologyBuilder.directContainer("c/book", Pcdm.HAS_MEMBER, "Members"))
            .contains("<" + Ldp.MEMBERSHIP_RESOURCE + "> <../book>")
            .contains("<" + Pcdm.HAS_MEMBER + ">");
    }

    @Test
    void buildsTheWholeTree() {
        final BuildResult result = builder.build(book,
            new BuildRequest("collection", "Moby Dick", null, Map.of(), Map.of("page", 3)), "Bearer t");

        assertThat(result.path()).isEqualTo("collection/moby-dick");
        assertThat(result.resources()).containsExactly("collection/moby-dick", "collection/moby-dick/members",
            "collection/moby-dick/members/page-1", "collection/moby-dick/members/page-1/files",
            "collection/moby-dick/members/page-2", "collection/moby-dick/members/page-2/files",
            "collection/moby-dick/members/page-3", "collection/moby-dick/members/page-3/files");
        verify(platform, times(4)).create(anyString(), anyString(), anyString(), eq(List.of(Ldp.BASIC_CONTAINER)),
            eq("Bearer t"));
        verify(platform, times(4)).put(anyString(), any(), eq("text/turtle"), eq(List.of(Ldp.DIRECT_CONTAINER)),
            eq("Bearer t"));
    }

    @Test
    void rejectsUnknownNodesAndExcessiveCounts() {
        assertThatThrownBy(() -> builder.build(book, new BuildRequest("", "x", null, null, Map.of("leaf", 1)), null))
            .isInstanceOf(InvalidTopologyException.class).hasMessageContaining("Unknown node leaf");
        assertThatThrownBy(() -> builder.build(book, new BuildRequest("", "x", null, null, Map.of("page", 100_000)), null))
            .isInstanceOf(InvalidTopologyException.class).hasMessageContaining("between 0 and");
    }

    private Model instance(final String path, final String topology, final String node) {
        final Model model = ModelFactory.createDefaultModel();
        model.createResource("http://platform.test/" + path)
            .addProperty(model.createProperty(Articulorum.TOPOLOGY), topology)
            .addProperty(model.createProperty(Articulorum.TOPOLOGY_NODE), node);
        return model;
    }

    @Test
    void addsMembersAtTheNextPosition() {
        when(platform.rdf(eq("c/book"), any())).thenReturn(Optional.of(instance("c/book", "book", "book")));
        final Model members = ModelFactory.createDefaultModel();
        members.createResource("http://platform.test/c/book/members")
            .addProperty(members.createProperty(Ldp.CONTAINS), members.createResource("http://platform.test/c/book/members/page-1"));
        when(platform.rdf(eq("c/book/members"), any())).thenReturn(Optional.of(members));

        final BuildResult result = builder.addMember(book, new AddMemberRequest("c/book", "page", null, null), null);

        assertThat(result.path()).isEqualTo("c/book/members/page-2");
        final ArgumentCaptor<String> turtle = ArgumentCaptor.forClass(String.class);
        verify(platform).create(eq("c/book/members"), eq("page-2"), turtle.capture(), anyList(), any());
        assertThat(turtle.getValue()).contains("\"Page 2\"").contains("\"2\"^^");
    }

    @Test
    void addMemberChecksTheTopologyAndLimits() {
        when(platform.rdf(eq("other"), any())).thenReturn(Optional.of(instance("other", "newspaper", "issue")));
        assertThatThrownBy(() -> builder.addMember(book, new AddMemberRequest("other", "page", null, null), null))
            .hasMessageContaining("was not built from the topology book");

        when(platform.rdf(eq("c/book"), any())).thenReturn(Optional.of(instance("c/book", "book", "book")));
        assertThatThrownBy(() -> builder.addMember(book, new AddMemberRequest("c/book", "chapter", null, null), null))
            .hasMessageContaining("is not a child of book");

        final Model full = ModelFactory.createDefaultModel();
        for (int i = 1; i <= 3; i++) {
            full.createResource("http://platform.test/c/book/members")
                .addProperty(full.createProperty(Ldp.CONTAINS), full.createResource("http://platform.test/c/book/members/p" + i));
        }
        when(platform.rdf(eq("c/book/members"), any())).thenReturn(Optional.of(full));
        assertThatThrownBy(() -> builder.addMember(book, new AddMemberRequest("c/book", "page", null, null), null))
            .hasMessageContaining("maximum of 3");

        when(platform.rdf(eq("gone"), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> builder.addMember(book, new AddMemberRequest("gone", "page", null, null), null))
            .hasMessageContaining("does not exist");
    }

    @Test
    void createsTheMembersContainerWhenMissing() {
        when(platform.rdf(eq("c/book"), any())).thenReturn(Optional.of(instance("c/book", "book", "book")));
        when(platform.rdf(eq("c/book/members"), any())).thenReturn(Optional.empty());

        builder.addMember(book, new AddMemberRequest("c/book", "page", "Cover", null), null);

        verify(platform).put(eq("c/book/members"), any(), eq("text/turtle"), eq(List.of(Ldp.DIRECT_CONTAINER)), any());
        final ArgumentCaptor<String> turtle = ArgumentCaptor.forClass(String.class);
        verify(platform).create(eq("c/book/members"), eq("page-1"), turtle.capture(), anyList(), any());
        assertThat(turtle.getValue().getBytes(StandardCharsets.UTF_8)).isNotEmpty();
        assertThat(turtle.getValue()).contains("\"Cover\"");
    }

}
