package com.articulorium.harvest.classify;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.articulorium.harvest.core.HarvestedRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns the topology a classification proposes (a name and a description) into a topology that
 * exists, for automatic acceptance:
 *
 * <ol>
 * <li>a topology of that id (the name's slug) or name is used as it is, so records proposing the
 * same topology share the one the first of them created;</li>
 * <li>otherwise the AI agent is asked for the topology of the material: it may find that an
 * existing topology fits after all, or draft a new one;</li>
 * <li>a draft is saved with the topology API, under the proposed name, when the API's own rules
 * accept it and its root is an object (a harvested record becomes one item).</li>
 * </ol>
 *
 * <p>Anything else (no draft, a draft with problems, a refusal) is a {@link
 * TopologyClient.PlacementException}: the record then waits for review, where a person drafts the
 * topology in the editor. The agent only drafts; the topology is saved with the harvester's
 * credentials, so the platform's Web Access Control decides whether it may add topologies.</p>
 */
public class TopologyProposer {

    private static final Logger LOG = LoggerFactory.getLogger(TopologyProposer.class);

    /** Longest description of the material sent to the agent. */
    static final int MAX_DESCRIPTION = 2000;

    private final AgentClient agent;

    private final TopologyClient topologies;

    private final MeterRegistry meters;

    /**
     * Creates the proposer.
     *
     * @param agent      the AI agent
     * @param topologies the topology API
     * @param meters     the meter registry
     */
    public TopologyProposer(final AgentClient agent, final TopologyClient topologies, final MeterRegistry meters) {
        this.agent = agent;
        this.topologies = topologies;
        this.meters = meters;
    }

    /**
     * Returns the existing topology to use for a proposed one, creating it when there is none.
     *
     * @param sourceId      the source of the record (for the log and the metric)
     * @param context       the container the record was ingested into
     * @param proposed      the proposed topology (its name and description)
     * @param record        the record, as an example of the material
     * @param authorization the harvester's credentials
     * @return the topology, as an existing topology
     * @throws TopologyClient.PlacementException  when no valid topology could be found or created
     * @throws AgentClient.AgentUnavailableException when the agent cannot draft one
     */
    public Suggestion.Topology resolve(final String sourceId, final String context, final Suggestion.Topology proposed,
            final HarvestedRecord record, final String authorization) {
        final String name = proposed.name() == null ? "" : proposed.name().strip();
        if (name.isEmpty()) {
            throw new TopologyClient.PlacementException(422, "The proposed topology has no name");
        }
        final String id = idOf(name);
        final Optional<Suggestion.Topology> known = topologies.find(id, name, authorization);
        if (known.isPresent()) {
            count(sourceId, "reused");
            return known.get();
        }
        final TopologyProposal proposal = agent.suggestTopology(context, describe(context, sourceId, proposed, record));
        if (TopologyProposal.EXISTING.equals(proposal.recommendation())) {
            final String existing = proposal.existing() == null ? null : proposal.existing().id();
            final Optional<Suggestion.Topology> match = existing == null ? Optional.empty()
                : topologies.find(existing, null, authorization).filter(found -> found.id().equals(existing));
            count(sourceId, match.isPresent() ? "reused" : "failed");
            return match.orElseThrow(() -> new TopologyClient.PlacementException(422,
                "The agent recommended a topology that does not exist for " + name));
        }
        final JsonNode draft = proposal.draft();
        if (draft == null || !draft.path("root").isObject()) {
            count(sourceId, "failed");
            throw new TopologyClient.PlacementException(422, "The agent drafted no topology for " + name);
        }
        if (!proposal.errors().isEmpty()) {
            count(sourceId, "invalid");
            throw new TopologyClient.PlacementException(422, "The topology drafted for " + name + " is not valid: "
                + String.join("; ", proposal.errors().stream().limit(5).toList()));
        }
        final JsonNode type = draft.path("root").path("pcdmType");
        if (!type.isString() || !"Object".equals(type.asString())) {
            count(sourceId, "invalid");
            throw new TopologyClient.PlacementException(422, "The topology drafted for " + name
                + " describes a collection, not an item");
        }
        final ObjectNode topology = (ObjectNode) draft.deepCopy();
        topology.put("id", id);
        topology.put("name", name);
        topology.put("builtIn", false);
        if (!topology.path("description").isString() && proposed.description() != null) {
            topology.put("description", proposed.description());
        }
        final String created;
        try {
            created = topologies.create(topology, authorization);
        } catch (TopologyClient.PlacementException e) {
            // another harvest may have created it meanwhile
            final Optional<Suggestion.Topology> raced = topologies.find(id, null, authorization);
            count(sourceId, raced.isPresent() ? "reused" : "failed");
            return raced.orElseThrow(() -> e);
        }
        count(sourceId, "created");
        LOG.info("Created the topology {} ({}) proposed for harvested records of {}", created, name, sourceId);
        return new Suggestion.Topology(Suggestion.Topology.EXISTING, created, name, null);
    }

    /**
     * Returns the topology an automatically accepted record's item is built from: the suggested
     * one when its root is an object. A topology whose root is a collection (a journal, a
     * newspaper title) describes a whole series, which one harvested record is not: the record
     * then becomes a plain object.
     *
     * @param id            the suggested existing topology, {@code null} for none
     * @param authorization the harvester's credentials
     * @return the topology's id, or {@code null} for a plain object
     * @throws TopologyClient.PlacementException when the topology API cannot answer
     */
    public String itemTopology(final String id, final String authorization) {
        if (id == null) {
            return null;
        }
        final JsonNode type = topologies.get(id, authorization).map(topology -> topology.path("root").path("pcdmType"))
            .orElse(null);
        if (type != null && type.isString() && "Object".equals(type.asString())) {
            return id;
        }
        LOG.info("The topology {} does not describe a single item; the record becomes a plain object", id);
        return null;
    }

    /**
     * Describes the material for the agent: the proposal, and what the record that prompted it is.
     */
    private static String describe(final String context, final String sourceId, final Suggestion.Topology proposed,
            final HarvestedRecord record) {
        final StringBuilder out = new StringBuilder(proposed.name().strip());
        if (proposed.description() != null && !proposed.description().isBlank()) {
            out.append(": ").append(proposed.description().strip());
        }
        out.append("\nThe material is harvested from another repository. Each harvested record becomes one item: "
            + "the root of the topology is an Object, with only the levels and files such a record needs.");
        final AgentClient.Request example = AgentClient.request(context, sourceId, record);
        line(out, "Kinds of an example record", example.genres());
        line(out, "Its file types", example.mediaTypes());
        line(out, "Its sets in the source", example.sets());
        return out.length() > MAX_DESCRIPTION ? out.substring(0, MAX_DESCRIPTION) : out.toString();
    }

    private static void line(final StringBuilder out, final String name, final List<String> values) {
        if (!values.isEmpty()) {
            out.append('\n').append(name).append(": ").append(String.join("; ", values.stream().limit(10).toList()));
        }
    }

    /**
     * Derives the id of a proposed topology from its name, as the topology API wants ids: lower-case
     * letters, digits, hyphens and underscores, starting with a letter or digit, at most 64.
     *
     * @param name the name
     * @return the id, never empty
     */
    static String idOf(final String name) {
        String id = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^[-_]+|-+$", "");
        if (id.length() > 64) {
            id = id.substring(0, 64).replaceAll("-+$", "");
        }
        return id.isEmpty() ? "harvested" : id;
    }

    private void count(final String sourceId, final String outcome) {
        meters.counter("harvest.topologies", "source", sourceId, "outcome", outcome).increment();
    }

}
