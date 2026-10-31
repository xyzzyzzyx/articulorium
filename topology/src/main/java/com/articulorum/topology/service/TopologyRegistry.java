package com.articulorum.topology.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.articulorum.domain.rdf.ResourcePaths;
import com.articulorum.domain.vocabulary.Ldp;
import com.articulorum.topology.config.TopologyProperties;
import com.articulorum.topology.model.Topology;
import com.articulorum.topology.platform.PlatformClient;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Knows every topology: the built-in ones shipped as {@code classpath:topologies/*.json} and the
 * custom ones stored in the repository, one JSON document per topology in the configured
 * container ({@code /topologies/<id>} by default). Built-in topologies cannot be replaced or
 * deleted; a custom topology may start as a copy of one.
 */
@Slf4j
@Service
public class TopologyRegistry {

    /** Media type of stored topologies. */
    static final String JSON = "application/json";

    private final PlatformClient platform;

    private final TopologyProperties properties;

    private final JsonMapper json;

    private final Map<String, Topology> builtIns;

    /**
     * Creates the registry and loads the built-in topologies.
     *
     * @param platform   the platform client
     * @param properties topology settings
     * @param json       JSON mapper
     */
    public TopologyRegistry(final PlatformClient platform, final TopologyProperties properties, final JsonMapper json) {
        this.platform = platform;
        this.properties = properties;
        this.json = json;
        this.builtIns = loadBuiltIns(json);
        log.info("Loaded {} built-in topologies: {}", builtIns.size(), builtIns.keySet());
    }

    /**
     * Lists every topology, built-in ones first, each group by name.
     *
     * @param authorization the caller's {@code Authorization} header
     * @return the topologies
     */
    public List<Topology> list(final String authorization) {
        final List<Topology> all = new ArrayList<>(builtIns.values());
        final List<Topology> custom = new ArrayList<>();
        for (final String id : customIds(authorization)) {
            read(id, authorization).ifPresent(custom::add);
        }
        custom.sort(Comparator.comparing(Topology::name, String.CASE_INSENSITIVE_ORDER));
        all.addAll(custom);
        return all;
    }

    /**
     * Returns a topology.
     *
     * @param id            the identifier
     * @param authorization the caller's {@code Authorization} header
     * @return the topology
     * @throws TopologyNotFoundException when it does not exist
     */
    public Topology get(final String id, final String authorization) {
        final Topology builtIn = builtIns.get(id);
        if (builtIn != null) {
            return builtIn;
        }
        if (id == null || !TopologyValidator.SLUG.matcher(id).matches()) {
            throw new TopologyNotFoundException(id);
        }
        return read(id, authorization).orElseThrow(() -> new TopologyNotFoundException(id));
    }

    /**
     * Creates or replaces a custom topology.
     *
     * @param topology      the topology (its {@code builtIn} flag is ignored)
     * @param authorization the caller's {@code Authorization} header
     * @return the stored topology
     * @throws InvalidTopologyException when it is invalid or would replace a built-in topology
     */
    public Topology save(final Topology topology, final String authorization) {
        final Topology custom = topology.with(topology.id(), false);
        TopologyValidator.check(custom);
        if (builtIns.containsKey(custom.id())) {
            throw new InvalidTopologyException(List.of("The built-in topology " + custom.id()
                + " cannot be replaced; save a copy under another id"));
        }
        ensureContainer(authorization);
        final byte[] body;
        try {
            body = json.writeValueAsBytes(custom);
        } catch (JacksonException e) {
            throw new InvalidTopologyException(List.of("Unable to serialise the topology: " + e.getOriginalMessage()));
        }
        platform.put(pathOf(custom.id()), body, JSON, List.of(Ldp.NON_RDF_SOURCE), authorization);
        return custom;
    }

    /**
     * Deletes a custom topology.
     *
     * @param id            the identifier
     * @param authorization the caller's {@code Authorization} header
     * @throws InvalidTopologyException for built-in topologies
     * @throws TopologyNotFoundException when it does not exist
     */
    public void delete(final String id, final String authorization) {
        if (builtIns.containsKey(id)) {
            throw new InvalidTopologyException(List.of("The built-in topology " + id + " cannot be deleted"));
        }
        get(id, authorization);
        platform.delete(pathOf(id), authorization);
    }

    /**
     * Tells whether an identifier belongs to a built-in topology.
     *
     * @param id the identifier
     * @return {@code true} for built-in topologies
     */
    public boolean isBuiltIn(final String id) {
        return builtIns.containsKey(id);
    }

    private Optional<Topology> read(final String id, final String authorization) {
        return platform.bytes(pathOf(id), authorization).flatMap(bytes -> {
            try {
                return Optional.of(json.readValue(bytes, Topology.class).with(id, false));
            } catch (JacksonException e) {
                log.warn("Ignoring unreadable topology {}: {}", id, e.getOriginalMessage());
                return Optional.empty();
            }
        });
    }

    private List<String> customIds(final String authorization) {
        final Optional<Model> container = platform.rdf(properties.getContainer(), authorization);
        if (container.isEmpty()) {
            return List.of();
        }
        final List<String> ids = new ArrayList<>();
        final Model model = container.get();
        model.listObjectsOfProperty(model.createProperty(Ldp.CONTAINS)).forEachRemaining((RDFNode node) -> {
            if (node.isURIResource()) {
                final String name = ResourcePaths.nameOf(platform.pathOf(java.net.URI.create(node.asResource().getURI())));
                if (TopologyValidator.SLUG.matcher(name).matches()) {
                    ids.add(name);
                }
            }
        });
        return ids;
    }

    private void ensureContainer(final String authorization) {
        if (platform.rdf(properties.getContainer(), authorization).isPresent()) {
            return;
        }
        platform.put(properties.getContainer(),
            ("<> <http://purl.org/dc/terms/title> \"Topologies\" ;\n"
                + "   <http://purl.org/dc/terms/description> \"Custom PCDM topologies of the topology service\" .\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "text/turtle", List.of(Ldp.BASIC_CONTAINER), authorization);
    }

    private String pathOf(final String id) {
        return ResourcePaths.child(ResourcePaths.normalize(properties.getContainer()), id);
    }

    /**
     * Loads the built-in topologies.
     *
     * @param json JSON mapper
     * @return the topologies by identifier, in name order
     */
    static Map<String, Topology> loadBuiltIns(final JsonMapper json) {
        final List<Topology> loaded = new ArrayList<>();
        try {
            for (final Resource resource : new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:topologies/*.json")) {
                try (InputStream in = resource.getInputStream()) {
                    final Topology topology = json.readValue(in, Topology.class);
                    final Topology builtIn = topology.with(topology.id(), true);
                    TopologyValidator.check(builtIn);
                    loaded.add(builtIn);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to load the built-in topologies", e);
        }
        loaded.sort(Comparator.comparing(Topology::name, String.CASE_INSENSITIVE_ORDER));
        final Map<String, Topology> byId = new LinkedHashMap<>();
        loaded.forEach(topology -> byId.put(topology.id(), topology));
        return java.util.Collections.unmodifiableMap(byId);
    }

}
