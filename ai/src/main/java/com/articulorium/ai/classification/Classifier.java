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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Classifies a harvested record against the community structure and the topologies, as the caller
 * (the harvest service, with its own credentials) may read them.
 *
 * <p>The model chooses among the collections and topologies it is shown, by their short ids. Ids it
 * was not shown are never used: a proposal that stands without them is kept, otherwise the model
 * is told what is wrong and asked once more (a classification is two model calls at most), and an
 * answer that still cannot be used becomes {@link Placement#NONE} with no confidence, so a person
 * decides. New communities, collections and topologies are only proposed (title and
 * description): the caller creates them, when a person accepts the proposal or, for the harvest
 * service's automatic acceptance, at once (a proposed topology from a draft of the
 * {@code TopologyAssistant}).</p>
 */
@Service
@RegisterReflectionForBinding(Classifier.Answer.class)
public class Classifier extends CapabilityAgent {

    private static final Logger LOG = LoggerFactory.getLogger(Classifier.class);

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
        final String system = system(locale);
        final String user = Prompts.data("communities-and-collections", structure(catalog))
            + Prompts.data("topologies", topologies(catalog))
            + Prompts.data("record", record(request))
            + "Classify the record. " + choices(catalog) + "\n";
        final String modelName = model.describe().orElse(null);
        Answer answer;
        boolean asked = false;
        try {
            answer = model.ask(Capability.CLASSIFICATION, AgentPrompt.of(system, user), Answer.class);
        } catch (AgentException e) {
            if (e.getReason() != Reason.INVALID_OUTPUT) {
                throw e;
            }
            // small models slip now and then: an unreadable answer is asked for once more
            asked = true;
            answer = model.ask(Capability.CLASSIFICATION, AgentPrompt.of(system, user), Answer.class);
        }
        final Resolution first = resolve(answer, catalog, request, false, modelName);
        if (first.problem() == null) {
            return first.classification();
        }
        // what can be repaired without the model is: a model call takes minutes on a CPU
        final Classification repaired = resolve(answer, catalog, request, true, modelName).classification();
        if (repaired.placement() != Placement.NONE) {
            LOG.info("Repaired the classification of {} of {} ({})", request.identifier(), request.source(),
                first.problem());
            return repaired;
        }
        if (!asked) {
            // the model is told what is wrong, once; a classification is two model calls at most
            LOG.info("The classification of {} of {} could not be used ({}); asking the model to correct it",
                request.identifier(), request.source(), first.problem());
            try {
                final Answer corrected = model.ask(Capability.CLASSIFICATION, AgentPrompt.of(system, user
                    + Prompts.data("problem-with-your-previous-answer", first.problem())
                    + "Answer again without this problem.\n"), Answer.class);
                final Resolution second = resolve(corrected, catalog, request, true, modelName);
                if (second.classification().placement() != Placement.NONE) {
                    return second.classification();
                }
            } catch (AgentException e) {
                LOG.info("The model did not correct the classification of {} of {}: {}", request.identifier(),
                    request.source(), e.getReason());
            }
        }
        LOG.warn("No usable placement for {} of {}: {}", request.identifier(), request.source(), first.problem());
        return repaired;
    }

    /**
     * Says which answers the structure allows, so a small model does not have to work it out: a
     * repository without communities can only get a new community with its collection.
     */
    private static String choices(final StructureCatalog.Catalog catalog) {
        final List<String> communities = catalog.structure().stream().filter(entry -> entry.kind() == Kind.COMMUNITY)
            .map(Entry::id).toList();
        final List<String> collections = catalog.structure().stream().filter(entry -> entry.kind() == Kind.COLLECTION)
            .map(Entry::id).toList();
        if (communities.isEmpty()) {
            return "There are no communities or collections yet, so answer NEW_COMMUNITY: give newCommunity (the "
                + "broad field of the record, for example \"Computer Science\") and newCollection (a narrower "
                + "grouping that other records will share, for example \"Digital Libraries\"), and leave collection "
                + "and community empty.";
        }
        return "The only community ids are " + String.join(", ", communities) + "; "
            + (collections.isEmpty() ? "there are no collections yet, so EXISTING_COLLECTION is not possible"
                : "the only collection ids are " + String.join(", ", collections))
            + ". Never use an id that is not in these lists.";
    }

    /**
     * Turns the model's answer into a classification, trusting only what it was shown.
     *
     * <p>Ids the model was not shown are never used. A proposal that is complete apart from such an
     * id is kept without it: a new community with an unknown parent goes to the top level, a new
     * collection whose community is unknown goes to the new community the answer also proposes, and
     * an "existing" collection that does not exist counts as the proposal the answer carries. When
     * nothing usable is left the classification is {@link Placement#NONE} with no confidence, and
     * the problem is reported for the model to correct.</p>
     *
     * @param lenient whether, in a repository without any community, a single proposed title may
     *                name both the new community and its collection
     */
    private Resolution resolve(final Answer answer, final StructureCatalog.Catalog catalog,
            final ClassificationRequest request, final boolean lenient, @Nullable final String modelName) {
        final Placement asked = answer.placement() == null ? Placement.NONE : answer.placement();
        final Optional<Entry> collectionEntry = catalog.find(answer.collection(), Kind.COLLECTION);
        final Optional<Entry> communityEntry = catalog.find(answer.community(), Kind.COMMUNITY);
        Classification.NewStructure newCommunity = structure(answer.newCommunity());
        Classification.NewStructure newCollection = structure(answer.newCollection());
        Placement placement = Placement.NONE;
        Classification.Ref collection = null;
        Classification.Ref community = null;
        String problem = null;
        if (asked == Placement.EXISTING_COLLECTION && collectionEntry.isPresent()) {
            placement = Placement.EXISTING_COLLECTION;
            collection = ref(collectionEntry.get());
        } else if (asked != Placement.NONE) {
            final boolean noCommunities = catalog.structure().stream().noneMatch(entry -> entry.kind() == Kind.COMMUNITY);
            if (lenient && noCommunities) {
                newCollection = newCollection == null ? newCommunity : newCollection;
                newCommunity = newCommunity == null ? newCollection : newCommunity;
            }
            if (newCollection == null) {
                problem = asked == Placement.EXISTING_COLLECTION
                    ? "collection \"" + clipped(answer.collection()) + "\" is not one of the listed collection ids"
                    : "newCollection (with a title) is missing";
            } else if (communityEntry.isPresent() && (asked != Placement.NEW_COMMUNITY || newCommunity == null)) {
                placement = Placement.NEW_COLLECTION;
                community = ref(communityEntry.get());
            } else if (newCommunity != null) {
                placement = Placement.NEW_COMMUNITY;
                community = communityEntry.map(Classifier::ref).orElse(null);
            } else {
                problem = "community \"" + clipped(answer.community()) + "\" is not one of the listed community ids, "
                    + "and newCommunity (with a title) is missing";
            }
        }
        if (placement != Placement.NEW_COMMUNITY) {
            newCommunity = null;
        }
        if (placement != Placement.NEW_COLLECTION && placement != Placement.NEW_COMMUNITY) {
            newCollection = null;
        }
        // the model named something it was not shown: its confidence means nothing
        final double confidence = placement == Placement.NONE && asked != Placement.NONE ? 0 : clamp(answer.confidence());
        final String rationale = answer.rationale() == null ? "" : Prompts.clip(answer.rationale(), 600);
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
        return new Resolution(new Classification(placement, collection, community, newCommunity, newCollection,
            topology(answer, catalog, request, newCommunity, newCollection), confidence, rationale, alternatives,
            modelName), problem);
    }

    private static String clipped(final String value) {
        return value == null ? "" : Prompts.clip(value, 40);
    }

    /**
     * A classification, and what made the model's answer unusable (when its placement could not be
     * kept).
     */
    private record Resolution(Classification classification, @Nullable String problem) {
    }

    private static Classification.TopologyChoice topology(final Answer answer, final StructureCatalog.Catalog catalog,
            final ClassificationRequest request, final Classification.NewStructure newCommunity,
            final Classification.NewStructure newCollection) {
        final TopologyKind kind = answer.topologyDecision() == null ? TopologyKind.NONE : answer.topologyDecision();
        if (kind == TopologyKind.EXISTING) {
            final Optional<TopologySummary> match = catalog.topologies().stream()
                .filter(topology -> topology.id().equals(answer.topology())).findFirst();
            if (match.isPresent()) {
                return new Classification.TopologyChoice(TopologyKind.EXISTING, match.get().id(), match.get().name(), null);
            }
        } else if (kind == TopologyKind.NEW && answer.newTopology() != null
                && StringUtils.hasText(answer.newTopology().title())
                && !namesASubject(answer.newTopology().title(), request, newCommunity, newCollection)) {
            return new Classification.TopologyChoice(TopologyKind.NEW, null, Prompts.clip(answer.newTopology().title(), 120),
                answer.newTopology().description() == null ? null : Prompts.clip(answer.newTopology().description(), 500));
        }
        return new Classification.TopologyChoice(TopologyKind.NONE, null, null, null);
    }

    /**
     * Tells whether a proposed topology is named after what the record is about rather than after
     * its form: one of the record's subjects, or the proposed community or collection. A topology
     * is a structure of parts and files, so such a proposal is a subject in the wrong field.
     */
    private static boolean namesASubject(final String topology, final ClassificationRequest request,
            final Classification.NewStructure newCommunity, final Classification.NewStructure newCollection) {
        final String name = topology.strip();
        return request.subjects().stream().anyMatch(subject -> subject.strip().equalsIgnoreCase(name))
            || newCommunity != null && newCommunity.title().equalsIgnoreCase(name)
            || newCollection != null && newCollection.title().equalsIgnoreCase(name);
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
            Choose the topology of the record's item. A topology is the form of an item (its parts \
            and files: a book of pages, a recording of tracks), never what it is about: a subject, a \
            field or a research topic is not a topology. Most harvested records are single documents: \
            answer NONE for them. Answer EXISTING with its id only when the record has that form, and \
            NEW (name, description) only for a form of material that no topology covers.
            Alternatives are optional: up to three other existing collections that could fit.
            Be brief: the rationale is one short sentence, descriptions are one short sentence.
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
