package com.articulorium.ai.profiles;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import com.articulorium.ai.AgentException;
import com.articulorium.ai.AgentException.Reason;
import com.articulorium.ai.Capability;
import com.articulorium.ai.CapabilityAgent;
import com.articulorium.ai.config.AiProperties;
import com.articulorium.ai.model.AgentModel;
import com.articulorium.ai.model.AgentPrompt;
import com.articulorium.ai.model.Prompts;
import com.articulorium.ai.platform.AgentAccess;
import com.articulorium.ai.platform.PlatformReader;
import com.articulorium.domain.rdf.ResourcePaths;
import com.articulorium.domain.vocabulary.Rdf;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.stereotype.Service;

/**
 * Suggests what an academic profile is missing: an overview, research areas and keywords, drawn
 * from what the profile records (its positions, degrees and publications, as the profile editor
 * writes them with the VIVO ontology). Profiles are read with the caller's credentials and asking
 * needs write access to the profile, as for every capability.
 *
 * <p>Research areas and keywords the profile has already are left out, and so are keywords that
 * repeat a suggested research area. Nothing is saved: the user interface merges what the person
 * keeps into the profile and saves it as the profile editor does (one replacement guarded by the
 * profile's entity tag), so research areas become the editor's own {@code skos:Concept}s.</p>
 */
@Service
@RegisterReflectionForBinding(ProfileEnhancer.Answer.class)
public class ProfileEnhancer extends CapabilityAgent {

    /** FOAF: the class of every profile. */
    static final String FOAF_PERSON = "http://xmlns.com/foaf/0.1/Person";

    /** The VIVO ontology. */
    static final String VIVO = "http://vivoweb.org/ontology/core#";

    /** OBO relations VIVO uses. */
    static final String OBO = "http://purl.obolibrary.org/obo/";

    private final AgentModel model;

    private final AgentAccess access;

    private final PlatformReader platform;

    private final AiProperties properties;

    /**
     * Creates the enhancer.
     *
     * @param model      the chat model
     * @param access     the access check
     * @param platform   the repository
     * @param properties the agent settings
     */
    public ProfileEnhancer(final AgentModel model, final AgentAccess access, final PlatformReader platform,
            final AiProperties properties) {
        super(Capability.PROFILES, properties);
        this.model = model;
        this.access = access;
        this.platform = platform;
        this.properties = properties;
    }

    /**
     * Suggests an overview, research areas and keywords for a profile.
     *
     * @param request       the profile
     * @param authorization the caller's {@code Authorization} header
     * @param locale        the caller's locale (language of the suggestions)
     * @return the suggestions
     */
    public ProfileSuggestion suggest(final ProfileSuggestionRequest request, @Nullable final String authorization,
            final Locale locale) {
        model.require(Capability.PROFILES);
        if (request == null || request.path() == null || request.path().isBlank()) {
            throw new AgentException(Reason.INVALID_REQUEST, "Name the profile");
        }
        final String path = ResourcePaths.normalize(request.path());
        access.requireWrite(path, authorization);
        final Model rdf = platform.rdf(path, authorization)
            .orElseThrow(() -> new AgentException(Reason.NOT_FOUND, "No resource /" + path));
        final Resource person = rdf.createResource(platform.iri(path));
        if (!rdf.contains(person, rdf.createProperty(Rdf.TYPE), rdf.createResource(FOAF_PERSON))) {
            throw new AgentException(Reason.UNSUPPORTED, "/" + path + " is not an academic profile");
        }
        final AiProperties.Profiles settings = properties.getProfiles();
        final Read read = read(rdf, person, Math.max(1, settings.getMaxEntries()));
        final String language = locale == null || locale.getLanguage().isEmpty() ? null : locale.toLanguageTag();
        final ProfileSuggestion.BasedOn basedOn = new ProfileSuggestion.BasedOn(read.positions().size(),
            read.education().size(), read.publications().size());
        if (read.positions().isEmpty() && read.education().isEmpty() && read.publications().isEmpty()) {
            // nothing to draw from: no model call, the user interface asks for positions or publications
            return new ProfileSuggestion(path, "", !read.overview().isBlank(), List.of(), List.of(), basedOn, language);
        }
        final Answer answer = model.ask(Capability.PROFILES, AgentPrompt.of(system(settings, locale),
            prompt(read)), Answer.class);

        final Set<String> taken = new LinkedHashSet<>();
        read.researchAreas().forEach(area -> taken.add(key(area)));
        final List<String> areas = clean(answer.researchAreas(), taken, settings.getMaxResearchAreas(), 120);
        read.keywords().forEach(keyword -> taken.add(key(keyword)));
        final List<String> keywords = clean(answer.keywords(), taken, settings.getMaxKeywords(), 80);
        final String overview = answer.overview() == null ? ""
            : Prompts.clip(answer.overview(), Math.max(200, settings.getMaxOverviewLength()));
        return new ProfileSuggestion(path, overview, !read.overview().isBlank(), areas, keywords, basedOn, language);
    }

    private static String prompt(final Read read) {
        final StringBuilder user = new StringBuilder();
        user.append(Prompts.data("name", read.name().isBlank() ? "(unnamed)" : read.name()));
        if (!read.positions().isEmpty()) {
            user.append(Prompts.data("positions", String.join("\n", read.positions())));
        }
        if (!read.education().isEmpty()) {
            user.append(Prompts.data("education", String.join("\n", read.education())));
        }
        if (!read.publications().isEmpty()) {
            user.append(Prompts.data("publications", String.join("\n", read.publications())));
        }
        if (!read.overview().isBlank()) {
            user.append(Prompts.data("current-overview", Prompts.clip(read.overview(), 3000)));
        }
        if (!read.researchAreas().isEmpty() || !read.keywords().isEmpty()) {
            final List<String> known = new ArrayList<>(read.researchAreas());
            known.addAll(read.keywords());
            user.append(Prompts.data("current-research-areas-and-keywords", String.join("; ", known)));
        }
        return user.append("Suggest the overview, research areas and keywords of this profile.\n").toString();
    }

    private static String system(final AiProperties.Profiles settings, final Locale locale) {
        return Prompts.RULES + """

            Task: enrich the academic profile of a researcher in a VIVO research information system. \
            From the positions, education and publications given (and nothing else), suggest:
            - overview: a factual third-person overview of the person's research and roles, two to five \
            sentences, without praise and without facts the material does not state; improve the current \
            overview when there is one;
            """ + "- researchAreas: at most " + settings.getMaxResearchAreas() + """
             broad research areas, as subject headings (for example "Radiochemistry"), none of the \
            current ones;
            """ + "- keywords: at most " + settings.getMaxKeywords() + """
             specific keywords (methods, topics, objects of study), none of the current ones and none \
            repeating a research area.
            """ + "Write the overview, research areas and keywords in " + Prompts.language(locale) + ".\n";
    }

    /** Reads what the profile records, as the profile editor writes it. */
    private static Read read(final Model rdf, final Resource person, final int maxEntries) {
        final Property label = rdf.createProperty(Rdf.RDFS_LABEL);
        final Property relatedBy = rdf.createProperty(VIVO + "relatedBy");
        final Property relates = rdf.createProperty(VIVO + "relates");
        final Function<RDFNode, String> labelOf = node -> node != null && node.isResource()
            ? literal(node.asResource(), label) : "";

        final List<String> positions = new ArrayList<>();
        final List<String> publications = new ArrayList<>();
        for (final RDFNode node : rdf.listObjectsOfProperty(person, relatedBy).toList()) {
            if (!node.isResource()) {
                continue;
            }
            final Resource relation = node.asResource();
            final RDFNode other = rdf.listObjectsOfProperty(relation, relates).toList().stream()
                .filter(object -> object.isResource() && !object.equals(person)).findFirst().orElse(null);
            if (hasType(rdf, relation, VIVO + "Authorship")) {
                final String title = labelOf.apply(other);
                if (!title.isBlank() && publications.size() < maxEntries) {
                    final RDFNode venue = other == null ? null : rdf.listObjectsOfProperty(other.asResource(),
                        rdf.createProperty(VIVO + "hasPublicationVenue")).toList().stream().findFirst().orElse(null);
                    final String venueLabel = labelOf.apply(venue);
                    publications.add(Prompts.clip(venueLabel.isBlank() ? title : title + " (" + venueLabel + ")", 300));
                }
            } else if (position(rdf, relation) && positions.size() < maxEntries) {
                final String title = literal(relation, label);
                final String organization = labelOf.apply(other);
                final String text = organization.isBlank() ? title : title.isBlank() ? organization
                    : title + ", " + organization;
                if (!text.isBlank()) {
                    positions.add(Prompts.clip(text, 200));
                }
            }
        }

        final List<String> education = new ArrayList<>();
        for (final RDFNode node : rdf.listObjectsOfProperty(person, rdf.createProperty(OBO + "RO_0000056")).toList()) {
            if (!node.isResource() || !hasType(rdf, node.asResource(), VIVO + "EducationalProcess")
                    || education.size() >= maxEntries) {
                continue;
            }
            final Resource process = node.asResource();
            final String degree = labelOf.apply(rdf.listObjectsOfProperty(process,
                rdf.createProperty(OBO + "RO_0002234")).toList().stream().findFirst().orElse(null));
            final String field = literal(process, rdf.createProperty(VIVO + "majorField"));
            final String text = (degree + (field.isBlank() ? "" : " in " + field)).strip();
            if (!text.isBlank()) {
                education.add(Prompts.clip(text, 200));
            }
        }

        final List<String> areas = rdf.listObjectsOfProperty(person, rdf.createProperty(VIVO + "hasResearchArea"))
            .toList().stream().map(labelOf).filter(text -> !text.isBlank()).toList();
        final List<String> keywords = rdf.listObjectsOfProperty(person, rdf.createProperty(VIVO + "freetextKeyword"))
            .toList().stream().filter(RDFNode::isLiteral).map(node -> node.asLiteral().getString().strip())
            .filter(text -> !text.isBlank()).toList();
        return new Read(literal(person, label), positions, education, publications,
            literal(person, rdf.createProperty(VIVO + "overview")), areas, keywords);
    }

    /** Positions as the editor writes them: {@code vivo:Position} or one of its subclasses. */
    private static boolean position(final Model rdf, final Resource node) {
        return rdf.listObjectsOfProperty(node, rdf.createProperty(Rdf.TYPE)).toList().stream()
            .filter(RDFNode::isURIResource).map(type -> type.asResource().getURI())
            .anyMatch(type -> type.startsWith(VIVO) && type.endsWith("Position"));
    }

    private static boolean hasType(final Model rdf, final Resource node, final String type) {
        return rdf.contains(node, rdf.createProperty(Rdf.TYPE), rdf.createResource(type));
    }

    private static String literal(final Resource subject, final Property predicate) {
        final var statement = subject.getProperty(predicate);
        return statement != null && statement.getObject().isLiteral()
            ? statement.getObject().asLiteral().getString().strip() : "";
    }

    /** Keeps distinct, new, non-blank texts, shortened, up to a number. */
    private static List<String> clean(@Nullable final List<String> texts, final Set<String> taken, final int max,
            final int length) {
        final List<String> kept = new ArrayList<>();
        for (final String text : texts == null ? List.<String>of() : texts) {
            if (text == null || text.isBlank() || kept.size() >= Math.max(0, max)) {
                continue;
            }
            final String clipped = Prompts.clip(text.replaceAll("\\s+", " "), length);
            if (taken.add(key(clipped))) {
                kept.add(clipped);
            }
        }
        return kept;
    }

    private static String key(final String text) {
        return text.strip().toLowerCase(Locale.ROOT);
    }

    private record Read(String name, List<String> positions, List<String> education, List<String> publications,
            String overview, List<String> researchAreas, List<String> keywords) {
    }

    /**
     * The model's answer.
     *
     * @param overview      the overview
     * @param researchAreas broad research areas
     * @param keywords      specific keywords
     */
    public record Answer(
            @JsonPropertyDescription("Two to five factual sentences in the third person") String overview,
            @JsonPropertyDescription("Broad research areas, as subject headings") List<String> researchAreas,
            @JsonPropertyDescription("Specific keywords: methods, topics, objects of study") List<String> keywords) {
    }

}
