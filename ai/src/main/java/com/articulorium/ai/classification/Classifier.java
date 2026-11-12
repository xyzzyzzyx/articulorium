package com.articulorium.ai.classification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.articulorium.ai.AgentException;
import com.articulorium.ai.AgentException.Reason;
import com.articulorium.ai.Capability;
import com.articulorium.ai.CapabilityAgent;
import com.articulorium.ai.config.AiProperties;
import com.articulorium.ai.model.AgentModel;
import com.articulorium.ai.model.AgentPrompt;
import com.articulorium.ai.model.Prompts;
import com.articulorium.ai.platform.AgentAccess;
import com.articulorium.ai.topology.TopologyAssistant;
import com.articulorium.ai.topology.TopologySummary;
import com.articulorium.ai.classification.Classification.Placement;
import com.articulorium.ai.classification.Classification.TopologyKind;
import com.articulorium.ai.classification.StructureCatalog.Entry;
import com.articulorium.ai.classification.StructureCatalog.Kind;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Classifies a harvested record against the community structure and the topologies, as the caller
 * (the harvest service, with its own credentials) may read them.
 *
 * <p>The model chooses among the collections and topologies it is shown, by their short ids; an
 * answer naming anything else is not trusted and becomes {@link Placement#NONE} with no confidence,
 * so a person decides. New communities, collections and topologies are only proposed (title and
 * description): they are created when a person accepts the proposal.</p>
 */
@Service
@RegisterReflectionForBinding(Classifier.Answer.class)
public class Classifier extends CapabilityAgent {

    private final AgentModel model;

    private final AgentAccess access;

    private final StructureCatalog catalogs;

    private final AiProperties properties;

    /**
     * Creates the classifier.
     *
     * @param model      the chat model
     * @param access     the access check
     * @param catalogs   the community structures and topologies
     * @param properties the agent settings
     */
    public Classifier(final AgentModel model, final AgentAccess access, final StructureCatalog catalogs,
            final AiProperties properties) {
        super(Capability.CLASSIFICATION, properties);
        this.model = model;
        this.access = access;
        this.catalogs = catalogs;
        this.properties = properties;
    }

    /**
     * Classifies a record.
     *
     * @param request       the record
     * @param authorization the caller's {@code Authorization} header
     * @param locale        the language of the rationale and of proposed titles
     * @return the classification
     */
    public Classification classify(final ClassificationRequest request, @Nullable final String authorization,
            final Locale locale) {
        model.require(Capability.CLASSIFICATION);
        if (!StringUtils.hasText(request.context())) {
            throw new AgentException(Reason.INVALID_REQUEST, "Name the container the record is ingested into (context)");
        }
        if (request.titles().isEmpty() && request.descriptions().isEmpty() && request.subjects().isEmpty()) {
            throw new AgentException(Reason.INVALID_REQUEST, "The record has no title, description or subject");
        }
        access.requireWrite(request.context(), authorization);
        final StructureCatalog.Catalog catalog = catalogs.catalog(authorization);
        final String user = Prompts.data("communities-and-collections", structure(catalog))
            + Prompts.data("topologies", topologies(catalog))
            + Prompts.data("record", record(request))
            + "Classify the record.\n";
        final Answer answer = model.ask(Capability.CLASSIFICATION, AgentPrompt.of(system(locale), user), Answer.class);
        return resolve(answer, catalog, model.describe().orElse(null));
    }

    private Classification resolve(final Answer answer, final StructureCatalog.Catalog catalog,
            @Nullable final String modelName) {
        double confidence = clamp(answer.confidence());
        final String rationale = answer.rationale() == null ? "" : Prompts.clip(answer.rationale(), 600);
        Placement placement = answer.placement() == null ? Placement.NONE : answer.placement();
        Classification.Ref collection = null;
        Classification.Ref community = null;
        Classification.NewStructure newCommunity = null;
        Classification.NewStructure newCollection = null;
        switch (placement) {
            case EXISTING_COLLECTION -> {
                final Optional<Entry> entry = catalog.find(answer.collection(), Kind.COLLECTION);
                if (entry.isPresent()) {
                    collection = ref(entry.get());
                } else {
                    placement = Placement.NONE;
                }
            }
            case NEW_COLLECTION -> {
                final Optional<Entry> entry = catalog.find(answer.community(), Kind.COMMUNITY);
                newCollection = structure(answer.newCollection());
                if (entry.isPresent() && newCollection != null) {
                    community = ref(entry.get());
                } else {
                    placement = Placement.NONE;
                    newCollection = null;
                }
            }
            case NEW_COMMUNITY -> {
                final Optional<Entry> entry = catalog.find(answer.community(), Kind.COMMUNITY);
                newCommunity = structure(answer.newCommunity());
                newCollection = structure(answer.newCollection());
                if (newCommunity == null || newCollection == null
                        || StringUtils.hasText(answer.community()) && entry.isEmpty()) {
                    placement = Placement.NONE;
                    newCommunity = null;
                    newCollection = null;
                } else {
                    community = entry.map(Classifier::ref).orElse(null);
                }
            }
            case NONE -> {
                // a person decides
            }
            default -> placement = Placement.NONE;
        }
        if (placement == Placement.NONE && answer.placement() != Placement.NONE) {
            // the model named something it was not shown: its confidence means nothing
            confidence = 0;
        }
        final List<Classification.Alternative> alternatives = new ArrayList<>();
        for (final AnswerAlternative alternative : answer.alternatives() == null ? List.<AnswerAlternative>of()
                : answer.alternatives()) {
            if (alternative == null || alternatives.size() >= 3) {
                continue;
            }
            final Classification.Ref ref = catalog.find(alternative.collection(), Kind.COLLECTION)
                .map(Classifier::ref).orElse(null);
            final Classification.Ref chosen = collection;
            if (ref != null && (chosen == null || !chosen.path().equals(ref.path()))
                    && alternatives.stream().noneMatch(other -> other.collection().path().equals(ref.path()))) {
                alternatives.add(new Classification.Alternative(ref, clamp(alternative.confidence())));
            }
        }
        return new Classification(placement, collection, community, newCommunity, newCollection,
            topology(answer, catalog), confidence, rationale, alternatives, modelName);
    }

    private static Classification.TopologyChoice topology(final Answer answer, final StructureCatalog.Catalog catalog) {
        final TopologyKind kind = answer.topologyDecision() == null ? TopologyKind.NONE : answer.topologyDecision();
        if (kind == TopologyKind.EXISTING) {
            final Optional<TopologySummary> match = catalog.topologies().stream()
                .filter(topology -> topology.id().equals(answer.topology())).findFirst();
            if (match.isPresent()) {
                return new Classification.TopologyChoice(TopologyKind.EXISTING, match.get().id(), match.get().name(), null);
            }
        } else if (kind == TopologyKind.NEW && answer.newTopology() != null
                && StringUtils.hasText(answer.newTopology().title())) {
            return new Classification.TopologyChoice(TopologyKind.NEW, null, Prompts.clip(answer.newTopology().title(), 120),
                answer.newTopology().description() == null ? null : Prompts.clip(answer.newTopology().description(), 500));
        }
        return new Classification.TopologyChoice(TopologyKind.NONE, null, null, null);
    }

    private static Classification.NewStructure structure(final AnswerStructure proposed) {
        if (proposed == null || !StringUtils.hasText(proposed.title())) {
            return null;
        }
        final String title = Prompts.clip(proposed.title(), 160);
        final String slug = TopologyAssistant.slugOf(title);
        return new Classification.NewStructure(title,
            proposed.description() == null ? null : Prompts.clip(proposed.description(), 500),
            slug.isEmpty() ? "collection" : slug);
    }

    private static Classification.Ref ref(final Entry entry) {
        return new Classification.Ref(entry.path(), entry.title(), entry.trail());
    }

    private static String structure(final StructureCatalog.Catalog catalog) {
        if (catalog.structure().isEmpty()) {
            return "(none yet)";
        }
        final StringBuilder out = new StringBuilder();
        for (final Entry entry : catalog.structure()) {
            out.append(entry.id()).append(entry.kind() == Kind.COMMUNITY ? " community " : " collection ")
                .append(entry.trail());
            if (!entry.description().isEmpty()) {
                out.append(" — ").append(entry.description());
            }
            if (!entry.subjects().isEmpty()) {
                out.append(" [").append(String.join("; ", entry.subjects())).append(']');
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String topologies(final StructureCatalog.Catalog catalog) {
        if (catalog.topologies().isEmpty()) {
            return "(none)";
        }
        final StringBuilder out = new StringBuilder();
        for (final TopologySummary topology : catalog.topologies()) {
            out.append(topology.id()).append(": ").append(topology.name());
            if (StringUtils.hasText(topology.description())) {
                out.append(" — ").append(Prompts.clip(topology.description(), 200));
            }
            out.append('\n');
        }
        return out.toString();
    }

    private String record(final ClassificationRequest request) {
        final StringBuilder out = new StringBuilder();
        line(out, "title", request.titles());
        line(out, "description", request.descriptions());
        line(out, "subjects", request.subjects());
        line(out, "creators", request.creators());
        line(out, "dates", request.dates());
        line(out, "genre", request.genres());
        line(out, "rdf types", request.types());
        line(out, "sets in its source", request.sets());
        line(out, "file types", request.mediaTypes());
        return Prompts.clip(out.toString(), properties.getClassification().getMaxRecordLength());
    }

    private static void line(final StringBuilder out, final String name, final List<String> values) {
        if (!values.isEmpty()) {
            out.append(name).append(": ").append(String.join("; ", values.stream().limit(20).toList())).append('\n');
        }
    }

    private static String system(final Locale locale) {
        return Prompts.RULES + """

            Task: decide where a record harvested from another repository belongs in this repository.
            Communities (ids m1, m2 ...) hold sub-communities and collections; collections (ids c1, c2 ...) \
            hold items. Choose:
            - EXISTING_COLLECTION with the collection id when an existing collection clearly fits;
            - NEW_COLLECTION with the community id and a new collection (title, description) when the \
            record belongs in an existing community but no collection fits;
            - NEW_COMMUNITY with a new community and a new collection in it (and the id of the parent \
            community, or none for the top level) when nothing fits;
            - NONE when the record cannot be placed with any confidence.
            Prefer existing collections; propose new ones only for material that clearly needs them, \
            with general titles that other records could share (for example "Theses", not one thesis).
            Choose the topology of the record's item: EXISTING with its id when one fits (a book, an \
            article, a postcard ...), NONE for a simple item, NEW (name, description) only for a kind \
            of material no topology covers.
            Give alternatives: up to three other existing collections that could fit, with confidences.
            Confidence is between 0 and 1: 0.9 or more only when the fit is unambiguous.
            """ + "Write the rationale and proposed titles and descriptions in " + Prompts.language(locale) + ".\n";
    }

    private static double clamp(final Double value) {
        return value == null || value.isNaN() ? 0 : Math.max(0, Math.min(1, value));
    }

    /**
     * A new community, collection or topology the model proposes.
     *
     * @param title       its title or name
     * @param description its description
     */
    public record AnswerStructure(
            @JsonPropertyDescription("Title (or name)") String title,
            @JsonPropertyDescription("One sentence") String description) {
    }

    /**
     * Another collection.
     *
     * @param collection its id
     * @param confidence how well it fits
     */
    public record AnswerAlternative(
            @JsonPropertyDescription("Collection id, for example c4") String collection,
            @JsonPropertyDescription("0 to 1") Double confidence) {
    }

    /**
     * The model's answer.
     *
     * @param placement        where the record goes
     * @param collection       the existing collection's id
     * @param community        the community's id (new collection or new community)
     * @param newCommunity     the new community
     * @param newCollection    the new collection
     * @param topologyDecision existing, new or none
     * @param topology         the existing topology's id
     * @param newTopology      the new topology
     * @param confidence       how sure, 0 to 1
     * @param rationale        why
     * @param alternatives     other collections
     */
    public record Answer(
            @JsonPropertyDescription("EXISTING_COLLECTION, NEW_COLLECTION, NEW_COMMUNITY or NONE") Placement placement,
            @JsonPropertyDescription("Id of the existing collection, for example c3") String collection,
            @JsonPropertyDescription("Id of the community a new collection or community goes in, for example m2") String community,
            @JsonPropertyDescription("The new community (NEW_COMMUNITY)") AnswerStructure newCommunity,
            @JsonPropertyDescription("The new collection (NEW_COLLECTION, NEW_COMMUNITY)") AnswerStructure newCollection,
            @JsonPropertyDescription("EXISTING, NEW or NONE") TopologyKind topologyDecision,
            @JsonPropertyDescription("Id of the existing topology") String topology,
            @JsonPropertyDescription("The new topology (NEW)") AnswerStructure newTopology,
            @JsonPropertyDescription("0 to 1") Double confidence,
            @JsonPropertyDescription("Why, in one or two sentences") String rationale,
            @JsonPropertyDescription("Up to three other existing collections") List<AnswerAlternative> alternatives) {
    }

}
