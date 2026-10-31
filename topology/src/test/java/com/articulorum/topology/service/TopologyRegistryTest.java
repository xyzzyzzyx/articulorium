package com.articulorum.topology.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.articulorum.domain.vocabulary.Ldp;
import com.articulorum.topology.config.TopologyProperties;
import com.articulorum.topology.model.Topology;
import com.articulorum.topology.platform.PlatformClient;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link TopologyRegistry} with a mocked platform.
 */
class TopologyRegistryTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private PlatformClient platform;

    private TopologyRegistry registry;

    @BeforeEach
    void setUp() {
        platform = mock(PlatformClient.class);
        when(platform.url(any())).thenAnswer(call -> "http://platform.test/" + call.getArgument(0));
        when(platform.pathOf(any())).thenAnswer(call -> ((URI) call.getArgument(0)).getPath().substring(1));
        registry = new TopologyRegistry(platform, new TopologyProperties(), json);
    }

    @Test
    void everyBuiltInTopologyIsValid() {
        final Map<String, Topology> builtIns = TopologyRegistry.loadBuiltIns(json);
        assertThat(builtIns).containsKeys("book", "newspaper", "journal", "postcards", "manuscript", "audiovisual");
        builtIns.values().forEach(topology -> {
            assertThat(topology.builtIn()).isTrue();
            assertThat(TopologyValidator.validate(topology)).isEmpty();
        });
    }

    @Test
    void listsBuiltInsAndStoredTopologies() throws Exception {
        final Model container = ModelFactory.createDefaultModel();
        container.createResource("http://platform.test/topologies")
            .addProperty(container.createProperty(Ldp.CONTAINS), container.createResource("http://platform.test/topologies/album"));
        when(platform.rdf(eq("topologies"), any())).thenReturn(Optional.of(container));
        final Topology album = registry.get("book", null).with("album", false);
        when(platform.bytes(eq("topologies/album"), any())).thenReturn(Optional.of(json.writeValueAsBytes(album)));

        final List<Topology> all = registry.list("Bearer t");

        assertThat(all).extracting(Topology::id).contains("book", "album");
        assertThat(all.get(all.size() - 1).id()).isEqualTo("album");
        assertThat(registry.get("album", null).builtIn()).isFalse();
    }

    @Test
    void savesCustomTopologiesAsJsonInTheContainer() {
        when(platform.rdf(eq("topologies"), any())).thenReturn(Optional.empty());
        final Topology copy = registry.get("book", null).with("my-book", true);

        final Topology saved = registry.save(copy, "Bearer t");

        assertThat(saved.builtIn()).isFalse();
        verify(platform).put(eq("topologies"), any(), eq("text/turtle"), eq(List.of(Ldp.BASIC_CONTAINER)), eq("Bearer t"));
        verify(platform).put(eq("topologies/my-book"), any(), eq("application/json"), eq(List.of(Ldp.NON_RDF_SOURCE)),
            eq("Bearer t"));
    }

    @Test
    void builtInsCannotBeReplacedOrDeleted() {
        assertThatThrownBy(() -> registry.save(registry.get("book", null), null))
            .isInstanceOf(InvalidTopologyException.class);
        assertThatThrownBy(() -> registry.delete("book", null)).isInstanceOf(InvalidTopologyException.class);
        verify(platform, never()).put(any(), any(), any(), anyList(), any());
    }

    @Test
    void unknownTopologiesAreNotFound() {
        when(platform.bytes(eq("topologies/nope"), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> registry.get("nope", null)).isInstanceOf(TopologyNotFoundException.class);
        assertThatThrownBy(() -> registry.get("../etc", null)).isInstanceOf(TopologyNotFoundException.class);
        when(platform.bytes(eq("topologies/broken"), any()))
            .thenReturn(Optional.of("{".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> registry.get("broken", null)).isInstanceOf(TopologyNotFoundException.class);
    }

}
