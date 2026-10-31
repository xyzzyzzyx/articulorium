package com.articulorum.topology.web;

import java.net.URI;
import java.util.List;

import com.articulorum.topology.model.AddMemberRequest;
import com.articulorum.topology.model.BuildRequest;
import com.articulorum.topology.model.BuildResult;
import com.articulorum.topology.model.Topology;
import com.articulorum.topology.model.TopologySummary;
import com.articulorum.topology.service.InvalidTopologyException;
import com.articulorum.topology.service.TopologyBuilder;
import com.articulorum.topology.service.TopologyRegistry;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * The topology API: list, read, create, replace and delete topologies, and build their PCDM
 * resources in the repository.
 *
 * <p>The caller's {@code Authorization} header is passed on to the platform, so Web Access
 * Control applies to everything the service reads and writes on the caller's behalf.</p>
 */
@Validated
@RestController
@RequestMapping(path = "/topologies", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Topologies", description = "PCDM structures and their instantiation")
public class TopologyController {

    private final TopologyRegistry registry;

    private final TopologyBuilder builder;

    /**
     * Creates the controller.
     *
     * @param registry the topology registry
     * @param builder  builds topology instances
     */
    public TopologyController(final TopologyRegistry registry, final TopologyBuilder builder) {
        this.registry = registry;
        this.builder = builder;
    }

    /**
     * Lists every topology.
     *
     * @param authorization the caller's credentials
     * @return built-in topologies followed by custom ones
     */
    @GetMapping
    @Operation(operationId = "listTopologies", summary = "List the topologies")
    public List<TopologySummary> list(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
            final String authorization) {
        return registry.list(authorization).stream().map(TopologySummary::of).toList();
    }

    /**
     * Returns a topology.
     *
     * @param id            the identifier
     * @param authorization the caller's credentials
     * @return the topology
     */
    @GetMapping("/{id}")
    @Operation(operationId = "getTopology", summary = "Read a topology")
    public Topology get(@PathVariable("id") final String id,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) final String authorization) {
        return registry.get(id, authorization);
    }

    /**
     * Creates a custom topology.
     *
     * @param topology      the topology
     * @param authorization the caller's credentials
     * @return 201 with the stored topology
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createTopology", summary = "Create a custom topology")
    public ResponseEntity<Topology> create(@RequestBody final Topology topology,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) final String authorization) {
        if (topology != null && topology.id() != null && exists(topology.id(), authorization)) {
            throw new InvalidTopologyException(List.of("A topology " + topology.id() + " already exists"));
        }
        final Topology saved = registry.save(topology, authorization);
        return ResponseEntity.created(URI.create("topologies/" + saved.id())).body(saved);
    }

    /**
     * Creates or replaces a custom topology.
     *
     * @param id            the identifier (must match the body's)
     * @param topology      the topology
     * @param authorization the caller's credentials
     * @return the stored topology
     */
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "saveTopology", summary = "Create or replace a custom topology")
    public Topology save(@PathVariable("id") final String id, @RequestBody final Topology topology,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) final String authorization) {
        if (topology == null || !id.equals(topology.id())) {
            throw new InvalidTopologyException(List.of("The body's id must be " + id));
        }
        return registry.save(topology, authorization);
    }

    /**
     * Deletes a custom topology (resources built from it stay).
     *
     * @param id            the identifier
     * @param authorization the caller's credentials
     * @return 204
     */
    @DeleteMapping("/{id}")
    @Operation(operationId = "deleteTopology", summary = "Delete a custom topology")
    public ResponseEntity<Void> delete(@PathVariable("id") final String id,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) final String authorization) {
        registry.delete(id, authorization);
        return ResponseEntity.noContent().build();
    }

    /**
     * Builds the PCDM resources of a topology.
     *
     * @param id            the topology
     * @param request       where and what to build
     * @param authorization the caller's credentials
     * @return 201 with the created resources
     */
    @PostMapping(path = "/{id}/instances", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "buildTopology", summary = "Build a topology instance in the repository")
    public ResponseEntity<BuildResult> build(@PathVariable("id") final String id,
            @Valid @RequestBody final BuildRequest request,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) final String authorization) {
        final BuildResult result = builder.build(registry.get(id, authorization), request, authorization);
        return ResponseEntity.status(201).body(result);
    }

    /**
     * Adds a child to a resource built from a topology.
     *
     * @param id            the topology
     * @param request       the parent and child node
     * @param authorization the caller's credentials
     * @return 201 with the created resources
     */
    @PostMapping(path = "/{id}/members", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "addTopologyMember", summary = "Add a child to a topology instance")
    public ResponseEntity<BuildResult> addMember(@PathVariable("id") final String id,
            @Valid @RequestBody final AddMemberRequest request,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) final String authorization) {
        final BuildResult result = builder.addMember(registry.get(id, authorization), request, authorization);
        return ResponseEntity.status(201).body(result);
    }

    private boolean exists(final String id, final String authorization) {
        try {
            registry.get(id, authorization);
            return true;
        } catch (com.articulorum.topology.service.TopologyNotFoundException e) {
            return false;
        }
    }

}
