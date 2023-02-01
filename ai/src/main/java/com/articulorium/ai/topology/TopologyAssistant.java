package com.articulorium.ai.topology;

import java.text.Normalizer;
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
import com.articulorium.ai.platform.Descriptions;
import com.articulorium.ai.platform.PlatformReader;
import com.articulorium.domain.rdf.ResourcePaths;
import com.articulorium.domain.vocabulary.Ldp;
import com.articulorium.domain.vocabulary.Premis;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import org.apache.jena.rdf.model.Model;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import tools.jackson.databind.json.JsonMapper;

/**
 * Suggests a topology for some material: one of the existing topologies when one fits, otherwise
 * the draft of a new one.
 *
 * <p>The model sees the existing topologies (with their levels and files) and the material: a
 * description in the caller's words and, optionally, a sample container whose children (with their
 * kinds, names and media types) show how the material is organised. A draft is checked with the
 * topology API's own rules; when it breaks them the model is asked once to correct it, and any
 * problem left is returned with the draft. Nothing is saved: the caller opens the draft in the
 * topology editor, changes it and saves it there.</p>
 */
@Service
public class TopologyAssistant extends CapabilityAgent {

    /** Largest number of existing topologies shown with their structure. */
    static final int MAX_OUTLINED = 25;

    private final AgentModel model;

    private final AgentAccess access;

    private final PlatformReader platform;

    private final TopologyClient topologies;

    private final AiProperties properties;

    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * Creates the assistant.
     *
     * @param model      the chat model
     * @param access     the access check
     * @param platform   the repository
     * @param topologies the topology API
     * @param properties the agent settings
     */
    public TopologyAssistant(final AgentModel model, final AgentAccess access, final PlatformReader platform,
            final TopologyClient topologies, final AiProperties properties) {
        super(Capability.TOPOLOGY, properties);
        this.model = model;
        this.access = access;
        this.platform = platform;
        this.topologies = topologies;
        this.properties = properties;
    }

    /**
     * Suggests a topology.
     *
     * @param request       the material
     * @param authorization the caller's {@code Authorization} header
     * @param locale        the caller's locale (labels of a draft)
     * @return the suggestion
     */
    public TopologySuggestion suggest(final TopologySuggestionRequest request, @Nullable final String authorization,
            final Locale locale) {
        model.require(Capability.TOPOLOGY);
        final String description = request.description() == null ? "" : request.description().strip();
        if (description.isEmpty() && !StringUtils.hasText(request.sample())) {
            throw new AgentException(Reason.INVALID_REQUEST, "Describe the material or name a sample container");
        }
        if (description.length() > properties.getTopology().getMaxDescriptionLength()) {
            throw new AgentException(Reason.INVALID_REQUEST, "The description is longer than "
                + properties.getTopology().getMaxDescriptionLength() + " characters");
        }
        access.requireWrite(StringUtils.hasText(request.context()) ? request.context() : properties.getTopologies(),
            authorization);
        final List<TopologySummary> existing = topologies.list(authorization);
        final StringBuilder user = new StringBuilder();
        user.append(Prompts.data("existing-topologies", outline(existing, authorization)));
        if (!description.isEmpty()) {
            user.append(Prompts.data("material-description", description));
        }
        if (StringUtils.hasText(request.sample())) {
            user.append(Prompts.data("sample-container", sample(request.sample(), authorization)));
        }
        user.append("Suggest the topology for this material.");
        final Advice advice = model.ask(Capability.TOPOLOGY, AgentPrompt.of(system(locale), user.toString()), Advice.class);
        final double confidence = clamp(advice.confidence());
        if (advice.decision() == Decision.EXISTING) {
            final Optional<TopologySummary> match = existing.stream()
                .filter(summary -> summary.id().equals(advice.existingId())).findFirst();
            if (match.isPresent()) {
                return new TopologySuggestion(TopologySuggestion.Recommendation.EXISTING, match.get(), null, List.of(),
                    advice.rationale(), confidence);
            }
            if (advice.draft() == null) {
                throw new AgentException(Reason.INVALID_OUTPUT, "The model recommended an unknown topology");
            }
        }
        if (advice.draft() == null || advice.draft().root() == null) {
            throw new AgentException(Reason.INVALID_OUTPUT, "The model suggested a new topology without a draft");
        }
        TopologyDraft draft = normalise(advice.draft(), existing);
        TopologyValidation validation = topologies.validate(draft, authorization);
        if (!validation.valid()) {
            draft = correct(draft, validation, locale, existing, authorization);
            validation = topologies.validate(draft, authorization);
        }
        return new TopologySuggestion(TopologySuggestion.Recommendation.NEW, null, draft, validation.errors(),
            advice.rationale(), confidence);
    }

    private TopologyDraft correct(final TopologyDraft draft, final TopologyValidation validation, final Locale locale,
            final List<TopologySummary> existing, @Nullable final String authorization) {
        final String user = Prompts.data("draft", json.writeValueAsString(draft))
            + Prompts.data("problems", String.join("\n", validation.errors()))
            + "Correct the draft so that it has none of these problems; change nothing else.";
        try {
            final TopologyDraft corrected = model.ask(Capability.TOPOLOGY, AgentPrompt.of(system(locale), user),
                TopologyDraft.class);
            return corrected.root() == null ? draft : normalise(corrected, existing);
        } catch (AgentException e) {
            if (e.getReason() == Reason.INVALID_OUTPUT || e.getReason() == Reason.MODEL_ERROR) {
                // the first draft (with its problems) is still worth reviewing
                return draft;
            }
            throw e;
        }
    }

    private String outline(final List<TopologySummary> existing, @Nullable final String authorization) {
        final StringBuilder out = new StringBuilder();
        int outlined = 0;
        for (final TopologySummary summary : existing) {
            out.append("- id ").append(summary.id()).append(": ").append(summary.name());
            if (StringUtils.hasText(summary.description())) {
                out.append(" — ").append(Prompts.clip(summary.description(), 300));
            }
            out.append('\n');
            if (outlined++ < MAX_OUTLINED) {
                topologies.get(summary.id(), authorization)
                    .ifPresent(topology -> outlineNode(topology.root(), 1, out));
            }
        }
        return out.isEmpty() ? "(none)" : out.toString();
    }

    private static void outlineNode(final TopologyDraft.Node node, final int depth, final StringBuilder out) {
        if (node == null || depth > 8) {
            return;
        }
        out.append("  ".repeat(depth)).append(node.label()).append(" [").append(node.pcdmType()).append(']');
        if (node.types() != null && !node.types().isEmpty()) {
            out.append(" types ").append(String.join(", ", node.types()));
        }
        if (node.files() != null && !node.files().isEmpty()) {
            out.append(" files ").append(String.join(", ", node.files().stream()
                .map(file -> file.label() + (file.mediaTypes() == null || file.mediaTypes().isEmpty() ? ""
                    : " (" + String.join(" ", file.mediaTypes()) + ")")).toList()));
        }
        out.append('\n');
        if (node.children() != null) {
            node.children().forEach(child -> outlineNode(child.node(), depth + 1, out));
        }
    }

    private String sample(final String path, @Nullable final String authorization) {
        final Model root = platform.rdf(path, authorization).orElseThrow(() -> new AgentException(Reason.NOT_FOUND,
            "No resource /" + ResourcePaths.normalize(path)));
        final StringBuilder out = new StringBuilder();
        final String iri = platform.iri(path);
        out.append(ResourcePaths.nameOf(ResourcePaths.normalize(path)));
        final String title = Descriptions.title(root, iri);
        if (!title.isEmpty()) {
            out.append(" \"").append(Prompts.clip(title, 200)).append('"');
        }
        out.append('\n');
        final int[] budget = {properties.getTopology().getMaxSampleResources()};
        children(root, iri, 1, budget, out, authorization);
        return out.toString();
    }

    private void children(final Model parent, final String parentIri, final int depth, final int[] budget,
            final StringBuilder out, @Nullable final String authorization) {
        final List<String> children = Descriptions.children(parent, parentIri);
        for (final String child : children) {
            if (budget[0]-- <= 0) {
                out.append("  ".repeat(depth)).append("… more\n");
                return;
            }
            final Optional<String> path = platform.pathOf(child);
            if (path.isEmpty()) {
                continue;
            }
            final Optional<Model> model = platform.rdf(path.get(), authorization);
            if (model.isEmpty()) {
                continue;
            }
            final Model description = model.get();
            out.append("  ".repeat(depth)).append(ResourcePaths.nameOf(path.get()));
            if (Descriptions.hasType(description, child, Ldp.NON_RDF_SOURCE)) {
                out.append(" (file");
                Descriptions.literals(description, child, Premis.HAS_MIME_TYPE).stream().findFirst()
                    .ifPresent(type -> out.append(' ').append(type));
                out.append(')');
            } else {
                final List<String> grandChildren = Descriptions.children(description, child);
                out.append(" (container, ").append(grandChildren.size()).append(" children)");
                final String title = Descriptions.title(description, child);
                if (!title.isEmpty()) {
                    out.append(" \"").append(Prompts.clip(title, 120)).append('"');
                }
            }
            out.append('\n');
            if (depth < 3) {
                children(description, child, depth + 1, budget, out, authorization);
            }
        }
    }

    private static String system(final Locale locale) {
        return Prompts.RULES + """

            Task: choose the PCDM topology for some material in the repository.
            A topology is a tree of levels (nodes). Each node is a PCDM Collection or Object with a key \
            (lower-case slug, unique in the topology), a label, optional extra rdf:types (absolute IRIs, \
            for example http://purl.org/ontology/bibo/Book), metadata fields (predicate IRIs such as \
            http://purl.org/dc/terms/title, creator, date, description, subject, extent), kinds of files \
            (PCDM use IRIs: http://pcdm.org/use#OriginalFile, ServiceFile, ThumbnailImage, ExtractedText, \
            Transcript, with expected media types) and child rules (min, max with -1 for unlimited, \
            initial, slugPattern such as page-{n}).
            Rules: an Object never contains a Collection; at most 8 levels; keep it as small as the \
            material needs.
            Prefer an existing topology (decision EXISTING with its id) when it fits the material well. \
            Only when none fits, decide NEW and give the complete draft.
            Give confidence between 0 and 1 and a short rationale (two sentences at most).
            """ + "Write labels, names, descriptions and the rationale in " + Prompts.language(locale) + ".\n";
    }

    private static TopologyDraft normalise(final TopologyDraft draft, final List<TopologySummary> existing) {
        String id = slugOf(StringUtils.hasText(draft.id()) ? draft.id() : draft.name());
        if (id.isEmpty()) {
            id = "custom";
        }
        final String base = id;
        int n = 2;
        final List<String> taken = new ArrayList<>(existing.stream().map(TopologySummary::id).toList());
        while (taken.contains(id)) {
            id = base + "-" + n++;
        }
        return new TopologyDraft(id, draft.name(), draft.description(), false, draft.root());
    }

    /**
     * Derives a slug.
     *
     * @param text the text
     * @return lower-case letters, digits and hyphens, at most 64 characters
     */
    public static String slugOf(final String text) {
        if (text == null) {
            return "";
        }
        final String ascii = Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        final String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        return slug.length() > 64 ? slug.substring(0, 64).replaceAll("-+$", "") : slug;
    }

    private static double clamp(final Double value) {
        return value == null || value.isNaN() ? 0 : Math.max(0, Math.min(1, value));
    }

    /** The model's decision. */
    public enum Decision {

        /** An existing topology fits. */
        EXISTING,

        /** A new topology is needed. */
        NEW

    }

    /**
     * The model's answer.
     *
     * @param decision   existing or new
     * @param existingId the existing topology's id
     * @param confidence how sure, 0 to 1
     * @param rationale  why
     * @param draft      the new topology
     */
    public record Advice(
            @JsonPropertyDescription("EXISTING when an existing topology fits, NEW otherwise") Decision decision,
            @JsonPropertyDescription("The id of the existing topology (decision EXISTING)") String existingId,
            @JsonPropertyDescription("How sure you are, from 0 to 1") Double confidence,
            @JsonPropertyDescription("Why, in at most two sentences") String rationale,
            @JsonPropertyDescription("The complete new topology (decision NEW only)") TopologyDraft draft) {
    }

}
