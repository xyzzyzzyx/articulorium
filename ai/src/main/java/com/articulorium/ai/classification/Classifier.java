package com.articulorium.ai.classification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

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
import com.articulorium.domain.rdf.ResourcePaths;
import com.fasterxml.jackson.annotation.JsonFormat;
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

    /** Between the levels of a title written as a path. */
    private static final Pattern PATH_SEPARATOR = Pattern.compile("\\s*[>›»→]\\s*|\\s+/\\s+");

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
        return classify(request, authorization, locale, false);
    }

    /**
     * Classifies a record, against the structure read again when asked: a caller that has just
     * created communities or collections asks for it, so the next record sees them.
     *
     * @param request       the record
     * @param authorization the caller's {@code Authorization} header
     * @param locale        the language of the rationale and of proposed titles
     * @param fresh         whether to read the structure again rather than reuse it
     * @return the classification
     */
    public Classification classify(final ClassificationRequest request, @Nullable final String authorization,
            final Locale locale, final boolean fresh) {
        model.require(Capability.CLASSIFICATION);
        if (!StringUtils.hasText(request.context())) {
            throw new AgentException(Reason.INVALID_REQUEST, "Name the container the record is ingested into (context)");
        }
        if (request.titles().isEmpty() && request.descriptions().isEmpty() && request.subjects().isEmpty()) {
            throw new AgentException(Reason.INVALID_REQUEST, "The record has no title, description or subject");
        }
        access.requireWrite(request.context(), authorization);
        final StructureCatalog.Catalog catalog = catalogs.catalog(authorization, fresh);
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
            return "There are no communities or collections yet, so answer NEW_COMMUNITY: give newCommunities, the "
                + "communities from the broadest field down to a narrower one (for example [{\"title\": \"Sciences\"}, "
                + "{\"title\": \"Computer Science\"}]), and newCollection, a narrower grouping that other records "
                + "will share (for example \"Digital Libraries\"); leave collection and community empty.";
        }
        return "The only community ids are " + String.join(", ", communities) + "; "
            + (collections.isEmpty() ? "there are no collections yet, so EXISTING_COLLECTION is not possible"
                : "the only collection ids are " + String.join(", ", collections))
            + ". Never use an id that is not in these lists. For a new collection or new communities, community is "
            + "the most specific existing community that fits, and newCommunities holds only the missing levels "
            + "below it.";
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
        Optional<Entry> communityEntry = catalog.find(answer.community(), Kind.COMMUNITY);
        List<Classification.NewStructure> newCommunities = structures(answer.newCommunities());
        // a collection titled with a path ("Computer Science > Preprints"): the path is communities
        final List<Classification.NewStructure> collectionPath = answer.newCollection() == null ? List.of()
            : structures(List.of(answer.newCollection()));
        Classification.NewStructure newCollection = collectionPath.isEmpty() ? null : collectionPath.getLast();
        final List<Classification.NewStructure> collectionLevels = collectionPath.isEmpty() ? List.of()
            : collectionPath.subList(0, collectionPath.size() - 1);
        // a collection named after this one record groups nothing: its subject's community names it
        String collectionProblem = null;
        if (newCollection != null && StructureNames.namesOneRecord(newCollection.title(), request.titles())) {
            collectionProblem = "newCollection \"" + clipped(newCollection.title()) + "\" names this one record; a "
                + "collection groups many records of one topic or kind (for example \"Information Retrieval\" or "
                + "\"Theses\"): choose an existing collection or give a general title";
            newCollection = lenient ? fallbackCollection(asked == Placement.NEW_COMMUNITY || communityEntry.isEmpty()
                ? newCommunities : List.of(), collectionLevels, communityEntry) : null;
        }
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
                if (newCollection == null && !newCommunities.isEmpty()) {
                    newCollection = newCommunities.getLast();
                }
                if (newCommunities.isEmpty() && newCollection != null) {
                    newCommunities = List.of(newCollection);
                }
            }
            if (newCollection == null) {
                problem = collectionProblem != null ? collectionProblem : asked == Placement.EXISTING_COLLECTION
                    ? "collection \"" + clipped(answer.collection()) + "\" is not one of the listed collection ids"
                    : "newCollection (with a title) is missing";
            } else if (communityEntry.isPresent() || !newCommunities.isEmpty()) {
                if (asked != Placement.NEW_COMMUNITY && communityEntry.isPresent()) {
                    // a new collection of an existing community: communities proposed besides are not wanted
                    newCommunities = List.of();
                }
                if (!collectionLevels.isEmpty()) {
                    final List<Classification.NewStructure> levels = new ArrayList<>(newCommunities);
                    levels.addAll(collectionLevels);
                    newCommunities = levels;
                }
                // proposed levels that exist already are the existing communities (and collection)
                Anchored anchored = anchor(catalog, communityEntry.orElse(null), newCommunities, newCollection);
                if (anchored.namedLikeItsCommunity()) {
                    // a collection is more specific than its community: one of the record's subjects names it
                    final Classification.NewStructure specific = lenient
                        ? subjectCollection(request, anchored.communityKey()) : null;
                    if (specific == null) {
                        problem = "newCollection \"" + clipped(newCollection.title()) + "\" is named like the "
                            + "community it goes in; a collection is more specific than its community: give a "
                            + "narrower collection title, or choose an existing collection";
                    } else {
                        anchored = anchor(catalog, communityEntry.orElse(null), newCommunities, specific);
                        newCollection = specific;
                    }
                }
                communityEntry = Optional.ofNullable(anchored.community());
                newCommunities = anchored.newCommunities();
                if (problem != null) {
                    newCommunities = List.of();
                } else if (anchored.collection() != null) {
                    placement = Placement.EXISTING_COLLECTION;
                    collection = ref(anchored.collection());
                } else if (newCommunities.isEmpty()) {
                    placement = Placement.NEW_COLLECTION;
                    community = ref(communityEntry.orElseThrow());
                } else {
                    placement = Placement.NEW_COMMUNITY;
                    community = communityEntry.map(Classifier::ref).orElse(null);
                }
            } else {
                problem = "community \"" + clipped(answer.community()) + "\" is not one of the listed community ids, "
                    + "and newCommunities (with titles) is missing";
            }
        }
        if (placement != Placement.NEW_COMMUNITY) {
            newCommunities = null;
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
        return new Resolution(new Classification(placement, collection, community, newCommunities, newCollection,
            topology(answer, catalog, request, newCommunities, newCollection), confidence, rationale, alternatives,
            modelName), problem);
    }

    /**
     * Places proposed communities on the existing structure, so that a community exists once.
     * From the parent (an existing community, or the top), a proposed level is:
     * <ul>
     * <li>dropped when it repeats the parent;</li>
     * <li>the community of its name (its key, see {@link StructureNames#key}) in the parent;</li>
     * <li>the collection of its name in the parent, which is then where the record goes;</li>
     * </ul>
     * and the next level is looked for in what it named. When none of these holds, the most
     * specific proposed level that is a community of its name anywhere else in the structure is
     * that community, and the levels before it are dropped (topics are not duplicated in other
     * branches). A path written from the top again starts
     * where its first level is. Of the levels left, those repeating a community above or another
     * level are dropped, so is a last level named like the collection when other levels hold it,
     * and at most {@code classification.max-new-communities} are kept, the more general ones (the
     * collection holds what is more specific). The collection is an existing one when the final
     * parent has a collection of its name.
     */
    private Anchored anchor(final StructureCatalog.Catalog catalog, @Nullable final Entry parent,
            final List<Classification.NewStructure> proposed, final Classification.NewStructure newCollection) {
        Entry at = parent;
        final List<Classification.NewStructure> levels = new ArrayList<>(proposed);
        // a path written from the top again ("Digital Libraries > Computation and Language > ..."
        // proposed inside Computation and Language): it starts where its first level is
        if (!levels.isEmpty()) {
            for (Entry ancestor = parent; ancestor != null; ancestor = parentOf(catalog, ancestor)) {
                if (same(ancestor, levels.getFirst())) {
                    at = parentOf(catalog, ancestor);
                    break;
                }
            }
        }
        while (!levels.isEmpty()) {
            final Classification.NewStructure level = levels.getFirst();
            if (at != null && same(at, level)) {
                levels.removeFirst();
                continue;
            }
            final Optional<Entry> community = child(catalog, at, Kind.COMMUNITY, level);
            if (community.isPresent()) {
                at = community.get();
                levels.removeFirst();
                continue;
            }
            final Optional<Entry> collection = child(catalog, at, Kind.COLLECTION, level);
            if (collection.isPresent()) {
                return new Anchored(at, List.of(), collection.get(), "", false);
            }
            // the most specific level that is a community elsewhere (not one above: a repeat) goes on from there
            final Set<String> lineage = new HashSet<>();
            for (Entry ancestor = at; ancestor != null; ancestor = parentOf(catalog, ancestor)) {
                lineage.add(ancestor.path());
            }
            Entry elsewhere = null;
            int index = levels.size() - 1;
            for (; index >= 0 && elsewhere == null; index--) {
                final Classification.NewStructure candidate = levels.get(index);
                elsewhere = catalog.structure().stream()
                    .filter(entry -> entry.kind() == Kind.COMMUNITY && !lineage.contains(entry.path())
                        && same(entry, candidate))
                    .findFirst().orElse(null);
            }
            if (elsewhere == null) {
                break;
            }
            at = elsewhere;
            levels.subList(0, index + 2).clear();
        }
        // a community does not repeat a community above it, nor another proposed level
        final Set<String> above = new HashSet<>();
        for (Entry ancestor = at; ancestor != null; ancestor = parentOf(catalog, ancestor)) {
            above.add(StructureNames.key(ancestor.title()));
            above.add(StructureNames.key(ResourcePaths.nameOf(ancestor.path())));
        }
        levels.removeIf(level -> !above.add(StructureNames.key(level.title())));
        if (!levels.isEmpty() && StructureNames.key(levels.getLast().title()).equals(StructureNames.key(newCollection.title()))
                && (levels.size() > 1 || at != null)) {
            levels.removeLast();
        }
        final int max = Math.max(1, properties.getClassification().getMaxNewCommunities());
        while (levels.size() > max) {
            levels.removeLast();
        }
        final Entry collection = levels.isEmpty() ? child(catalog, at, Kind.COLLECTION, newCollection).orElse(null) : null;
        // the community the new collection goes in: the last new level, or the existing community
        final String community = !levels.isEmpty() ? levels.getLast().title() : at == null ? null : at.title();
        final String communityKey = community == null ? "" : StructureNames.key(community);
        return new Anchored(at, List.copyOf(levels), collection, communityKey,
            collection == null && !communityKey.isEmpty() && communityKey.equals(StructureNames.key(newCollection.title())));
    }

    /**
     * A collection named after one of the record's subjects (the part after " - " of arXiv's
     * "Computer Science - Digital Libraries"): neither a classification code, nor named like the
     * community it goes in, nor after the record.
     */
    private static Classification.NewStructure subjectCollection(final ClassificationRequest request,
            final String communityKey) {
        for (final String subject : request.subjects()) {
            final int dash = subject == null ? -1 : subject.lastIndexOf(" - ");
            final String name = subject == null ? "" : (dash < 0 ? subject : subject.substring(dash + 3)).strip();
            final String key = StructureNames.key(name);
            if (!name.isEmpty() && !StructureNames.isCode(name) && !key.isEmpty() && !key.equals(communityKey)
                    && !StructureNames.namesOneRecord(name, request.titles())) {
                return structure(new AnswerStructure(name, null));
            }
        }
        return null;
    }

    /** The community or collection of a proposal's name in a community (or at the top). */
    private static Optional<Entry> child(final StructureCatalog.Catalog catalog, @Nullable final Entry parent,
            final Kind kind, final Classification.NewStructure proposed) {
        final String parentPath = parent == null ? null : parent.path();
        return catalog.structure().stream()
            .filter(entry -> entry.kind() == kind && Objects.equals(entry.parent(), parentPath) && same(entry, proposed))
            .findFirst();
    }

    /**
     * A general collection for a record whose proposed collection named it alone: the most specific
     * proposed community names it ("Information Retrieval", that level then being the collection),
     * or else the community it goes in, which {@link #anchor} then finds named like its community,
     * so that one of the record's subjects names it.
     */
    private static Classification.NewStructure fallbackCollection(final List<Classification.NewStructure> levels,
            final List<Classification.NewStructure> collectionLevels, final Optional<Entry> community) {
        if (!collectionLevels.isEmpty()) {
            return collectionLevels.getLast();
        }
        if (!levels.isEmpty()) {
            return levels.getLast();
        }
        return community.map(entry -> structure(new AnswerStructure(entry.title(), null))).orElse(null);
    }

    private static @Nullable Entry parentOf(final StructureCatalog.Catalog catalog, final Entry entry) {
        return entry.parent() == null ? null : catalog.structure().stream()
            .filter(candidate -> candidate.kind() == Kind.COMMUNITY && candidate.path().equals(entry.parent()))
            .findFirst().orElse(null);
    }

    /** Whether an existing community or collection is the one a proposal names (same key). */
    private static boolean same(final Entry entry, final Classification.NewStructure proposed) {
        final String key = StructureNames.key(proposed.title());
        return !key.isEmpty() && (StructureNames.key(entry.title()).equals(key)
            || StructureNames.key(ResourcePaths.nameOf(entry.path())).equals(key));
    }

    /**
     * Proposed communities placed on the existing structure.
     *
     * @param community      the most specific existing community, {@code null} for the top
     * @param newCommunities the levels to create below it
     * @param collection     the existing collection of the proposed collection's title, if any
     * @param communityKey   the key of the community a new collection goes in, empty at the top
     * @param namedLikeItsCommunity whether the new collection is named like that community
     */
    private record Anchored(@Nullable Entry community, List<Classification.NewStructure> newCommunities,
            @Nullable Entry collection, String communityKey, boolean namedLikeItsCommunity) {
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
            final ClassificationRequest request, final List<Classification.NewStructure> newCommunities,
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
                && !namesASubject(answer.newTopology().title(), request, newCommunities, newCollection)) {
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
            final List<Classification.NewStructure> newCommunities, final Classification.NewStructure newCollection) {
        final String name = topology.strip();
        return request.subjects().stream().anyMatch(subject -> subject.strip().equalsIgnoreCase(name))
            || newCommunities != null && newCommunities.stream().anyMatch(level -> level.title().equalsIgnoreCase(name))
            || newCollection != null && newCollection.title().equalsIgnoreCase(name);
    }

    /**
     * The proposed communities, from the more general to the more specific: a title written as a
     * path ({@code Sciences > Computer Science}, as the structure is shown to the model) is one
     * level per part, the description going with the last; untitled levels and a level repeating
     * the one before it are dropped.
     */
    private static List<Classification.NewStructure> structures(@Nullable final List<AnswerStructure> proposed) {
        final List<Classification.NewStructure> levels = new ArrayList<>();
        for (final AnswerStructure level : proposed == null ? List.<AnswerStructure>of() : proposed) {
            if (level == null || level.title() == null) {
                continue;
            }
            final List<String> parts = levelsOf(level.title());
            for (int i = 0; i < parts.size(); i++) {
                if (StructureNames.isCode(parts.get(i))) {
                    // a classification code from the record (H.3.7, cs.DL) is not a community
                    continue;
                }
                final Classification.NewStructure structure = structure(new AnswerStructure(parts.get(i),
                    i == parts.size() - 1 ? level.description() : null));
                if (structure != null && (levels.isEmpty() || !levels.getLast().slug().equals(structure.slug()))) {
                    levels.add(structure);
                }
            }
        }
        return levels;
    }

    /**
     * Splits a title written as a path into its levels: parts separated by {@code >}, {@code ›},
     * {@code »}, {@code →} or {@code /} between spaces ("Input/Output" stays one title).
     *
     * @param title the title
     * @return the parts, without blank ones
     */
    static List<String> levelsOf(final String title) {
        return Arrays.stream(PATH_SEPARATOR.split(title)).map(String::strip).filter(part -> !part.isEmpty())
            .toList();
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
            - NEW_COMMUNITY with new communities and a new collection in the last of them (and the id of \
            the existing community they go in, or none for the top level) when no community fits closely;
            - NONE when the record cannot be placed with any confidence.
            Communities form a hierarchy from general to specific: top-level communities are broad \
            fields (for example "Sciences", "Humanities", "Engineering"), each sub-community narrower \
            (for example Sciences > Computer Science > Information Science). Choose the most specific \
            existing community that fits, and propose in newCommunities, in order from general to \
            specific, only the levels missing below it: one level, or two or three when the record is \
            far from everything that exists. Never propose a narrow topic as a top-level community. \
            Each title names one level only: never write a path or ">" in a title, and never repeat \
            a community that is already above it.
            Most records belong in an existing collection, or in a new collection of an existing \
            community. Propose a new community rarely: only for a broad field that nothing covers, \
            never one per record, never a classification code (like H.3.7 or cs.DL).
            A collection groups many records of one topic or kind (for example "Information \
            Retrieval" or "Theses"): never name a collection after the record or its title, never like \
            the community it goes in (it is more specific: "Digital Preservation" in "Digital \
            Libraries"), and never start a title with "New collection".
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
     * @param community        the community's id (new collection or new communities)
     * @param newCommunities   the new communities, general to specific (a single object is read as one)
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
            @JsonPropertyDescription("Id of the most specific existing community the new collection or new communities go in, for example m2") String community,
            @JsonPropertyDescription("The new communities (NEW_COMMUNITY), from the broadest to the most specific, each inside the one before")
            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<AnswerStructure> newCommunities,
            @JsonPropertyDescription("The new collection (NEW_COLLECTION, NEW_COMMUNITY)") AnswerStructure newCollection,
            @JsonPropertyDescription("EXISTING, NEW or NONE") TopologyKind topologyDecision,
            @JsonPropertyDescription("Id of the existing topology") String topology,
            @JsonPropertyDescription("The new topology (NEW)") AnswerStructure newTopology,
            @JsonPropertyDescription("0 to 1") Double confidence,
            @JsonPropertyDescription("Why, in one or two sentences") String rationale,
            @JsonPropertyDescription("Up to three other existing collections") List<AnswerAlternative> alternatives) {
    }

}
