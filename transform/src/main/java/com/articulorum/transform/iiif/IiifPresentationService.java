package com.articulorum.transform.iiif;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.articulorum.domain.vocabulary.Articulorum;
import com.articulorum.domain.vocabulary.Fedora;
import com.articulorum.domain.vocabulary.Ldp;
import com.articulorum.domain.vocabulary.Pcdm;
import com.articulorum.domain.vocabulary.Premis;
import com.articulorum.domain.vocabulary.Rdf;

import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.springframework.stereotype.Service;

/**
 * Exports PCDM structures as IIIF Presentation API 3.0 documents.
 *
 * <ul>
 *   <li>A PCDM object becomes a <strong>Manifest</strong>. Each object in its member tree that has
 *       no members of its own (a page, the front of a postcard, an article) becomes a
 *       <strong>Canvas</strong>, in {@code schema:position} order; its image, audio or video file
 *       (preferring {@code pcdmuse:ServiceFile}, then {@code pcdmuse:OriginalFile}) is painted on
 *       it, and its other files (PDF, OCR text, transcripts) are linked as {@code rendering}.
 *       Intermediate objects (an issue's sections, a folio with recto and verso) become
 *       <strong>Ranges</strong> in {@code structures}.</li>
 *   <li>A PCDM collection, community, sub-community or collection of the community structure
 *       becomes a <strong>Collection</strong> listing its members (or, without PCDM membership,
 *       its contained collections and objects) as Manifests and Collections.</li>
 *   <li>Every descriptive literal (Dublin Core, BIBO, schema.org, ...) becomes a {@code metadata}
 *       entry; {@code dcterms:title} is the label, {@code dcterms:description} or
 *       {@code dcterms:abstract} the summary, {@code dcterms:rights} the required statement and an
 *       IRI-valued {@code dcterms:license} the rights. Every document links back to the LDP
 *       resource ({@code seeAlso}) and to its page in the user interface ({@code homepage}).</li>
 * </ul>
 *
 * @see <a href="https://iiif.io/api/presentation/3.0/">IIIF Presentation API 3.0</a>
 */
@Service
public class IiifPresentationService {

    /** The IIIF Presentation 3 JSON-LD context. */
    public static final String CONTEXT = "http://iiif.io/api/presentation/3/context.json";

    /** Media type of IIIF Presentation 3 documents. */
    public static final String MEDIA_TYPE = "application/ld+json;profile=\"" + CONTEXT + "\"";

    static final String DCTERMS = "http://purl.org/dc/terms/";

    static final String DC = "http://purl.org/dc/elements/1.1/";

    static final String SCHEMA_POSITION = "http://schema.org/position";

    static final String EBUCORE_DURATION = Premis.EBUCORE_NAMESPACE + "duration";

    /** Namespaces whose statements are structural or technical, never descriptive metadata. */
    static final List<String> NON_DESCRIPTIVE = List.of(Rdf.NAMESPACE, Fedora.NAMESPACE, Ldp.NAMESPACE,
        Articulorum.NAMESPACE, Pcdm.NAMESPACE, Pcdm.USE_NAMESPACE, Premis.NAMESPACE, Premis.EBUCORE_NAMESPACE,
        "http://mementoweb.org/ns#", "http://www.iana.org/assignments/relation/", "http://www.w3.org/ns/auth/acl#");

    private final IiifPlatformReader reader;

    private final IiifProperties properties;

    /**
     * Creates the service.
     *
     * @param reader     reads resources from the platform
     * @param properties IIIF settings
     */
    public IiifPresentationService(final IiifPlatformReader reader, final IiifProperties properties) {
        this.reader = reader;
        this.properties = properties;
    }

    /**
     * Exports a resource: a Manifest for a PCDM object, a Collection for collections and
     * communities.
     *
     * @param path    the resource path
     * @param context the caller's view
     * @return the JSON-LD document as nested maps and lists
     * @throws IiifException when the resource does not exist (404) or cannot be exported
     */
    public Map<String, Object> export(final String path, final PublicContext context) {
        final Export export = new Export(context);
        final View view = export.view(path)
            .orElseThrow(() -> new IiifException(404, "No resource /" + path, null));
        if (view.isCollectionLike()) {
            return export.collection(view);
        }
        if (!view.types().contains(Pcdm.OBJECT)) {
            throw new IiifException(422, "/" + path + " is neither a PCDM object nor a collection", null);
        }
        return export.manifest(view);
    }

    // ------------------------------------------------------------------------------------------
    // Views of resources
    // ------------------------------------------------------------------------------------------

    /**
     * What an export needs to know about a resource.
     *
     * @param iri      the public IRI
     * @param path     the path
     * @param model    its RDF
     * @param subject  its subject in the model
     * @param types    its {@code rdf:type}s
     */
    record View(String iri, String path, Model model, Resource subject, Set<String> types) {

        static View of(final String path, final String iri, final Model model) {
            final Resource subject = model.createResource(iri);
            final Set<String> types = new LinkedHashSet<>();
            subject.listProperties(model.createProperty(Rdf.TYPE)).forEachRemaining(statement -> {
                if (statement.getObject().isURIResource()) {
                    types.add(statement.getObject().asResource().getURI());
                }
            });
            return new View(iri, path, model, subject, types);
        }

        boolean isCollectionLike() {
            return types.contains(Pcdm.COLLECTION) || types.contains(Articulorum.COMMUNITY)
                || types.contains(Articulorum.COLLECTION) || types.contains(Articulorum.COMMUNITY_ROOT);
        }

        Optional<String> literal(final String... predicates) {
            for (final String predicate : predicates) {
                final Statement statement = subject.getProperty(model.createProperty(predicate));
                if (statement != null && statement.getObject().isLiteral()) {
                    return Optional.of(statement.getObject().asLiteral().getLexicalForm());
                }
            }
            return Optional.empty();
        }

        List<String> iris(final String predicate) {
            final List<String> values = new ArrayList<>();
            subject.listProperties(model.createProperty(predicate)).forEachRemaining(statement -> {
                if (statement.getObject().isURIResource()) {
                    values.add(statement.getObject().asResource().getURI());
                }
            });
            values.sort(Comparator.naturalOrder());
            return values;
        }

        String label() {
            return literal(DCTERMS + "title", DC + "title", "http://www.w3.org/2000/01/rdf-schema#label")
                .orElseGet(() -> path.isEmpty() ? "Repository" : path.substring(path.lastIndexOf('/') + 1));
        }

        int position() {
            return literal(SCHEMA_POSITION).map(value -> {
                try {
                    return Integer.parseInt(value.trim());
                } catch (NumberFormatException e) {
                    return Integer.MAX_VALUE;
                }
            }).orElse(Integer.MAX_VALUE);
        }

    }

    /**
     * A file of a PCDM object.
     *
     * @param iri      the binary's public IRI
     * @param path     its path
     * @param mimeType its media type
     * @param filename its file name
     * @param uses     its PCDM use types
     */
    record FileInfo(String iri, String path, String mimeType, String filename, Set<String> uses) {

        String kind() {
            final String type = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
            if (type.startsWith("image/")) {
                return "Image";
            }
            if (type.startsWith("audio/")) {
                return "Sound";
            }
            if (type.startsWith("video/")) {
                return "Video";
            }
            if (type.startsWith("text/") || type.equals("application/pdf") || type.contains("xml")
                    || type.contains("html")) {
                return "Text";
            }
            return "Dataset";
        }

        boolean paintable() {
            return switch (kind()) {
                case "Image", "Sound", "Video" -> true;
                default -> false;
            };
        }

        int preference() {
            if (uses.contains(Pcdm.SERVICE_FILE)) {
                return 0;
            }
            if (uses.contains(Pcdm.ORIGINAL_FILE)) {
                return 1;
            }
            return uses.contains(Pcdm.THUMBNAIL_IMAGE) ? 3 : 2;
        }

        String label() {
            for (final String use : uses) {
                if (use.startsWith(Pcdm.USE_NAMESPACE)) {
                    return IiifPresentationService.humanize(use.substring(Pcdm.USE_NAMESPACE.length()))
                        + (filename == null ? "" : " (" + filename + ")");
                }
            }
            return filename == null ? path.substring(path.lastIndexOf('/') + 1) : filename;
        }

    }

    // ------------------------------------------------------------------------------------------
    // One export
    // ------------------------------------------------------------------------------------------

    /** The state of one export: the caller's view, the read budget and running identifiers. */
    final class Export {

        private final PublicContext context;

        private int budget = properties.getMaxResources();

        private int canvasCount;

        private int rangeCount;

        private String manifestId;

        Export(final PublicContext context) {
            this.context = context;
        }

        Optional<View> view(final String path) {
            if (--budget < 0) {
                throw new IiifException(413, "The export reads more than " + properties.getMaxResources()
                    + " resources", null);
            }
            return reader.rdf(path, context).map(model -> View.of(path, context.resourceIri(path), model));
        }

        Map<String, Object> collection(final View view) {
            final Map<String, Object> collection = describe(view, "Collection", context.iiifUrl(view.path()));
            final List<Map<String, Object>> items = new ArrayList<>();
            List<String> children = view.iris(Pcdm.HAS_MEMBER);
            if (children.isEmpty()) {
                children = view.iris(Ldp.CONTAINS);
            }
            final List<View> views = new ArrayList<>();
            for (final String child : children) {
                final String childPath = context.pathOf(child);
                if (childPath != null) {
                    view(childPath).ifPresent(views::add);
                }
            }
            views.sort(Comparator.comparingInt(View::position).thenComparing(View::label));
            for (final View child : views) {
                if (child.isCollectionLike() || child.types().contains(Pcdm.OBJECT)) {
                    final Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", context.iiifUrl(child.path()));
                    item.put("type", child.isCollectionLike() ? "Collection" : "Manifest");
                    item.put("label", language(child.label()));
                    items.add(item);
                }
            }
            collection.put("items", items);
            return collection;
        }

        Map<String, Object> manifest(final View root) {
            manifestId = context.iiifUrl(root.path());
            final Map<String, Object> manifest = describe(root, "Manifest", manifestId);
            final List<Map<String, Object>> canvases = new ArrayList<>();
            final List<Map<String, Object>> structures = new ArrayList<>();
            final List<FileInfo> rootFiles = files(root);
            final List<View> members = members(root);
            if (members.isEmpty()) {
                canvases.add(canvas(root, rootFiles));
            } else {
                final List<Map<String, Object>> renderings = renderings(rootFiles);
                if (!renderings.isEmpty()) {
                    manifest.put("rendering", renderings);
                }
                for (final View member : members) {
                    visit(member, canvases, structures, null);
                }
            }
            if (canvases.isEmpty()) {
                canvases.add(canvas(root, List.of()));
            }
            thumbnail(canvases).ifPresent(thumbnail -> manifest.put("thumbnail", List.of(thumbnail)));
            manifest.put("items", canvases);
            if (!structures.isEmpty()) {
                manifest.put("structures", structures);
            }
            return manifest;
        }

        private void visit(final View node, final List<Map<String, Object>> canvases,
                final List<Map<String, Object>> structures, final Map<String, Object> parentRange) {
            final List<View> members = members(node);
            if (members.isEmpty()) {
                final Map<String, Object> canvas = canvas(node, files(node));
                canvases.add(canvas);
                if (parentRange != null) {
                    rangeItems(parentRange).add(reference((String) canvas.get("id"), "Canvas"));
                }
                return;
            }
            final Map<String, Object> range = new LinkedHashMap<>();
            range.put("id", manifestId + "/range/" + (++rangeCount));
            range.put("type", "Range");
            range.put("label", language(node.label()));
            range.put("items", new ArrayList<Map<String, Object>>());
            if (parentRange == null) {
                structures.add(range);
            } else {
                rangeItems(parentRange).add(range);
            }
            final List<FileInfo> files = files(node);
            final List<Map<String, Object>> renderings = renderings(files);
            if (!renderings.isEmpty()) {
                range.put("rendering", renderings);
            }
            for (final View member : members) {
                visit(member, canvases, structures, range);
            }
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> rangeItems(final Map<String, Object> range) {
            return (List<Map<String, Object>>) range.get("items");
        }

        private List<View> members(final View node) {
            final List<View> members = new ArrayList<>();
            for (final String iri : node.iris(Pcdm.HAS_MEMBER)) {
                final String path = context.pathOf(iri);
                if (path != null) {
                    view(path).filter(member -> member.types().contains(Pcdm.OBJECT)).ifPresent(members::add);
                }
            }
            members.sort(Comparator.comparingInt(View::position).thenComparing(View::path));
            return members;
        }

        private List<FileInfo> files(final View node) {
            final List<FileInfo> files = new ArrayList<>();
            for (final String iri : node.iris(Pcdm.HAS_FILE)) {
                final String path = context.pathOf(iri);
                if (path == null) {
                    continue;
                }
                view(path + "/description").ifPresent(description -> {
                    final Resource subject = description.model().createResource(iri);
                    final Set<String> uses = new LinkedHashSet<>();
                    subject.listProperties(description.model().createProperty(Rdf.TYPE)).forEachRemaining(s -> {
                        if (s.getObject().isURIResource()) {
                            uses.add(s.getObject().asResource().getURI());
                        }
                    });
                    files.add(new FileInfo(iri, path, literal(subject, Premis.HAS_MIME_TYPE),
                        literal(subject, Premis.FILENAME), uses));
                });
            }
            return files;
        }

        private Map<String, Object> canvas(final View node, final List<FileInfo> files) {
            final String canvasId = manifestId + "/canvas/" + (++canvasCount);
            final Map<String, Object> canvas = new LinkedHashMap<>();
            canvas.put("id", canvasId);
            canvas.put("type", "Canvas");
            canvas.put("label", language(node.label()));
            final List<Map<String, Object>> metadata = metadata(node);
            if (!metadata.isEmpty()) {
                canvas.put("metadata", metadata);
            }
            final Optional<FileInfo> painted = files.stream()
                .filter(FileInfo::paintable)
                .min(Comparator.comparingInt(FileInfo::preference));
            final List<Map<String, Object>> annotationPages = new ArrayList<>();
            if (painted.isPresent()) {
                final FileInfo file = painted.get();
                final Map<String, Object> body = new LinkedHashMap<>();
                body.put("id", file.iri());
                body.put("type", file.kind());
                if (file.mimeType() != null) {
                    body.put("format", file.mimeType());
                }
                if ("Image".equals(file.kind())) {
                    final ImageDimensions.Size size = size(file);
                    body.put("width", size.width());
                    body.put("height", size.height());
                    canvas.put("width", size.width());
                    canvas.put("height", size.height());
                } else {
                    final double duration = duration(node);
                    body.put("duration", duration);
                    canvas.put("duration", duration);
                    if ("Video".equals(file.kind())) {
                        canvas.put("width", properties.getDefaultWidth());
                        canvas.put("height", properties.getDefaultHeight());
                    }
                }
                final Map<String, Object> annotation = new LinkedHashMap<>();
                annotation.put("id", canvasId + "/annotation/1");
                annotation.put("type", "Annotation");
                annotation.put("motivation", "painting");
                annotation.put("body", body);
                annotation.put("target", canvasId);
                final Map<String, Object> page = new LinkedHashMap<>();
                page.put("id", canvasId + "/page/1");
                page.put("type", "AnnotationPage");
                page.put("items", List.of(annotation));
                annotationPages.add(page);
            } else {
                canvas.put("width", properties.getDefaultWidth());
                canvas.put("height", properties.getDefaultHeight());
            }
            canvas.put("items", annotationPages);
            final List<Map<String, Object>> renderings = renderings(files.stream()
                .filter(file -> painted.isEmpty() || !file.equals(painted.get()))
                .toList());
            if (!renderings.isEmpty()) {
                canvas.put("rendering", renderings);
            }
            canvas.put("seeAlso", List.of(seeAlso(node)));
            return canvas;
        }

        private ImageDimensions.Size size(final FileInfo file) {
            if (properties.isProbeImageSize()) {
                final Optional<ImageDimensions.Size> size = reader.head(file.path(), properties.getProbeBytes(), context)
                    .flatMap(ImageDimensions::read);
                if (size.isPresent()) {
                    return size.get();
                }
            }
            return new ImageDimensions.Size(properties.getDefaultWidth(), properties.getDefaultHeight());
        }

        private List<Map<String, Object>> renderings(final List<FileInfo> files) {
            final List<Map<String, Object>> renderings = new ArrayList<>();
            for (final FileInfo file : files) {
                final Map<String, Object> rendering = new LinkedHashMap<>();
                rendering.put("id", file.iri());
                rendering.put("type", file.kind());
                rendering.put("label", language(file.label()));
                if (file.mimeType() != null) {
                    rendering.put("format", file.mimeType());
                }
                renderings.add(rendering);
            }
            return renderings;
        }

        private Map<String, Object> describe(final View view, final String type, final String id) {
            final Map<String, Object> document = new LinkedHashMap<>();
            document.put("@context", CONTEXT);
            document.put("id", id);
            document.put("type", type);
            document.put("label", language(view.label()));
            view.literal(DCTERMS + "description", DCTERMS + "abstract", DC + "description")
                .ifPresent(summary -> document.put("summary", language(summary)));
            final List<Map<String, Object>> metadata = metadata(view);
            if (!metadata.isEmpty()) {
                document.put("metadata", metadata);
            }
            view.literal(DCTERMS + "rights", DC + "rights").ifPresent(rights -> document.put("requiredStatement",
                Map.of("label", Map.of("en", List.of("Rights")), "value", language(rights))));
            view.iris(DCTERMS + "license").stream()
                .filter(license -> license.startsWith("http://creativecommons.org/")
                    || license.startsWith("https://creativecommons.org/")
                    || license.startsWith("http://rightsstatements.org/"))
                .findFirst()
                .ifPresent(license -> document.put("rights", license));
            final Map<String, Object> homepage = new LinkedHashMap<>();
            homepage.put("id", context.uiUrl(view.path()));
            homepage.put("type", "Text");
            homepage.put("label", Map.of("en", List.of("View in Articulorum")));
            homepage.put("format", "text/html");
            document.put("homepage", List.of(homepage));
            document.put("seeAlso", List.of(seeAlso(view)));
            return document;
        }

        private Map<String, Object> seeAlso(final View view) {
            final Map<String, Object> seeAlso = new LinkedHashMap<>();
            seeAlso.put("id", view.iri());
            seeAlso.put("type", "Dataset");
            seeAlso.put("label", Map.of("en", List.of("Linked Data Platform resource")));
            seeAlso.put("format", "text/turtle");
            return seeAlso;
        }

        private Optional<Map<String, Object>> thumbnail(final List<Map<String, Object>> canvases) {
            for (final Map<String, Object> canvas : canvases) {
                final Object pages = canvas.get("items");
                if (pages instanceof List<?> list && !list.isEmpty()
                        && list.get(0) instanceof Map<?, ?> page
                        && page.get("items") instanceof List<?> annotations && !annotations.isEmpty()
                        && annotations.get(0) instanceof Map<?, ?> annotation
                        && annotation.get("body") instanceof Map<?, ?> body
                        && "Image".equals(body.get("type"))) {
                    final Map<String, Object> thumbnail = new LinkedHashMap<>();
                    thumbnail.put("id", body.get("id"));
                    thumbnail.put("type", "Image");
                    if (body.get("format") != null) {
                        thumbnail.put("format", body.get("format"));
                    }
                    return Optional.of(thumbnail);
                }
            }
            return Optional.empty();
        }

    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /**
     * Builds the {@code metadata} entries of a resource: one per descriptive predicate, with every
     * literal value (and IRI values of non-structural predicates), in predicate order.
     *
     * @param view the resource
     * @return the entries
     */
    static List<Map<String, Object>> metadata(final View view) {
        final Map<String, List<String>> values = new LinkedHashMap<>();
        final List<Statement> statements = view.subject().listProperties().toList();
        statements.sort(Comparator.comparing(statement -> statement.getPredicate().getURI()));
        for (final Statement statement : statements) {
            final String predicate = statement.getPredicate().getURI();
            if (NON_DESCRIPTIVE.stream().anyMatch(predicate::startsWith) || SCHEMA_POSITION.equals(predicate)
                    || (DCTERMS + "title").equals(predicate)) {
                continue;
            }
            final RDFNode object = statement.getObject();
            final String value = object.isLiteral() ? object.asLiteral().getLexicalForm()
                : object.isURIResource() ? object.asResource().getURI() : null;
            if (value != null && !value.isBlank()) {
                values.computeIfAbsent(predicate, key -> new ArrayList<>()).add(value);
            }
        }
        final List<Map<String, Object>> metadata = new ArrayList<>();
        values.forEach((predicate, list) -> metadata.add(Map.of(
            "label", Map.of("en", List.of(humanize(localName(predicate)))),
            "value", Map.of("none", List.copyOf(list)))));
        return metadata;
    }

    /**
     * Builds a reference to another IIIF resource.
     *
     * @param id   its identifier
     * @param type its type, for example {@code Canvas}
     * @return {@code {"id": ..., "type": ...}}
     */
    static Map<String, Object> reference(final String id, final String type) {
        final Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("id", id);
        reference.put("type", type);
        return reference;
    }

    /**
     * Wraps a string as a IIIF language map without a language.
     *
     * @param value the string
     * @return {@code {"none": [value]}}
     */
    static Map<String, Object> language(final String value) {
        return Map.of("none", List.of(value));
    }

    /**
     * Turns a local name into a label: {@code pageStart} becomes {@code Page start}.
     *
     * @param name the local name
     * @return the label
     */
    static String humanize(final String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (c == '_' || c == '-') {
                out.append(' ');
            } else if (Character.isUpperCase(c) && i > 0 && !Character.isUpperCase(name.charAt(i - 1))) {
                out.append(' ').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return Character.toUpperCase(out.charAt(0)) + out.substring(1);
    }

    private static String localName(final String iri) {
        final int index = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return index >= 0 && index < iri.length() - 1 ? iri.substring(index + 1) : iri;
    }

    private static String literal(final Resource subject, final String predicate) {
        final Statement statement = subject.getProperty(subject.getModel().createProperty(predicate));
        if (statement == null || !statement.getObject().isLiteral()) {
            return null;
        }
        final Literal literal = statement.getObject().asLiteral();
        return literal.getLexicalForm();
    }

    /**
     * Returns the duration of a time-based canvas: {@code ebucore:duration} or a numeric
     * {@code dcterms:extent} in seconds, else 1 second (IIIF requires a positive duration).
     *
     * @param node the resource
     * @return the duration in seconds
     */
    static double duration(final View node) {
        final Optional<String> value = node.literal(EBUCORE_DURATION, DCTERMS + "extent");
        if (value.isPresent()) {
            try {
                final double seconds = Double.parseDouble(value.get().trim());
                if (seconds > 0) {
                    return seconds;
                }
            } catch (NumberFormatException e) {
                // not a number of seconds
            }
        }
        return 1.0;
    }

}
