package com.articulorium.ai.media;

import java.util.ArrayList;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
import com.articulorium.ai.platform.Descriptions;
import com.articulorium.ai.platform.PlatformReader;
import com.articulorium.domain.rdf.ResourcePaths;
import com.articulorium.domain.vocabulary.Ldp;
import com.articulorium.domain.vocabulary.Premis;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import org.apache.jena.rdf.model.Model;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.content.Media;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

/**
 * Suggests metadata for a binary: technical and embedded metadata read from the file
 * ({@link MediaFacts}) and descriptive metadata from the model, which sees the file's facts, the
 * resources it belongs to (the item and its collection, read with the caller's credentials), the
 * text of a PDF and, with a vision model, the image itself.
 *
 * <p>Videos are not shown to the model (few models take video): they are described from their
 * container metadata and their context. Suggestions only use a fixed set of Dublin Core terms, so
 * the model cannot write anything else into the repository, and statements the description already
 * has are left out.</p>
 */
@Service
@RegisterReflectionForBinding(MediaDescriber.Answer.class)
public class MediaDescriber extends CapabilityAgent {

    /** Dates the model may suggest: a year, a month, a day, or a range of years. */
    static final Pattern DATE = Pattern.compile("\\d{4}(-\\d{2}(-\\d{2})?)?(/\\d{4}(-\\d{2}(-\\d{2})?)?)?");

    private final AgentModel model;

    private final AgentAccess access;

    private final PlatformReader platform;

    private final AiProperties properties;

    /**
     * Creates the describer.
     *
     * @param model      the chat model
     * @param access     the access check
     * @param platform   the repository
     * @param properties the agent settings
     */
    public MediaDescriber(final AgentModel model, final AgentAccess access, final PlatformReader platform,
            final AiProperties properties) {
        super(Capability.MEDIA, properties);
        this.model = model;
        this.access = access;
        this.platform = platform;
        this.properties = properties;
    }

    /**
     * Suggests metadata for a binary.
     *
     * @param path          the binary's path
     * @param authorization the caller's {@code Authorization} header
     * @param locale        the caller's locale (language of descriptions)
     * @return the suggestion
     */
    public MediaMetadataSuggestion describe(final String path, @Nullable final String authorization,
            final Locale locale) {
        model.require(Capability.MEDIA);
        if (path == null || path.isBlank()) {
            throw new AgentException(Reason.INVALID_REQUEST, "Name the binary to describe");
        }
        final String normalized = ResourcePaths.normalize(path);
        access.requireWrite(normalized, authorization);
        final String iri = platform.iri(normalized);
        final Model description = platform.rdf(normalized, authorization)
            .orElseThrow(() -> new AgentException(Reason.NOT_FOUND, "No resource /" + normalized));
        if (!Descriptions.hasType(description, iri, Ldp.NON_RDF_SOURCE)) {
            throw new AgentException(Reason.UNSUPPORTED, "/" + normalized + " is not a binary");
        }
        final String mediaType = Descriptions.literals(description, iri, Premis.HAS_MIME_TYPE).stream().findFirst()
            .orElse("application/octet-stream");
        final MediaKind kind = MediaKind.of(mediaType).orElseThrow(() -> new AgentException(Reason.UNSUPPORTED,
            "Binaries of type " + MediaKind.essence(mediaType) + " are not described"));
        final long size = Descriptions.literals(description, iri, Premis.HAS_SIZE).stream().findFirst()
            .map(MediaDescriber::parseLong).orElse(-1L);
        final String filename = Descriptions.literals(description, iri, Premis.FILENAME).stream().findFirst()
            .orElse(ResourcePaths.nameOf(normalized));
        final List<String> notes = new ArrayList<>();
        final long maxBytes = properties.getMedia().getMaxBytes().toBytes();
        byte[] bytes = null;
        if (size > maxBytes) {
            if (kind != MediaKind.VIDEO) {
                throw new AgentException(Reason.TOO_LARGE, "/" + normalized + " has " + size + " bytes; at most "
                    + maxBytes + " are read");
            }
            notes.add("FILE_NOT_READ");
        } else {
            bytes = platform.bytes(normalized, authorization, maxBytes)
                .orElseThrow(() -> new AgentException(Reason.NOT_FOUND, "No binary /" + normalized));
        }
        final MediaFacts.Facts facts = bytes == null ? new MediaFacts.Facts(List.of(), "", List.of(), 0)
            : kind == MediaKind.PDF ? MediaFacts.ofPdf(bytes, properties.getMedia().getMaxPdfPages(),
                properties.getMedia().getMaxTextLength())
            : MediaFacts.ofImageOrVideo(bytes, kind);
        notes.addAll(facts.notes());
        final List<Media> media = new ArrayList<>();
        if (kind == MediaKind.IMAGE && bytes != null) {
            if (!properties.getMedia().isImageInput()) {
                notes.add("IMAGE_NOT_SHOWN");
            } else if (!MediaKind.VISION_TYPES.contains(MediaKind.essence(mediaType))) {
                notes.add("IMAGE_FORMAT_NOT_SHOWN");
            } else if (bytes.length > properties.getMedia().getMaxImageBytes().toBytes()) {
                notes.add("IMAGE_TOO_LARGE_TO_SHOW");
            } else {
                media.add(Media.builder().mimeType(MimeType.valueOf(MediaKind.essence(mediaType))).data(bytes)
                    .name(filename).build());
            }
        }
        if (kind == MediaKind.VIDEO) {
            notes.add("VIDEO_NOT_SHOWN");
        }
        final String user = prompt(filename, mediaType, kind, size, facts, context(normalized, authorization))
            + (media.isEmpty() ? "" : "The image itself is attached.\n");
        final Answer answer = model.ask(Capability.MEDIA, new AgentPrompt(system(kind, locale), user, media),
            Answer.class);
        final List<MetadataStatement> statements = new ArrayList<>(facts.statements());
        statements.addAll(statements(answer, locale));
        final List<MetadataStatement> fresh = new ArrayList<>();
        for (final MetadataStatement statement : statements) {
            final boolean present = Descriptions.literals(description, iri, statement.predicate()).stream()
                .anyMatch(value -> value.strip().equalsIgnoreCase(statement.value().strip()));
            if (!present && fresh.stream().noneMatch(other -> other.sameAs(statement))) {
                fresh.add(statement);
            }
        }
        return new MediaMetadataSuggestion(normalized, normalized + "/" + PlatformReader.DESCRIPTION,
            MediaKind.essence(mediaType), kind, !media.isEmpty(), fresh, notes);
    }

    private String prompt(final String filename, final String mediaType, final MediaKind kind, final long size,
            final MediaFacts.Facts facts, final String context) {
        final StringBuilder file = new StringBuilder();
        file.append("file name: ").append(filename).append('\n');
        file.append("media type: ").append(MediaKind.essence(mediaType)).append('\n');
        if (size >= 0) {
            file.append("size: ").append(size).append(" bytes\n");
        }
        if (facts.pages() > 0) {
            file.append("pages: ").append(facts.pages()).append('\n');
        }
        for (final MetadataStatement statement : facts.statements()) {
            file.append("embedded ").append(local(statement.predicate())).append(": ")
                .append(Prompts.clip(statement.value(), 500)).append('\n');
        }
        final StringBuilder user = new StringBuilder(Prompts.data("file", file.toString()));
        if (!context.isEmpty()) {
            user.append(Prompts.data("part-of", context));
        }
        if (!facts.text().isBlank()) {
            user.append(Prompts.data("extracted-text", facts.text()));
        }
        user.append("Describe this ").append(kind.name().toLowerCase(Locale.ROOT)).append(".\n");
        return user.toString();
    }

    /**
     * Describes the resources a binary belongs to: its item (the parent of the {@code files}
     * container topologies use) and that item's parent.
     */
    private String context(final String path, @Nullable final String authorization) {
        final StringBuilder out = new StringBuilder();
        Optional<String> parent = ResourcePaths.parentOf(path);
        int levels = 0;
        while (parent.isPresent() && !parent.get().isEmpty() && levels < 3) {
            final String parentPath = parent.get();
            final Optional<Model> rdf = platform.rdf(parentPath, authorization);
            if (rdf.isEmpty()) {
                break;
            }
            final String iri = platform.iri(parentPath);
            final String title = Descriptions.title(rdf.get(), iri);
            if (!title.isEmpty()) {
                out.append(levels == 0 ? "in: " : "within: ").append(Prompts.clip(title, 200));
                final List<String> descriptions = Descriptions.descriptions(rdf.get(), iri);
                if (!descriptions.isEmpty()) {
                    out.append(" — ").append(Prompts.clip(descriptions.get(0), 400));
                }
                out.append('\n');
                levels++;
            }
            parent = ResourcePaths.parentOf(parentPath);
        }
        return out.toString();
    }

    private static String system(final MediaKind kind, final Locale locale) {
        return Prompts.RULES + """

            Task: suggest descriptive metadata (Dublin Core) for one file of a digital library: \
            """ + kind.name().toLowerCase(Locale.ROOT) + """
            . Use what the file shows and says, its embedded metadata and what it is part of.
            - title: the title of the work as it appears (in its own language); for an untitled image \
            a short descriptive title.
            - description: two or three factual sentences on what the file shows or contains.
            - subjects: up to 8 topics or keywords; people, places, events and themes.
            - creators: persons or organisations named as authors, photographers, makers. Never guess.
            - date: when the work was created, as YYYY, YYYY-MM, YYYY-MM-DD or a range YYYY/YYYY, \
            only when the material supports it.
            - language: the main language of the text in the work, as a BCP 47 tag (en, de, fr ...).
            - places and period: what the work is about (spatial and temporal coverage).
            - genre: the kind of work (photograph, map, letter, thesis, poster ...).
            Leave out whatever the material does not support.
            """ + "Write the description, subjects, places, period and genre in " + Prompts.language(locale)
            + "; keep titles and names as they are written in the work.\n";
    }

    private List<MetadataStatement> statements(final Answer answer, final Locale locale) {
        final List<MetadataStatement> out = new ArrayList<>();
        final String workLanguage = languageTag(answer.language());
        final String userLanguage = locale == null || locale.getLanguage().isEmpty() ? null : locale.toLanguageTag();
        add(out, "title", answer.title(), workLanguage);
        add(out, "alternative", answer.alternativeTitle(), workLanguage);
        add(out, "description", answer.description(), userLanguage);
        list(answer.subjects(), 12).forEach(subject -> add(out, "subject", subject, userLanguage));
        list(answer.creators(), 12).forEach(creator -> add(out, "creator", creator, null));
        if (answer.date() != null && DATE.matcher(answer.date().strip()).matches()) {
            add(out, "date", answer.date().strip(), null);
        }
        if (workLanguage != null) {
            add(out, "language", workLanguage, null);
        }
        list(answer.places(), 8).forEach(place -> add(out, "spatial", place, userLanguage));
        add(out, "temporal", answer.period(), userLanguage);
        add(out, "type", answer.genre(), userLanguage);
        return out;
    }

    private static void add(final List<MetadataStatement> out, final String term, final String value,
            final String language) {
        if (value != null && !value.isBlank() && value.strip().length() <= 4000) {
            out.add(MetadataStatement.literal(Descriptions.DCTERMS + term, value.strip(), language, null,
                MetadataStatement.Origin.MODEL));
        }
    }

    private static List<String> list(final List<String> values, final int max) {
        return values == null ? List.of() : values.stream().filter(value -> value != null && !value.isBlank())
            .map(String::strip).distinct().limit(max).toList();
    }

    /**
     * Checks a language tag.
     *
     * @param tag the tag the model gave
     * @return the well-formed tag, or {@code null}
     */
    static String languageTag(final String tag) {
        if (tag == null || tag.isBlank() || tag.length() > 35) {
            return null;
        }
        try {
            final Locale locale = new Locale.Builder().setLanguageTag(tag.strip()).build();
            return locale.getLanguage().isEmpty() ? null : locale.toLanguageTag();
        } catch (IllformedLocaleException e) {
            return null;
        }
    }

    private static String local(final String iri) {
        final int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return iri.substring(cut + 1);
    }

    private static Long parseLong(final String value) {
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * The model's answer.
     *
     * @param title            title
     * @param alternativeTitle another title
     * @param description      description
     * @param subjects         subjects
     * @param creators         creators
     * @param date             creation date
     * @param language         language of the work
     * @param places           places
     * @param period           period
     * @param genre            kind of work
     */
    public record Answer(
            @JsonPropertyDescription("Title of the work") String title,
            @JsonPropertyDescription("Another title (translation, subtitle), usually omitted") String alternativeTitle,
            @JsonPropertyDescription("Two or three factual sentences") String description,
            @JsonPropertyDescription("Topics and keywords, at most 8") List<String> subjects,
            @JsonPropertyDescription("Authors or makers named in the material") List<String> creators,
            @JsonPropertyDescription("YYYY, YYYY-MM, YYYY-MM-DD or YYYY/YYYY") String date,
            @JsonPropertyDescription("BCP 47 tag of the language of the work's text") String language,
            @JsonPropertyDescription("Places the work is about") List<String> places,
            @JsonPropertyDescription("Period the work is about") String period,
            @JsonPropertyDescription("Kind of work, for example photograph") String genre) {
    }

}
