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
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
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
 * <p>The structure has three levels: <b>fields</b>, the top-level communities, broad areas of
 * knowledge taken from a fixed list ({@code classification.fields}); <b>disciplines</b>,
 * communities inside a field (Computer Science in Natural Sciences); and <b>collections</b> inside
 * a discipline, each grouping many records of one topic (Digital Libraries in Computer Science).
 * The model places a record in an existing collection, or proposes a new collection in an existing
 * community, or in a discipline (existing or new) of a field. It never invents fields or deeper
 * hierarchies. What it proposes is checked: names are plain titles (no "New Community:" prefix, no
 * path, no classification code, not the record's title); a discipline or collection that exists
 * (by name, see {@link StructureNames#key}) is used rather than created again; a collection is
 * narrower than its community and never named like a field or a community.</p>
 *
 * <p>Ids the model was not shown are never used. An answer that cannot be used is reported to the
 * model, which answers once more (a classification is two model calls at most); an answer that
 * still cannot be used becomes {@link Placement#NONE} with no confidence, so a person decides.
 * New communities, collections and topologies are only proposed: the caller creates them.</p>
 */
@Service
@RegisterReflectionForBinding(Classifier.Answer.class)
public class Classifier extends CapabilityAgent {

    private static final Logger LOG = LoggerFactory.getLogger(Classifier.class);

    /** A short id of the structure listing: {@code m3}, {@code c12}. */
    private static final Pattern ID = Pattern.compile("[mc]\\d+");

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
        final Resolution first = resolve(answer, catalog, request, modelName);
        if (first.problem() == null) {
            return first.classification();
        }
        LOG.info("Unusable answer for {} of {}: {}", request.identifier(), request.source(),
            Prompts.clip(String.valueOf(answer), 800));
        if (!asked) {
            // the model is told what is wrong, once; a classification is two model calls at most
            LOG.info("The classification of {} of {} could not be used ({}); asking the model to correct it",
                request.identifier(), request.source(), first.problem());
            try {
                final Answer corrected = model.ask(Capability.CLASSIFICATION, AgentPrompt.of(system, user
                    + Prompts.data("problem-with-your-previous-answer", first.problem())
                    + "Answer again without this problem.\n"), Answer.class);
                final Resolution second = resolve(corrected, catalog, request, modelName);
                if (second.problem() == null) {
                    return second.classification();
                }
                LOG.info("The corrected classification of {} of {} could not be used either: {} ({})",
                    request.identifier(), request.source(), second.problem(), Prompts.clip(String.valueOf(corrected), 800));
            } catch (AgentException e) {
                LOG.info("The model did not correct the classification of {} of {}: {}", request.identifier(),
                    request.source(), e.getReason());
            }
        }
        LOG.warn("No usable placement for {} of {}: {}", request.identifier(), request.source(), first.problem());
        return first.classification();
    }

    /** The fields: the only top-level communities. */
    private List<String> fields() {
        return properties.getClassification().getFields().stream().filter(StringUtils::hasText).map(String::strip)
            .toList();
    }

    /**
     * Says which answers the structure allows, so a small model does not have to work it out.
     */
    private String choices(final StructureCatalog.Catalog catalog) {
        final List<String> communities = catalog.structure().stream().filter(entry -> entry.kind() == Kind.COMMUNITY)
            .map(Entry::id).toList();
        final List<String> collections = catalog.structure().stream().filter(entry -> entry.kind() == Kind.COLLECTION)
            .map(Entry::id).toList();
        final String fields = "The fields are: " + String.join("; ", fields()) + ".";
        if (communities.isEmpty()) {
            return fields + " There are no communities or collections yet, so answer NEW_COLLECTION with field, "
                + "discipline and newCollection, and leave collection and community empty.";
        }
        return fields + " The only community ids are " + String.join(", ", communities) + "; "
            + (collections.isEmpty() ? "there are no collections yet, so EXISTING_COLLECTION is not possible"
                : "the only collection ids are " + String.join(", ", collections))
            + ". Never use an id that is not in these lists.";
    }

    /**
     * Turns the model's answer into a classification, trusting only what it was shown and what
     * passes the checks of the structure's rules; otherwise the classification is
     * {@link Placement#NONE} with no confidence, and the problem is reported for the model to
     * correct.
     */
    private Resolution resolve(final Answer answer, final StructureCatalog.Catalog catalog,
            final ClassificationRequest request, @Nullable final String modelName) {
        final Placement asked = answer.placement() == null ? Placement.NONE : answer.placement();
        final Optional<Entry> collectionEntry = catalog.find(answer.collection(), Kind.COLLECTION);
        Placement placement = Placement.NONE;
        Classification.Ref collection = null;
        Classification.Ref community = null;
        List<Classification.NewStructure> newCommunities = null;
        Classification.NewStructure newCollection = null;
        String problem = null;
        if (asked == Placement.EXISTING_COLLECTION && collectionEntry.isPresent()) {
            placement = Placement.EXISTING_COLLECTION;
            collection = ref(collectionEntry.get());
        } else if (asked != Placement.NONE) {
            // an "existing" collection that does not exist counts as the proposal the answer carries
            final Proposal proposal = propose(answer, catalog, request);
            problem = proposal.problem();
            if (problem == null && proposal.collection() != null) {
                placement = Placement.EXISTING_COLLECTION;
                collection = ref(proposal.collection());
            } else if (problem == null) {
                newCollection = proposal.newCollection();
                community = proposal.community() == null ? null : ref(proposal.community());
                if (proposal.newCommunities().isEmpty()) {
                    placement = Placement.NEW_COLLECTION;
                } else {
                    placement = Placement.NEW_COMMUNITY;
                    newCommunities = proposal.newCommunities();
                }
            }
        }
        // the model named something it was not shown, or broke the rules: its confidence means nothing
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
     * Places a proposed collection: in the existing community the answer names, or in its discipline
     * of its field (each existing or created), following the structure's rules.
     */
    private Proposal propose(final Answer answer, final StructureCatalog.Catalog catalog,
            final ClassificationRequest request) {
        final List<String> fields = fields();
        // a title where an id goes ("collection": "Citation Analysis") is what the model proposes
        final String collectionTitle0 = answer.newCollection() != null && StringUtils.hasText(answer.newCollection().title())
            ? answer.newCollection().title() : notAnId(answer.collection());
        final Optional<Entry> named = catalog.find(answer.community(), Kind.COMMUNITY);
        final String disciplineTitle0 = answer.discipline() != null && StringUtils.hasText(answer.discipline().title())
            ? answer.discipline().title() : named.isPresent() ? null : notAnId(answer.community());
        // the collection's title; the parts before its last of a path may name the discipline and field
        final List<String> collectionPath = collectionTitle0 == null ? List.of() : path(collectionTitle0, fields, catalog);
        if (collectionPath.isEmpty()) {
            return Proposal.problem("newCollection (with a title) is missing");
        }
        final List<String> disciplinePath = disciplineTitle0 == null ? List.of() : path(disciplineTitle0, fields, catalog);
        String field = matchField(answer.field(), fields);
        String discipline = null;
        final List<String> path = new ArrayList<>(disciplinePath);
        for (final String part : collectionPath.subList(0, collectionPath.size() - 1)) {
            // a field before the collection's name counts even with a discipline given apart
            if (disciplinePath.isEmpty() || matchField(part, fields) != null) {
                path.add(part);
            }
        }
        for (final String part : path) {
            final String asField = matchField(part, fields);
            if (asField != null) {
                field = field == null ? asField : field;
            } else {
                discipline = part;
            }
        }
        final String collectionTitle = collectionPath.getLast();
        for (final String name : discipline == null ? List.of(collectionTitle) : List.of(discipline, collectionTitle)) {
            if (StructureNames.isCode(name)) {
                return Proposal.problem("\"" + clipped(name) + "\" is a classification code, not a name");
            }
            if (StructureNames.namesOneRecord(name, request.titles())) {
                return Proposal.problem("\"" + clipped(name) + "\" names this one record; communities and collections "
                    + "group many records: choose an existing one or give a general title");
            }
        }
        final Classification.NewStructure newCollection = structure(new AnswerStructure(collectionTitle,
            answer.newCollection() == null ? null : answer.newCollection().description()));
        final Classification.NewStructure newDiscipline = discipline == null ? null
            : structure(new AnswerStructure(discipline, answer.discipline() == null ? null : answer.discipline().description()));
        final List<Classification.NewStructure> levels = new ArrayList<>();
        Entry parent = named.orElse(null);
        if (parent != null) {
            if (newDiscipline != null && matchField(parent.title(), fields) != null) {
                // a field was chosen: the discipline goes in it
                levels.add(newDiscipline);
            }
        } else if (field != null) {
            levels.add(structure(new AnswerStructure(field, null)));
            if (newDiscipline != null) {
                levels.add(newDiscipline);
            }
        } else if (newDiscipline != null) {
            // without a field, only an existing discipline will do
            final Optional<Entry> existing = catalog.structure().stream()
                .filter(entry -> entry.kind() == Kind.COMMUNITY && same(entry, newDiscipline)).findFirst();
            if (existing.isEmpty()) {
                return Proposal.problem("field \"" + clipped(answer.field()) + "\" is not one of the fields ("
                    + String.join("; ", fields) + "), and discipline \"" + clipped(discipline)
                    + "\" is no existing community: name one of the fields");
            }
            parent = existing.get();
        } else {
            return Proposal.problem("community \"" + clipped(answer.community()) + "\" is not one of the listed "
                + "community ids; give an existing community id, or field (one of the fields) and discipline");
        }
        final Anchored anchored = anchor(catalog, parent, levels, newCollection);
        if (anchored.collection() != null) {
            return new Proposal(anchored.community(), List.of(), anchored.collection(), null, null);
        }
        final String parentTitle = anchored.newCommunities().isEmpty()
            ? anchored.community() == null ? null : anchored.community().title()
            : anchored.newCommunities().getLast().title();
        final String collectionKey = StructureNames.key(newCollection.title());
        if (parentTitle == null || matchField(parentTitle, fields) != null) {
            return Proposal.problem("a collection goes in a discipline (a community inside a field), not "
                + (parentTitle == null ? "at the top" : "directly in the field \"" + parentTitle + "\"")
                + ": give the discipline");
        }
        if (collectionKey.equals(StructureNames.key(parentTitle))) {
            return Proposal.problem("newCollection \"" + clipped(newCollection.title()) + "\" is named like the "
                + "community it goes in; a collection is narrower than its community: give a narrower title");
        }
        if (fields.stream().anyMatch(name -> StructureNames.key(name).equals(collectionKey))
                || catalog.structure().stream().anyMatch(entry -> entry.kind() == Kind.COMMUNITY
                    && StructureNames.key(entry.title()).equals(collectionKey))) {
            return Proposal.problem("newCollection \"" + clipped(newCollection.title()) + "\" is the name of a field "
                + "or community; a collection is a topic inside a discipline: give a narrower title");
        }
        return new Proposal(anchored.community(), anchored.newCommunities(), null, newCollection, null);
    }

    /** A value where the model was to give an id, when it is a title instead ("Digital Libraries", not "c3"). */
    private static @Nullable String notAnId(@Nullable final String value) {
        return value == null || !StringUtils.hasText(value) || ID.matcher(value.strip()).matches() ? null : value;
    }

    /**
     * The levels of a proposed title: without labels and quotes, split at path separators, and at
     * the colon of "Social Sciences: Bibliometrics" when what comes before it is a field or an
     * existing community (otherwise a colon starts a subtitle: the title of one record).
     */
    private static List<String> path(final String title, final List<String> fields,
            final StructureCatalog.Catalog catalog) {
        String name = StructureNames.clean(title);
        final int colon = name.indexOf(':');
        if (colon > 0) {
            final String before = name.substring(0, colon).strip();
            final String key = StructureNames.key(before);
            if (matchField(before, fields) != null || catalog.structure().stream()
                    .anyMatch(entry -> entry.kind() == Kind.COMMUNITY && StructureNames.key(entry.title()).equals(key))) {
                name = before + " > " + StructureNames.clean(name.substring(colon + 1));
            }
        }
        return levelsOf(name);
    }

    /**
     * Finds the field a name stands for: the field of the same key, or else the only field whose
     * words hold all of the name's words or the other way round ("Health Sciences" or "Humanities"
     * for "Medical and Health Sciences" and "Humanities and the Arts"; "Sciences" fits several,
     * so none).
     *
     * @return the field as configured, or {@code null}
     */
    static @Nullable String matchField(@Nullable final String name, final List<String> fields) {
        if (name == null || StructureNames.key(name).isEmpty()) {
            return null;
        }
        final String key = StructureNames.key(name);
        for (final String field : fields) {
            if (StructureNames.key(field).equals(key)) {
                return field;
            }
        }
        final Set<String> words = Set.of(key.split("-"));
        final List<String> close = fields.stream().filter(field -> {
            final Set<String> fieldWords = Set.of(StructureNames.key(field).split("-"));
            return fieldWords.containsAll(words) || words.containsAll(fieldWords);
        }).toList();
        return close.size() == 1 ? close.getFirst() : null;
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
     * that community, and the levels before it are dropped (disciplines are not duplicated in other
     * fields). Levels repeating a community above or another level are dropped. The collection is an
     * existing one when the final parent has a collection of its name.
     */
    private Anchored anchor(final StructureCatalog.Catalog catalog, @Nullable final Entry parent,
            final List<Classification.NewStructure> proposed, final Classification.NewStructure newCollection) {
        Entry at = parent;
        final List<Classification.NewStructure> levels = new ArrayList<>(proposed);
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
                return new Anchored(at, List.of(), collection.get());
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
        final Entry collection = levels.isEmpty() ? child(catalog, at, Kind.COLLECTION, newCollection).orElse(null) : null;
        return new Anchored(at, List.copyOf(levels), collection);
    }

    /** The community or collection of a proposal's name in a community (or at the top). */
    private static Optional<Entry> child(final StructureCatalog.Catalog catalog, @Nullable final Entry parent,
            final Kind kind, final Classification.NewStructure proposed) {
        final String parentPath = parent == null ? null : parent.path();
        return catalog.structure().stream()
            .filter(entry -> entry.kind() == kind && Objects.equals(entry.parent(), parentPath) && same(entry, proposed))
            .findFirst();
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
     */
    private record Anchored(@Nullable Entry community, List<Classification.NewStructure> newCommunities,
            @Nullable Entry collection) {
    }

    /**
     * Where a proposed collection goes, or why it cannot.
     *
     * @param community      the existing community the collection (or the first new community) goes in
     * @param newCommunities the communities to create (the field and its discipline, or the discipline)
     * @param collection     the existing collection of the proposed collection's name
     * @param newCollection  the collection to create
     * @param problem        why the proposal cannot be used, {@code null} when it can
     */
    private record Proposal(@Nullable Entry community, List<Classification.NewStructure> newCommunities,
            @Nullable Entry collection, Classification.@Nullable NewStructure newCollection, @Nullable String problem) {

        static Proposal problem(final String problem) {
            return new Proposal(null, List.of(), null, null, problem);
        }

    }

    private static String clipped(final String value) {
        return value == null ? "" : Prompts.clip(value, 60);
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

    private String structure(final StructureCatalog.Catalog catalog) {
        if (catalog.structure().isEmpty()) {
            return "(none yet)";
        }
        final StringBuilder out = new StringBuilder();
        for (final Entry entry : catalog.structure()) {
            final boolean field = entry.kind() == Kind.COMMUNITY && entry.parent() == null
                && matchField(entry.title(), fields()) != null;
            out.append(entry.id()).append(entry.kind() == Kind.COLLECTION ? " collection " : field ? " field " : " community ")
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

    private String system(final Locale locale) {
        final List<String> fields = fields();
        return Prompts.RULES + """

            Task: decide where a record harvested from another repository belongs in this repository.
            The repository has three levels:
            1. Fields: the top-level communities, broad areas of knowledge. Only these fields may \
            exist: %s.
            2. Disciplines: communities inside a field, for example Computer Science or Linguistics.
            3. Collections: inside a discipline, each grouping many records of one topic, for example \
            Digital Libraries or Machine Translation.
            Existing communities (ids m1, m2 ...) and collections (ids c1, c2 ...) are listed with their \
            titles from the top. Choose:
            - EXISTING_COLLECTION with the collection id when an existing collection fits the record;
            - NEW_COLLECTION with a new collection (title, description) when none fits: give community, \
            the id of the existing discipline it goes in, or, when no community fits, field (one of the \
            fields, as written) and discipline (title, description);
            - NONE when the record cannot be placed with any confidence.
            Prefer existing collections and communities. Every title is the plain name of one level: \
            no prefix such as "New Community" or "New Collection", no path or ">", no classification \
            code (like H.3.7 or cs.DL), never the record's title. A collection is narrower than its \
            discipline: never name it like a field or a community.
            Choose the topology of the record's item. A topology is the form of an item (its parts \
            and files: a book of pages, a recording of tracks), never what it is about: a subject, a \
            field or a research topic is not a topology. Most harvested records are single documents: \
            answer NONE for them. Answer EXISTING with its id only when the record has that form, and \
            NEW (name, description) only for a form of material that no topology covers.
            Alternatives are optional: up to three other existing collections that could fit.
            Be brief: the rationale is one short sentence, descriptions are one short sentence.
            Confidence is between 0 and 1: 0.9 or more only when the fit is unambiguous.
            """.formatted(String.join("; ", fields))
            + "Write the rationale and proposed titles and descriptions in " + Prompts.language(locale) + ".\n";
    }

    private static double clamp(final Double value) {
        return value == null || value.isNaN() ? 0 : Math.max(0, Math.min(1, value));
    }

    /**
     * A new community, collection or topology the model proposes: an object with its title and
     * description, or (as small models write it) just its title.
     *
     * @param title       its title or name
     * @param description its description
     */
    public record AnswerStructure(
            @JsonPropertyDescription("Title (or name)") String title,
            @JsonPropertyDescription("One sentence") String description) {

        /**
         * Reads the object form.
         *
         * @param title       its title or name
         * @param description its description
         */
        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        public AnswerStructure(@JsonProperty("title") final String title,
                @JsonProperty("description") final String description) {
            this.title = title;
            this.description = description;
        }

        /**
         * Reads the title alone.
         *
         * @param title its title or name
         * @return the proposal
         */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static AnswerStructure of(final String title) {
            return new AnswerStructure(title, null);
        }

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
     * @param community        the id of the existing community a new collection goes in
     * @param field            the field of a new discipline (one of the fields)
     * @param discipline       the discipline a new collection goes in, when no community fits
     * @param newCollection    the new collection
     * @param topologyDecision existing, new or none
     * @param topology         the existing topology's id
     * @param newTopology      the new topology
     * @param confidence       how sure, 0 to 1
     * @param rationale        why
     * @param alternatives     other collections
     */
    public record Answer(
            @JsonPropertyDescription("EXISTING_COLLECTION, NEW_COLLECTION or NONE") Placement placement,
            @JsonPropertyDescription("Id of the existing collection, for example c3") String collection,
            @JsonPropertyDescription("Id of the existing community a new collection goes in, for example m2") String community,
            @JsonPropertyDescription("When no community fits: one of the fields, as written") String field,
            @JsonPropertyDescription("When no community fits: the discipline inside the field") AnswerStructure discipline,
            @JsonPropertyDescription("The new collection (NEW_COLLECTION)") AnswerStructure newCollection,
            @JsonPropertyDescription("EXISTING, NEW or NONE") TopologyKind topologyDecision,
            @JsonPropertyDescription("Id of the existing topology") String topology,
            @JsonPropertyDescription("The new topology (NEW)") AnswerStructure newTopology,
            @JsonPropertyDescription("0 to 1") Double confidence,
            @JsonPropertyDescription("Why, in one or two sentences") String rationale,
            @JsonPropertyDescription("Up to three other existing collections") List<AnswerAlternative> alternatives) {
    }

}
