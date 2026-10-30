package com.articulorum.platform.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.articulorum.domain.LdpResource;
import com.articulorum.domain.Triple;
import com.articulorum.domain.rdf.Iris;
import com.articulorum.domain.vocabulary.Memento;
import com.articulorum.domain.vocabulary.Rdf;
import com.articulorum.platform.ldp.LinkHeader;
import com.articulorum.platform.ldp.exception.BadRequestException;
import com.articulorum.platform.ldp.exception.MethodNotAllowedException;
import com.articulorum.platform.namespace.NamespaceRegistry;
import com.articulorum.platform.rdf.TripleMapper;
import com.articulorum.platform.search.SearchCondition;
import com.articulorum.platform.search.SearchField;
import com.articulorum.platform.search.SearchService;
import com.articulorum.platform.service.HistoryService;
import com.articulorum.platform.service.ResolvedTarget;
import com.articulorum.platform.storage.StorageKeys;
import com.articulorum.platform.storage.StoredVersion;

import org.apache.jena.rdf.model.Model;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Serves the Fedora API extensions recognised by {@link ExtensionRequest}: versions (Memento
 * TimeMaps and mementos), fixity checks and search.
 *
 * <p>Version lists, fixity reports and search results are JSON documents (the version list is
 * also available as an RFC 6690 link-format TimeMap). Mementos are content negotiated like the
 * resources they belong to: RDF (or HTML for browsers) for RDF sources and descriptions, the
 * stored bytes for non-RDF sources.</p>
 */
@Component
public class ExtensionHandler {

    /** RFC 1123 formatter for {@code Memento-Datetime}. */
    static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private final HistoryService history;

    private final SearchService search;

    private final NamespaceRegistry namespaces;

    private final HtmlView htmlView;

    /**
     * One entry of a version list.
     *
     * @param id         the version identifier
     * @param uri        the memento IRI
     * @param created    when the version was created
     * @param agent      who created it
     * @param message    why it was created
     * @param hasContent whether the version holds bytes (versions of non-RDF sources)
     */
    public record VersionEntry(String id, String uri, Instant created, String agent, String message,
            boolean hasContent) {
    }

    /**
     * The version list of a resource.
     *
     * @param original the IRI of the original resource
     * @param timemap  the IRI of this list
     * @param versions the versions, oldest first
     */
    public record VersionList(String original, String timemap, List<VersionEntry> versions) {
    }

    /**
     * Creates the handler.
     *
     * @param history    the versioning and fixity service
     * @param search     the search service
     * @param namespaces namespace prefixes for rendered mementos
     * @param htmlView   the browser view renderer
     */
    public ExtensionHandler(final HistoryService history, final SearchService search,
            final NamespaceRegistry namespaces, final HtmlView htmlView) {
        this.history = history;
        this.search = search;
        this.namespaces = namespaces;
        this.htmlView = htmlView;
    }

    /**
     * Serves GET (and HEAD) requests.
     *
     * @param request   the request
     * @param extension the extension request
     * @param head      whether the body must be omitted
     * @return the response
     * @throws IOException when the storage layer fails
     */
    public ResponseEntity<?> get(final HttpServletRequest request, final ExtensionRequest extension, final boolean head)
            throws IOException {
        final String base = RequestTargets.base(request);
        final ResponseEntity<?> response = switch (extension.extension()) {
            case VERSIONS -> versions(request, extension, base);
            case MEMENTO -> memento(request, extension, base, head);
            case FIXITY -> ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(history.fixity(extension.resourcePath()));
            case SEARCH -> ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(search.search(searchRequest(request), base));
        };
        if (head) {
            return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).build();
        }
        return response;
    }

    /**
     * Serves POST requests: {@code POST <resource>/fcr:versions} creates a version.
     *
     * @param request   the request
     * @param extension the extension request
     * @return 201 Created with the memento's IRI as {@code Location}
     */
    public ResponseEntity<String> post(final HttpServletRequest request, final ExtensionRequest extension) {
        if (extension.extension() != ExtensionRequest.Extension.VERSIONS) {
            throw notAllowed(extension);
        }
        final String base = RequestTargets.base(request);
        final StoredVersion version = history.createVersion(extension.resourcePath());
        final String location = mementoIri(base, history.resolve(extension.resourcePath()), version.versionId());
        final HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(location));
        headers.setContentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8));
        return new ResponseEntity<>(location, headers, HttpStatus.CREATED);
    }

    /**
     * Serves OPTIONS requests.
     *
     * @param extension the extension request
     * @return 204 with {@code Allow}
     */
    public ResponseEntity<Void> options(final ExtensionRequest extension) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setAllow(allowed(extension));
        return ResponseEntity.noContent().headers(headers).build();
    }

    /**
     * Builds the error for a method the extension does not support.
     *
     * @param extension the extension request
     * @return 405 Method Not Allowed
     */
    public MethodNotAllowedException notAllowed(final ExtensionRequest extension) {
        return new MethodNotAllowedException("Method not supported by " + extension.extension(), allowed(extension));
    }

    static Set<HttpMethod> allowed(final ExtensionRequest extension) {
        final Set<HttpMethod> methods = new LinkedHashSet<>(List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS));
        if (extension.extension() == ExtensionRequest.Extension.VERSIONS) {
            methods.add(HttpMethod.POST);
        }
        return methods;
    }

    // ------------------------------------------------------------------------------------------
    // Versions
    // ------------------------------------------------------------------------------------------

    private ResponseEntity<?> versions(final HttpServletRequest request, final ExtensionRequest extension,
            final String base) {
        final LdpResource resource = history.resolve(extension.resourcePath());
        final String original = Iris.resolve(base, resource.getRelativeIri());
        final String timemap = timemapIri(base, resource);
        final List<VersionEntry> entries = new ArrayList<>();
        for (final StoredVersion version : history.versions(extension.resourcePath())) {
            entries.add(new VersionEntry(version.versionId(), mementoIri(base, resource, version.versionId()),
                version.created(), version.agent(), version.message(), version.contains(StorageKeys.CONTENT)));
        }
        final HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LINK, LinkHeader.format(original, Memento.REL_ORIGINAL + " " + Memento.REL_TIMEGATE));
        headers.add(HttpHeaders.LINK, LinkHeader.format(timemap, "self"));
        headers.setAllow(allowed(extension));
        headers.setVary(List.of(HttpHeaders.ACCEPT));
        final String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept != null && accept.contains(Memento.LINK_FORMAT)) {
            headers.setContentType(MediaType.parseMediaType(Memento.LINK_FORMAT + ";charset=UTF-8"));
            return ResponseEntity.ok().headers(headers).body(linkFormat(original, timemap, entries));
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return ResponseEntity.ok().headers(headers).body(new VersionList(original, timemap, entries));
    }

    /**
     * Renders a TimeMap in RFC 6690 link format.
     *
     * @param original the original resource
     * @param timemap  the TimeMap IRI
     * @param entries  the versions
     * @return the link-format document
     */
    static String linkFormat(final String original, final String timemap, final List<VersionEntry> entries) {
        final StringBuilder out = new StringBuilder();
        out.append('<').append(original).append(">;rel=\"original timegate\",\n");
        out.append('<').append(timemap).append(">;rel=\"self\";type=\"").append(Memento.LINK_FORMAT).append('"');
        for (final VersionEntry entry : entries) {
            out.append(",\n<").append(entry.uri()).append(">;rel=\"memento\"");
            if (entry.created() != null) {
                out.append(";datetime=\"").append(HTTP_DATE.format(entry.created())).append('"');
            }
        }
        return out.append('\n').toString();
    }

    private ResponseEntity<?> memento(final HttpServletRequest request, final ExtensionRequest extension,
            final String base, final boolean head) throws IOException {
        final HistoryService.Memento memento = history.memento(extension.resourcePath(), extension.versionId());
        final LdpResource resource = memento.resource();
        final HttpHeaders headers = new HttpHeaders();
        final String original = Iris.resolve(base, resource.getRelativeIri());
        headers.add(HttpHeaders.LINK, LinkHeader.format(original, Memento.REL_ORIGINAL + " " + Memento.REL_TIMEGATE));
        headers.add(HttpHeaders.LINK, LinkHeader.format(timemapIri(base, resource), Memento.REL_TIMEMAP));
        headers.add(HttpHeaders.LINK, LinkHeader.format(Memento.MEMENTO, "type"));
        if (memento.version().created() != null) {
            headers.set(Memento.MEMENTO_DATETIME_HEADER, HTTP_DATE.format(memento.version().created()));
        }
        headers.setAllow(Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS));
        final String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (memento.hasContent() && !AcceptPreferences.prefersDescription(accept, binaryType(resource))) {
            headers.setContentType(binaryType(resource));
            if (head) {
                return ResponseEntity.ok().headers(headers).build();
            }
            final InputStream content = history.openContent(memento);
            return ResponseEntity.ok().headers(headers).body(new InputStreamResource(content));
        }
        final Model model = mementoModel(memento, base);
        if (AcceptPreferences.prefersHtml(accept)) {
            headers.setContentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8));
            return ResponseEntity.ok().headers(headers).body(htmlView.render(new ResolvedTarget(resource, false), model,
                base));
        }
        return ResponseEntity.ok().headers(headers).body(model);
    }

    /**
     * Builds the RDF representation of a memento: the client-managed triples at that version, the
     * resource's LDP types and the memento's own type and datetime.
     *
     * @param memento the memento
     * @param base    the server base
     * @return the model
     */
    Model mementoModel(final HistoryService.Memento memento, final String base) {
        final LdpResource resource = memento.resource();
        final String subject = resource.getRelativeIri();
        final List<Triple> triples = new ArrayList<>(memento.triples());
        for (final String type : resource.getInteractionModel().getTypeIris()) {
            triples.add(Triple.iri(subject, Rdf.TYPE, type));
        }
        triples.add(Triple.iri(subject, Rdf.TYPE, Memento.MEMENTO));
        if (memento.version().created() != null) {
            triples.add(Triple.literal(subject, Memento.MEMENTO_DATETIME, memento.version().created().toString(),
                Rdf.XSD_NAMESPACE + "dateTime", null));
        }
        final Model model = TripleMapper.toModel(triples, base);
        namespaces.applyTo(model);
        model.setNsPrefix("memento", Memento.NAMESPACE);
        return model;
    }

    private static MediaType binaryType(final LdpResource resource) {
        if (resource.getBinary() != null && resource.getBinary().getContentType() != null) {
            try {
                return MediaType.parseMediaType(resource.getBinary().getContentType());
            } catch (IllegalArgumentException e) {
                // fall through
            }
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /**
     * Returns the TimeMap IRI of a resource.
     *
     * @param base     the server base
     * @param resource the resource
     * @return {@code <resource>/fcr:versions}
     */
    static String timemapIri(final String base, final LdpResource resource) {
        final String iri = Iris.resolve(base, resource.getRelativeIri());
        return (iri.endsWith("/") ? iri : iri + "/") + ExtensionRequest.VERSIONS_SEGMENT;
    }

    /**
     * Returns the IRI of a memento.
     *
     * @param base      the server base
     * @param resource  the resource
     * @param versionId the version identifier
     * @return {@code <resource>/fcr:versions/<versionId>}
     */
    static String mementoIri(final String base, final LdpResource resource, final String versionId) {
        return timemapIri(base, resource) + "/" + versionId;
    }

    // ------------------------------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------------------------------

    /**
     * Reads the search parameters of a request: repeated {@code condition}s, {@code max_results},
     * {@code offset}, {@code order_by} and {@code order} ({@code asc} or {@code desc}).
     *
     * @param request the request
     * @return the search request
     * @throws BadRequestException when a parameter is invalid
     */
    static SearchService.SearchRequest searchRequest(final HttpServletRequest request) {
        final List<SearchCondition> conditions = new ArrayList<>();
        final String[] values = request.getParameterValues("condition");
        if (values != null) {
            for (final String value : values) {
                if (value != null && !value.isBlank()) {
                    conditions.add(SearchCondition.parse(value));
                }
            }
        }
        final int maxResults = intParameter(request, "max_results", SearchService.DEFAULT_MAX_RESULTS);
        final int offset = intParameter(request, "offset", 0);
        final String orderByName = request.getParameter("order_by");
        final SearchField orderBy = orderByName == null || orderByName.isBlank()
            ? null
            : SearchField.fromParameterName(orderByName)
                .orElseThrow(() -> new BadRequestException("Unknown order_by field: " + orderByName, null));
        final String order = request.getParameter("order");
        if (order != null && !order.isBlank() && !"asc".equalsIgnoreCase(order) && !"desc".equalsIgnoreCase(order)) {
            throw new BadRequestException("order must be asc or desc", null);
        }
        return new SearchService.SearchRequest(conditions, maxResults, offset, orderBy, !"desc".equalsIgnoreCase(order));
    }

    private static int intParameter(final HttpServletRequest request, final String name, final int defaultValue) {
        final String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException(name + " must be a number", e);
        }
    }

}
