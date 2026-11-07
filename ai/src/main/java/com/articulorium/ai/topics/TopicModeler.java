package com.articulorium.ai.topics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.articulorium.ai.AgentException;
import com.articulorium.ai.AgentException.Reason;
import com.articulorium.ai.Capability;
import com.articulorium.ai.CapabilityAgent;
import com.articulorium.ai.config.AiProperties;
import com.articulorium.ai.model.AgentModel;
import com.articulorium.ai.model.AgentPrompt;
import com.articulorium.ai.model.Prompts;
import com.articulorium.ai.platform.AgentAccess;
import com.articulorium.ai.platform.Descriptions;
import com.articulorium.ai.platform.PlatformReader;
import com.articulorium.domain.rdf.ResourcePaths;
import com.articulorium.domain.vocabulary.Articulorium;
import com.articulorium.domain.vocabulary.Ldp;
import com.articulorium.domain.vocabulary.Pcdm;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import org.apache.jena.rdf.model.Model;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Models the topics of a community or a collection from the texts of its items (titles,
 * descriptions, subjects, creators, dates), read with the caller's credentials: items the caller
 * may not read are never shown to the model, so they cannot surface in topics or the summary.
 *
 * <p>Items are gathered breadth first through sub-communities and collections, up to
 * {@code articulorium.ai.topics.max-documents}. The model groups them into topics and summarises
 * the whole; topics citing items it was not shown are cleaned of them. Nothing is saved: the caller
 * keeps the topics and summary it wants and adds them to the community or collection
 * ({@code dcterms:subject}, {@code dcterms:abstract}).</p>
 */
@Service
public class TopicModeler extends CapabilityAgent {

    private final AgentModel model;

    private final AgentAccess access;

    private final PlatformReader platform;

    private final AiProperties properties;

    /**
     * Creates the modeler.
     *
     * @param model      the chat model
     * @param access     the access check
     * @param platform   the repository
     * @param properties the agent settings
     */
    public TopicModeler(final AgentModel model, final AgentAccess access, final PlatformReader platform,
            final AiProperties properties) {
        super(Capability.TOPICS, properties);
        this.model = model;
        this.access = access;
        this.platform = platform;
        this.properties = properties;
    }

    /**
     * Models the topics of a community or collection.
     *
     * @param request       what to model
     * @param authorization the caller's {@code Authorization} header
     * @param locale        the caller's locale (language of topics and summary)
     * @return the topic model
     */
    public TopicModel model(final TopicModelRequest request, @Nullable final String authorization,
            final Locale locale) {
        model.require(Capability.TOPICS);
        if (request.path() == null || request.path().isBlank()) {
            throw new AgentException(Reason.INVALID_REQUEST, "Name the community or collection");
        }
        final String path = ResourcePaths.normalize(request.path());
        access.requireWrite(path, authorization);
        final Model root = platform.rdf(path, authorization)
            .orElseThrow(() -> new AgentException(Reason.NOT_FOUND, "No resource /" + path));
        if (!structure(root, platform.iri(path))) {
            throw new AgentException(Reason.UNSUPPORTED, "/" + path + " is neither a community nor a collection");
        }
        final int maxTopics = Math.max(1, Math.min(request.maxTopics() == null ? properties.getTopics().getMaxTopics()
            : request.maxTopics(), properties.getTopics().getMaxTopics()));
        final Gathered gathered = gather(path, root, authorization);
        final String language = locale == null || locale.getLanguage().isEmpty() ? null : locale.toLanguageTag();
        if (gathered.documents().isEmpty()) {
            return new TopicModel(path, "", List.of(), 0, false, language);
        }
        final StringBuilder documents = new StringBuilder();
        gathered.documents().forEach((id, document) -> documents.append(id).append(": ").append(document.text())
            .append('\n'));
        final String title = Descriptions.title(root, platform.iri(path));
        final String user = Prompts.data("collection", title.isEmpty() ? path : title)
            + Prompts.data("items", documents.toString())
            + "Find at most " + maxTopics + " topics in these items and summarise them.\n";
        final Answer answer = model.ask(Capability.TOPICS, AgentPrompt.of(system(maxTopics, locale), user), Answer.class);
        final List<TopicModel.Topic> topics = new ArrayList<>();
        final Set<String> labels = new LinkedHashSet<>();
        for (final Answer.Topic topic : answer.topics() == null ? List.<Answer.Topic>of() : answer.topics()) {
            if (topic == null || topic.label() == null || topic.label().isBlank()
                    || !labels.add(topic.label().strip().toLowerCase(Locale.ROOT)) || topics.size() >= maxTopics) {
                continue;
            }
            final List<TopicModel.Member> members = new ArrayList<>();
            for (final String id : topic.items() == null ? List.<String>of() : topic.items()) {
                final Document document = id == null ? null : gathered.documents().get(id.strip());
                if (document != null && members.stream().noneMatch(member -> member.path().equals(document.path()))) {
                    members.add(new TopicModel.Member(document.path(), document.title()));
                }
            }
            final List<String> keywords = topic.keywords() == null ? List.of() : topic.keywords().stream()
                .filter(keyword -> keyword != null && !keyword.isBlank()).map(String::strip).distinct().limit(8).toList();
            topics.add(new TopicModel.Topic(Prompts.clip(topic.label(), 120), keywords,
                topic.description() == null ? "" : Prompts.clip(topic.description(), 500), members,
                (double) members.size() / gathered.documents().size()));
        }
        topics.sort((a, b) -> Double.compare(b.share(), a.share()));
        return new TopicModel(path, answer.summary() == null ? "" : Prompts.clip(answer.summary(), 2000), topics,
            gathered.documents().size(), gathered.truncated(), language);
    }

    private Gathered gather(final String path, final Model root, @Nullable final String authorization) {
        final int maxDocuments = Math.max(1, properties.getTopics().getMaxDocuments());
        final int maxReads = maxDocuments * 3;
        final Map<String, Document> documents = new LinkedHashMap<>();
        final Deque<Map.Entry<String, Model>> queue = new ArrayDeque<>();
        queue.add(Map.entry(path, root));
        int reads = 0;
        boolean truncated = false;
        while (!queue.isEmpty()) {
            final Map.Entry<String, Model> next = queue.poll();
            for (final String child : Descriptions.children(next.getValue(), platform.iri(next.getKey()))) {
                if (documents.size() >= maxDocuments || reads >= maxReads) {
                    truncated = true;
                    break;
                }
                final Optional<String> childPath = platform.pathOf(child);
                if (childPath.isEmpty()) {
                    continue;
                }
                reads++;
                final Optional<Model> rdf = platform.rdf(childPath.get(), authorization);
                if (rdf.isEmpty() || Descriptions.hasType(rdf.get(), child, Ldp.NON_RDF_SOURCE)) {
                    continue;
                }
                if (container(rdf.get(), child)) {
                    queue.add(Map.entry(childPath.get(), rdf.get()));
                    continue;
                }
                final String text = text(rdf.get(), child);
                if (!text.isBlank()) {
                    documents.put("d" + (documents.size() + 1),
                        new Document(childPath.get(), Descriptions.title(rdf.get(), child), text));
                }
            }
            if (truncated) {
                break;
            }
        }
        return new Gathered(documents, truncated);
    }

    private String text(final Model model, final String iri) {
        final StringBuilder text = new StringBuilder(Descriptions.title(model, iri));
        Descriptions.descriptions(model, iri).forEach(description -> text.append(". ").append(description));
        final List<String> subjects = Descriptions.subjects(model, iri);
        if (!subjects.isEmpty()) {
            text.append(". Subjects: ").append(String.join("; ", subjects));
        }
        final List<String> creators = Descriptions.creators(model, iri);
        if (!creators.isEmpty()) {
            text.append(". By: ").append(String.join("; ", creators));
        }
        Descriptions.dates(model, iri).stream().findFirst().ifPresent(date -> text.append(". Date: ").append(date));
        Descriptions.genres(model, iri).stream().findFirst().ifPresent(genre -> text.append(". Type: ").append(genre));
        return Prompts.clip(text.toString().replaceAll("\\s+", " "), properties.getTopics().getMaxDocumentLength());
    }

    /** What the communities page shows as a community or collection (the resources modelled). */
    private static boolean structure(final Model model, final String iri) {
        return container(model, iri) || Descriptions.hasType(model, iri, Pcdm.COLLECTION);
    }

    /**
     * What is walked through: communities and the collections of the community structure. A PCDM
     * collection built from a topology (a newspaper title, a journal) is an item of its collection.
     */
    private static boolean container(final Model model, final String iri) {
        return Descriptions.hasType(model, iri, Articulorium.COMMUNITY)
            || Descriptions.hasType(model, iri, Articulorium.COMMUNITY_ROOT)
            || Descriptions.hasType(model, iri, Articulorium.COLLECTION);
    }

    private static String system(final int maxTopics, final Locale locale) {
        return Prompts.RULES + """

            Task: topic modelling of a community or collection of a digital library. Each item is given \
            as "id: text". Group the items into at most\s""" + maxTopics + """
             distinct topics (fewer when the items are homogeneous). For each topic give a short label \
            (a noun phrase, as a subject heading), up to 8 keywords, one sentence describing it and the \
            ids of the items about it (an item may belong to several topics). Then summarise what the \
            whole community or collection holds in two to four sentences.
            """ + "Write labels, keywords, descriptions and the summary in " + Prompts.language(locale) + ".\n";
    }

    private record Document(String path, String title, String text) {
    }

    private record Gathered(Map<String, Document> documents, boolean truncated) {
    }

    /**
     * The model's answer.
     *
     * @param topics  the topics
     * @param summary the summary
     */
    public record Answer(
            @JsonPropertyDescription("The topics, the largest first") List<Topic> topics,
            @JsonPropertyDescription("Two to four sentences on what the whole holds") String summary) {

        /**
         * A topic.
         *
         * @param label       its label
         * @param keywords    keywords
         * @param description one sentence
         * @param items       ids of its items
         */
        public record Topic(
                @JsonPropertyDescription("Short noun phrase, as a subject heading") String label,
                @JsonPropertyDescription("Up to 8 keywords") List<String> keywords,
                @JsonPropertyDescription("One sentence") String description,
                @JsonPropertyDescription("Ids of the items about the topic, for example d3") List<String> items) {
        }

    }

}
