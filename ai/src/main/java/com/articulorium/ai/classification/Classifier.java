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
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Classifies a harvested record against the community structure and the topologies, as the caller
 * (the harvest service, with its own credentials) may read them.
 *
 * <p>The structure has three levels: <b>fields</b>, the top-level communities, broad areas of
 * knowledge taken from a fixed list ({@code classification.fields}); <b>disciplines</b>,
 * communities inside a field (Computer Science in Natural Sciences); and <b>topics</b>,
 * collections inside a discipline, each grouping many records (Digital Libraries in Computer
 * Science). The model answers an existing collection's id, or a field, a discipline and a topic:
 * an answer small models can give. What it gives is checked (plain names, no codes or record
 * titles, a discipline distinct from its field, a topic narrower than its discipline) and placed
 * on the structure, reusing what exists by name ({@link StructureNames#key}). Where the model
 * leaves the discipline or topic out, or gives one that breaks a rule, the record's own
 * classification is used when it has one (a subject such as arXiv's "Computer Science - Digital
 * Libraries").</p>
 *
 * <p>An answer that still cannot be used is reported to the model, which answers once more (a
 * classification is two model calls at most); an answer that still cannot be used becomes
 * {@link Placement#NONE} with no confidence, so a person decides. New communities, collections
 * and topologies are only proposed: the caller creates them.</p>
 */
@Service
@RegisterReflectionForBinding({Classifier.Answer.class, Classifier.AnswerStructure.class})
@ImportRuntimeHints(Classifier.Hints.class)
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
            try {
                answer = model.ask(Capability.CLASSIFICATION, AgentPrompt.of(system, user), Answer.class);
            } catch (AgentException again) {
                if (again.getReason() != Reason.INVALID_OUTPUT) {
                    throw again;
                }
                // the record's own classification may still place it
                final Resolution own = resolve(Answer.NOTHING, catalog, request, modelName);
                if (own.classification().placement() == Placement.NONE) {
                    throw again;
                }
                LOG.info("No readable answer for {} of {}; placed by its own classification", request.identifier(),
                    request.source());
                return own.classification();
            }
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
        final List<String> collections = catalog.structure().stream().filter(entry -> entry.kind() == Kind.COLLECTION)
            .map(Entry::id).toList();
        return "The fields are: " + String.join("; ", fields()) + ". "
            + (collections.isEmpty() ? "There are no collections yet: give field, discipline and topic."
                : "The only collection ids are " + String.join(", ", collections) + ": give one of them as collection "
                    + "only when that collection fits the record, otherwise give field, discipline and topic.");
    }

    /**
     * Turns the model's answer into a classification. When the record brings its own discipline
     * (its set's, or a subject's "Discipline - Topic": see {@link RecordClassification}), the
     * first of these that passes the structure's rules is taken: the existing collection the
     * model names, when it lies in that discipline; that discipline with a topic the record's
     * subjects name (the primary first); that discipline with the model's topic; the existing
     * collection the model names elsewhere; the model's field, discipline and topic. Otherwise
     * the existing collection the model names, or its field, discipline and topic. A placement by
     * the record's own classification says so in its rationale and is as confident as configured
     * at least. When none passes, the classification is {@link Placement#NONE} with no
     * confidence, and the problem with the model's proposal is reported for it to correct.
     */
    private Resolution resolve(final Answer answer, final StructureCatalog.Catalog catalog,
            final ClassificationRequest request, @Nullable final String modelName) {
        Placement placement = Placement.NONE;
        Classification.Ref collection = null;
        Classification.Ref community = null;
        List<Classification.NewStructure> newCommunities = null;
        Classification.NewStructure newCollection = null;
        String problem = null;
        String basis = null;
        final List<String> fields = fields();
        final RecordClassification own = RecordClassification.of(request, fields,
            properties.getClassification().getSetDisciplines()).filter(found -> found.discipline() != null).orElse(null);
        final Optional<Entry> collectionEntry = answer.collection() == null ? Optional.empty()
            : catalog.find(answer.collection(), Kind.COLLECTION);
        if (collectionEntry.isPresent() && (own == null || inDiscipline(collectionEntry.get(), own.discipline()))) {
            placement = Placement.EXISTING_COLLECTION;
            collection = ref(collectionEntry.get());
        } else {
            final String field = StructureNames.isPlaceholder(answer.field()) ? null : matchField(answer.field(), fields);
            final String discipline = title(answer.discipline());
            final String topic = title(answer.topic()) != null ? title(answer.topic()) : notAnId(answer.collection());
            final String topicDescription = answer.topic() == null ? null : answer.topic().description();
            final Candidate model = new Candidate(field, answer.discipline(), discipline, topic, topicDescription,
                null, null);
            final List<Candidate> candidates = new ArrayList<>();
            if (own != null) {
                // the record's own classification, curated by its source, before the model's proposal
                final String ownField = own.field() != null ? own.field() : field;
                for (final String ownTopic : own.topics()) {
                    candidates.add(new Candidate(ownField, null, own.discipline(), ownTopic, null, null,
                        "discipline " + own.discipline() + " (" + own.source() + "), topic " + ownTopic
                            + " (a subject of the record)"));
                }
                candidates.add(new Candidate(ownField, null, own.discipline(), topic, topicDescription, null,
                    "discipline " + own.discipline() + " (" + own.source() + ")"));
                collectionEntry.ifPresent(entry -> candidates.add(new Candidate(null, null, null, null, null, entry,
                    null)));
            }
            candidates.add(model);
            Proposal accepted = null;
            for (final Candidate candidate : candidates) {
                final Proposal proposal = candidate.existing() != null
                    ? new Proposal(null, List.of(), candidate.existing(), null, null)
                    : propose(candidate, catalog, request, fields);
                if (proposal.problem() == null) {
                    accepted = proposal;
                    basis = candidate.basis();
                    break;
                }
                // the model is told what is wrong with its own proposal
                problem = candidate == model || problem == null ? proposal.problem() : problem;
            }
            if (accepted != null) {
                problem = null;
                if (accepted.collection() != null) {
                    placement = Placement.EXISTING_COLLECTION;
                    collection = ref(accepted.collection());
                } else {
                    newCollection = accepted.newCollection();
                    community = accepted.community() == null ? null : ref(accepted.community());
                    if (accepted.newCommunities().isEmpty()) {
                        placement = Placement.NEW_COLLECTION;
                    } else {
                        placement = Placement.NEW_COMMUNITY;
                        newCommunities = accepted.newCommunities();
                    }
                }
            }
        }
        // an answer that broke the rules: its confidence means nothing
        double confidence = placement == Placement.NONE ? 0 : clamp(answer.confidence());
        String rationale = answer.rationale() == null ? "" : Prompts.clip(answer.rationale(), 600);
        if (basis != null) {
            // placed by what the record's source says, not by the model's reasons
            confidence = Math.max(confidence, properties.getClassification().getRecordConfidence());
            rationale = "Placed by the record's own classification: " + basis + ".";
        }
        return new Resolution(new Classification(placement, collection, community, newCommunities, newCollection,
            topology(answer, catalog, request, newCommunities, newCollection), confidence, rationale, List.of(),
            modelName), problem);
    }

    /** Whether an existing collection lies in a discipline: a community of its trail bears its name. */
    private static boolean inDiscipline(final Entry collection, final String discipline) {
        final String key = StructureNames.key(discipline);
        final List<String> levels = levelsOf(collection.trail());
        return levels.subList(0, Math.max(0, levels.size() - 1)).stream()
            .anyMatch(level -> StructureNames.key(level).equals(key));
    }

    /**
     * A placement to try: field, discipline (a title, or an existing community's id) and topic.
     *
     * @param field            the field, as configured, or {@code null}
     * @param disciplineAnswer the model's discipline (for its description), or {@code null}
     * @param discipline       the discipline's title or an existing community's id, or {@code null}
     * @param topic            the new collection's title, or {@code null}
     * @param topicDescription the new collection's description, or {@code null}
     * @param existing         an existing collection to take as it is, or {@code null}
     * @param basis            what of the record's own classification it follows, for the
     *                         rationale; {@code null} for the model's proposal
     */
    private record Candidate(@Nullable String field, @Nullable AnswerStructure disciplineAnswer,
            @Nullable String discipline, @Nullable String topic, @Nullable String topicDescription,
            @Nullable Entry existing, @Nullable String basis) {
    }

    /**
     * Places a proposed collection in its discipline of its field (each existing or created),
     * following the structure's rules.
     */
    private Proposal propose(final Candidate candidate, final StructureCatalog.Catalog catalog,
            final ClassificationRequest request, final List<String> fields) {
        if (candidate.topic() == null) {
            return Proposal.problem("topic (the new collection's title) is missing");
        }
        final List<String> topicPath = path(candidate.topic(), fields, catalog);
        if (topicPath.isEmpty()) {
            return Proposal.problem("topic (the new collection's title) is missing");
        }
        String field = candidate.field();
        // the discipline: an existing community's id, or a title (a path may name the field too)
        Entry parent = candidate.discipline() == null ? null
            : catalog.find(candidate.discipline(), Kind.COMMUNITY).orElse(null);
        String discipline = null;
        final List<String> path = new ArrayList<>();
        if (parent == null && candidate.discipline() != null && !ID.matcher(candidate.discipline().strip()).matches()) {
            path.addAll(path(candidate.discipline(), fields, catalog));
        }
        final boolean disciplineGiven = !path.isEmpty();
        for (final String part : topicPath.subList(0, topicPath.size() - 1)) {
            if (!disciplineGiven || matchField(part, fields) != null) {
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
        final String topic = topicPath.getLast();
        for (final String name : discipline == null ? List.of(topic) : List.of(discipline, topic)) {
            if (StructureNames.isCode(name)) {
                return Proposal.problem("\"" + clipped(name) + "\" is a classification code, not a name");
            }
            if (StructureNames.namesOneRecord(name, request.titles())) {
                return Proposal.problem("\"" + clipped(name) + "\" names this one record; a collection groups many "
                    + "records of one topic: give a general topic");
            }
        }
        final Classification.NewStructure newCollection = structure(new AnswerStructure(topic, candidate.topicDescription()));
        final Classification.NewStructure newDiscipline = discipline == null ? null
            : structure(new AnswerStructure(discipline,
                candidate.disciplineAnswer() == null ? null : candidate.disciplineAnswer().description()));
        final List<Classification.NewStructure> levels = new ArrayList<>();
        if (parent != null) {
            if (newDiscipline != null && matchField(parent.title(), fields) != null) {
                levels.add(newDiscipline);
            }
        } else if (newDiscipline != null) {
            final Optional<Entry> existing = catalog.structure().stream()
                .filter(entry -> entry.kind() == Kind.COMMUNITY && same(entry, newDiscipline)).findFirst();
            if (existing.isPresent()) {
                // a discipline exists once, in whichever field
                parent = existing.get();
            } else if (field != null) {
                levels.add(structure(new AnswerStructure(field, null)));
                levels.add(newDiscipline);
            } else {
                return Proposal.problem("field \"" + clipped(candidate.field()) + "\" is not one of the fields ("
                    + String.join("; ", fields) + "): give one of them as field");
            }
        } else {
            return Proposal.problem("discipline is missing: give the discipline (for example Computer Science or "
                + "Sociology), a community inside the field, distinct from the field");
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
            return Proposal.problem("discipline is missing: give the discipline (a community inside the field), "
                + "distinct from the field");
        }
        if (collectionKey.equals(StructureNames.key(parentTitle))) {
            return Proposal.problem("topic \"" + clipped(newCollection.title()) + "\" is named like its discipline; a "
                + "topic is narrower than its discipline");
        }
        if (fields.stream().anyMatch(name -> StructureNames.key(name).equals(collectionKey))
                || catalog.structure().stream().anyMatch(entry -> entry.kind() == Kind.COMMUNITY
                    && StructureNames.key(entry.title()).equals(collectionKey))) {
            return Proposal.problem("topic \"" + clipped(newCollection.title()) + "\" is the name of a field or "
                + "community; a topic is narrower: give a narrower topic");
        }
        return new Proposal(anchored.community(), anchored.newCommunities(), null, newCollection, null);
    }

    /** A value where the model was to give an id, when it is a title instead ("Digital Libraries", not "c3"). */
    private static @Nullable String notAnId(@Nullable final String value) {
        return value == null || StructureNames.isPlaceholder(value) || StructureNames.isCode(value)
            || ID.matcher(value.strip()).matches() ? null : value;
    }

    /** The title of a proposal, unless it stands for no name ("None") or is a code. */
    private static @Nullable String title(@Nullable final AnswerStructure proposal) {
        return proposal == null || proposal.title() == null || StructureNames.isPlaceholder(proposal.title())
            || StructureNames.isCode(proposal.title()) && !ID.matcher(proposal.title().strip()).matches() ? null
            : proposal.title();
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
        // "Social Sciences > None > Methods": a part standing for no name is no level
        return levelsOf(name).stream().filter(part -> !StructureNames.isPlaceholder(part)).toList();
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
        final Set<String> words = Set.copyOf(Arrays.asList(key.split("-")));
        final List<String> close = fields.stream().filter(field -> {
            final Set<String> fieldWords = Set.copyOf(Arrays.asList(StructureNames.key(field).split("-")));
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
        // the decision may be left out: an existing topology's id, or a new topology, tells it
        final TopologyKind kind = answer.topologyDecision() != null ? answer.topologyDecision()
            : StringUtils.hasText(answer.topology()) ? TopologyKind.EXISTING
            : answer.newTopology() != null ? TopologyKind.NEW : TopologyKind.NONE;
        if (kind == TopologyKind.EXISTING) {
            final Optional<TopologySummary> match = catalog.topologies().stream()
                .filter(topology -> topology.id().equals(answer.topology())).findFirst();
            if (match.isPresent()) {
                return new Classification.TopologyChoice(TopologyKind.EXISTING, match.get().id(), match.get().name(), null);
            }
        } else if (kind == TopologyKind.NEW && answer.newTopology() != null
                && StringUtils.hasText(answer.newTopology().title())
                // a structure of parts and files needs files of several kinds: a single document has none
                && request.mediaTypes().size() > 1
                && !StructureNames.isPlaceholder(answer.newTopology().title())
                && !StructureNames.namesOneRecord(answer.newTopology().title(), request.titles())
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
        line(out, "file types", request.mediaTypes());
        RecordClassification.of(request, fields(), properties.getClassification().getSetDisciplines())
            .map(RecordClassification::discipline)
            .ifPresent(discipline -> line(out, "discipline in its source", List.of(discipline)));
        return Prompts.clip(out.toString(), properties.getClassification().getMaxRecordLength());
    }

    private static void line(final StringBuilder out, final String name, final List<String> values) {
        if (!values.isEmpty()) {
            out.append(name).append(": ").append(String.join("; ", values.stream().limit(20).toList())).append('\n');
        }
    }

    private String system(final Locale locale) {
        return Prompts.RULES + """

            Task: decide where a record harvested from another repository belongs in this repository.
            The repository has three levels:
            1. field: the top-level communities, broad areas of knowledge. Only these fields exist: %s.
            2. discipline: a community inside a field, for example Computer Science, Physics, \
            Linguistics or Sociology. A discipline is never one of the fields.
            3. topic: a collection inside a discipline, grouping many records of one topic, for example \
            Digital Libraries, Astrophysics of Galaxies or Machine Translation.
            When one of the listed existing collections (ids c1, c2 ...) fits the record, answer its id \
            as collection. Otherwise answer field, discipline and topic (use an existing discipline when \
            one fits); they are created when they do not exist. The record's subjects often name its \
            discipline and topic ("Computer Science - Digital Libraries").
            Every title is the plain name of one level: no prefix such as "New Collection", no path, no \
            code (like H.3.7 or cs.DL), never the record's own title, never "None".
            Topology: the id of an existing topology only when the record has that form (most records \
            are single documents: leave it empty); a new topology only for an item made of files of \
            several kinds that no topology describes.
            Example: {"collection": "", "field": "Natural Sciences", "discipline": "Computer Science", \
            "topic": "Digital Libraries", "topology": "", "confidence": 0.8, "rationale": "A study of \
            repository software."}
            Be brief: the rationale is one short sentence. Confidence is between 0 and 1.
            """.formatted(String.join("; ", fields()))
            + "Write the rationale and proposed titles in " + Prompts.language(locale) + ".\n";
    }

    private static double clamp(final Double value) {
        return value == null || value.isNaN() ? 0 : Math.max(0, Math.min(1, value));
    }

    /**
     * Registers the creators that read a proposal from an object or from its title alone. (A
     * second {@code @RegisterReflection} beside {@code @RegisterReflectionForBinding} would hide
     * the binding: only the annotation declared directly is read.)
     */
    public static class Hints implements RuntimeHintsRegistrar {

        @Override
        public void registerHints(final RuntimeHints hints, @Nullable final ClassLoader classLoader) {
            hints.reflection().registerType(AnswerStructure.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS);
        }

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
     * The model's answer.
     *
     * @param collection       the existing collection's id, when one fits
     * @param field            the field (one of the fields)
     * @param discipline       the discipline inside the field (a title, or an existing community's id)
     * @param topic            the new collection (a topic inside the discipline)
     * @param topologyDecision existing, new or none (optional: read from {@code topology} and {@code newTopology})
     * @param topology         the existing topology's id
     * @param newTopology      the new topology
     * @param confidence       how sure, 0 to 1
     * @param rationale        why
     */
    public record Answer(
            @JsonPropertyDescription("Id of the existing collection that fits, for example c3; empty when none fits") String collection,
            @JsonPropertyDescription("One of the fields, as written") String field,
            @JsonPropertyDescription("The discipline inside the field, for example Computer Science") @JsonAlias("community") AnswerStructure discipline,
            @JsonPropertyDescription("The topic inside the discipline: the new collection, for example Digital Libraries") @JsonAlias("newCollection") AnswerStructure topic,
            @JsonPropertyDescription("Optional: EXISTING, NEW or NONE") TopologyKind topologyDecision,
            @JsonPropertyDescription("Id of an existing topology the record's item follows, usually empty") String topology,
            @JsonPropertyDescription("Only for an item of files of several kinds that no topology describes") AnswerStructure newTopology,
            @JsonPropertyDescription("0 to 1") Double confidence,
            @JsonPropertyDescription("Why, in one sentence") String rationale) {

        /** No answer: what is left when the model's answer cannot be read. */
        static final Answer NOTHING = new Answer(null, null, null, null, null, null, null, null, null);

    }

}
