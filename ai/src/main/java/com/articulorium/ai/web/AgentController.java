package com.articulorium.ai.web;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.articulorium.agent.AgentRegistry;
import com.articulorium.ai.Capability;
import com.articulorium.ai.classification.Classification;
import com.articulorium.ai.classification.ClassificationRequest;
import com.articulorium.ai.classification.Classifier;
import com.articulorium.ai.config.AiProperties;
import com.articulorium.ai.media.MediaDescriber;
import com.articulorium.ai.media.MediaMetadataRequest;
import com.articulorium.ai.media.MediaMetadataSuggestion;
import com.articulorium.ai.model.AgentModel;
import com.articulorium.ai.profiles.ProfileEnhancer;
import com.articulorium.ai.profiles.ProfileSuggestion;
import com.articulorium.ai.profiles.ProfileSuggestionRequest;
import com.articulorium.ai.topics.TopicModel;
import com.articulorium.ai.topics.TopicModelRequest;
import com.articulorium.ai.topics.TopicModeler;
import com.articulorium.ai.topology.TopologyAssistant;
import com.articulorium.ai.topology.TopologySuggestion;
import com.articulorium.ai.topology.TopologySuggestionRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The agent API. Every suggestion is computed for the caller with the caller's own credentials and
 * returned, never saved: the caller reviews it and applies what it keeps with the existing APIs.
 * Asking about a resource needs write access to it (see {@code AgentAccess}).
 *
 * <p>Errors are RFC 9457 problem documents with a {@code reason}: a disabled agent or capability, no
 * model, a busy agent, a failing model, an unusable answer, an unsupported or too large binary, a
 * refused caller.</p>
 */
@RestController
@Tag(name = "AI agent", description = "Suggestions from a chat model, reviewed before they are applied")
public class AgentController {

    private final AiProperties properties;

    private final AgentRegistry agents;

    private final AgentModel model;

    private final TopologyAssistant topologies;

    private final MediaDescriber media;

    private final TopicModeler topics;

    private final Classifier classifier;

    private final ProfileEnhancer profiles;

    /**
     * Creates the controller.
     *
     * @param properties the agent settings
     * @param agents     the agents running (the capabilities that are on)
     * @param model      the chat model
     * @param topologies topology suggestions
     * @param media      media metadata
     * @param topics     topic models
     * @param classifier classification of harvested records
     * @param profiles   suggestions for academic profiles
     */
    public AgentController(final AiProperties properties, final AgentRegistry agents, final AgentModel model,
            final TopologyAssistant topologies, final MediaDescriber media, final TopicModeler topics,
            final Classifier classifier, final ProfileEnhancer profiles) {
        this.properties = properties;
        this.agents = agents;
        this.model = model;
        this.topologies = topologies;
        this.media = media;
        this.topics = topics;
        this.classifier = classifier;
        this.profiles = profiles;
    }

    /**
     * Tells what the agent offers now: the capabilities running as agents (the agent registry),
     * when a model is configured. Public: it reveals no repository content.
     *
     * @return the capabilities
     */
    @GetMapping(path = "/capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "aiCapabilities", summary = "What the AI agent offers")
    public Capabilities capabilities() {
        final boolean available = model.available();
        final Map<String, Boolean> offered = new LinkedHashMap<>();
        for (final Capability capability : Capability.values()) {
            offered.put(capability.key(), available && agents.find(capability.agentName()).isPresent());
        }
        return new Capabilities(properties.isEnabled(), available,
            properties.isEnabled() ? model.describe().orElse(null) : null, offered);
    }

    /**
     * Suggests a topology for some material.
     *
     * @param request       the material
     * @param authorization the caller's credentials
     * @param locale        the caller's locale
     * @return an existing topology, or a draft to review in the topology editor
     */
    @PostMapping(path = "/topology-suggestions", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "suggestTopology", summary = "Suggest a topology (existing, or a draft) for some material")
    public TopologySuggestion suggestTopology(@RequestBody final TopologySuggestionRequest request,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) final String authorization,
            final Locale locale) {
        return topologies.suggest(request, authorization, locale);
    }

    /**
     * Suggests metadata for a binary.
     *
     * @param request       the binary
     * @param authorization the caller's credentials
     * @param locale        the caller's locale
     * @return statements to review and add to the binary's description
     */
    @PostMapping(path = "/media-metadata", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "suggestMediaMetadata", summary = "Suggest metadata for an image, video or PDF")
    public MediaMetadataSuggestion suggestMediaMetadata(@RequestBody final MediaMetadataRequest request,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) final String authorization,
            final Locale locale) {
        return media.describe(request.path(), authorization, locale);
    }

    /**
     * Models the topics of a community or collection.
     *
     * @param request       the community or collection
     * @param authorization the caller's credentials
     * @param locale        the caller's locale
     * @return topics and a summary to review and add to it
     */
    @PostMapping(path = "/topic-models", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "modelTopics", summary = "Suggest the topics and a summary of a community or collection")
    public TopicModel modelTopics(@RequestBody final TopicModelRequest request,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) final String authorization,
            final Locale locale) {
        return topics.model(request, authorization, locale);
    }

    /**
     * Suggests an overview, research areas and keywords for an academic profile.
     *
     * @param request       the profile
     * @param authorization the caller's credentials
     * @param locale        the caller's locale
     * @return suggestions to review and add to the profile
     */
    @PostMapping(path = "/profile-suggestions", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "suggestProfile", summary = "Suggest the overview, research areas and keywords of a profile")
    public ProfileSuggestion suggestProfile(@RequestBody final ProfileSuggestionRequest request,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) final String authorization,
            final Locale locale) {
        return profiles.suggest(request, authorization, locale);
    }

    /**
     * Classifies a harvested record.
     *
     * @param request       the record
     * @param authorization the caller's credentials (the harvest service's)
     * @param locale        the language of the rationale
     * @return where the record belongs and its topology
     */
    @PostMapping(path = "/classifications", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "classifyRecord", summary = "Suggest the collection and topology of a harvested record")
    public Classification classify(@RequestBody final ClassificationRequest request,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) final String authorization,
            final Locale locale) {
        return classifier.classify(request, authorization, locale);
    }

}
