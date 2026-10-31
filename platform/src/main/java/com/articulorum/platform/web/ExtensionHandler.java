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
import com.articulorum.domain.rdf.ResourcePaths;
import com.articulorum.domain.vocabulary.Acl;
import com.articulorum.domain.vocabulary.Ldp;
import com.articulorum.domain.vocabulary.Memento;
import com.articulorum.domain.vocabulary.Rdf;
import com.articulorum.platform.config.LdpProperties;
import com.articulorum.platform.ldp.LdpHeaders;
import com.articulorum.platform.ldp.LinkHeader;
import com.articulorum.platform.ldp.exception.BadRequestException;
import com.articulorum.platform.ldp.exception.MethodNotAllowedException;
import com.articulorum.platform.ldp.exception.ResourceNotFoundException;
import com.articulorum.platform.ldp.exception.UnsupportedMediaTypeException;
import com.articulorum.platform.namespace.NamespaceRegistry;
import com.articulorum.platform.notify.CoarNotify;
import com.articulorum.platform.notify.NotificationEntity;
import com.articulorum.platform.notify.NotificationService;
import com.articulorum.platform.rdf.RdfMediaTypes;
import com.articulorum.platform.rdf.TripleMapper;
import com.articulorum.platform.search.SearchCondition;
import com.articulorum.platform.search.SearchField;
import com.articulorum.platform.search.SearchService;
import com.articulorum.platform.service.CurrentAgent;
import com.articulorum.platform.service.HistoryService;
import com.articulorum.platform.service.ResolvedTarget;
import com.articulorum.platform.storage.StoredVersion;
import com.articulorum.platform.webac.AccessControlService;
import com.articulorum.platform.webac.Agent;
import com.articulorum.platform.webac.WebAcFilter;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Serves the API extensions recognised by {@link ExtensionRequest}: versions (Memento TimeMaps and
 * mementos), fixity checks, search, WebAC access control lists and COAR Notify inboxes and
 * outboxes.
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

    private final AccessControlService access;

    private final NotificationService notifications;

    private final LdpProperties ldpProperties;

    private final CurrentAgent agent;

    /**
     * Summary of a notification in an inbox or outbox listing.
     *
     * @param id             the local identifier
     * @param uri            the notification's IRI in this inbox (sent notifications: {@code null})
     * @param notificationId the notification's own {@code id}
     * @param types          its types
     * @param pattern        the COAR Notify pattern, for example {@code Announce Review}
     * @param origin         the origin service
     * @param target         the target service
     * @param object         the object
     * @param actor          the actor
     * @param status         RECEIVED, SENT or FAILED
     * @param statusDetail   why delivery failed
     * @param agent          who posted or sent it
     * @param received       when it was received or sent
     */
    public record NotificationSummary(String id, String uri, String notificationId, List<String> types,
            String pattern, String origin, String target, String object, String actor, String status,
            String statusDetail, String agent, Instant received) {
    }

    /**
     * An inbox or outbox listing.
     *
     * @param resource      the IRI of the container
     * @param inbox         the IRI of its inbox
     * @param notifications the notifications, newest first
     */
    public record NotificationList(String resource, String inbox, List<NotificationSummary> notifications) {
    }

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
     * @param htmlView      the browser view renderer
     * @param access        Web Access Control
     * @param notifications COAR Notify inboxes and outboxes
     * @param ldpProperties LDP settings (body size limits)
     * @param agent         determines the agent making changes
     */
    public ExtensionHandler(final HistoryService history, final SearchService search,
            final NamespaceRegistry namespaces, final HtmlView htmlView, final AccessControlService access,
            final NotificationService notifications, final LdpProperties ldpProperties, final CurrentAgent agent) {
        this.history = history;
        this.search = search;
        this.namespaces = namespaces;
        this.htmlView = htmlView;
        this.access = access;
        this.notifications = notifications;
        this.ldpProperties = ldpProperties;
        this.agent = agent;
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
                .body(readable(request, search.search(searchRequest(request), base), base));
            case ACL -> acl(extension, base);
            case INBOX -> inbox(request, extension, base, NotificationEntity.Direction.INBOUND);
            case OUTBOX -> inbox(request, extension, base, NotificationEntity.Direction.OUTBOUND);
            case NOTIFICATION -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(CoarNotify.JSON_LD))
                .body(notifications.get(extension.resourcePath(), extension.versionId()).getContent());
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
    public ResponseEntity<?> post(final HttpServletRequest request, final ExtensionRequest extension)
            throws IOException {
        final String base = RequestTargets.base(request);
        if (extension.extension() == ExtensionRequest.Extension.INBOX) {
            final NotificationEntity received = notifications.receive(extension.resourcePath(), body(request),
                agent.name());
            final String location = NotificationService.inboxIri(base, extension.resourcePath()) + "/" + received.getId();
            return ResponseEntity.created(URI.create(location)).build();
        }
        if (extension.extension() == ExtensionRequest.Extension.OUTBOX) {
            final NotificationEntity sent = notifications.send(extension.resourcePath(), body(request), base,
                agent.name());
            return ResponseEntity.status(sent.getStatus() == NotificationEntity.Status.SENT
                    ? HttpStatus.CREATED : HttpStatus.BAD_GATEWAY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(summary(sent, base));
        }
        if (extension.extension() != ExtensionRequest.Extension.VERSIONS) {
            throw notAllowed(extension);
        }
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
        switch (extension.extension()) {
            case VERSIONS, INBOX, OUTBOX -> methods.add(HttpMethod.POST);
            case ACL -> methods.addAll(List.of(HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE));
            default -> {
                // read-only
            }
        }
        return methods;
    }

    /**
     * Removes the search hits the requesting agent may not read. Pagination figures still count
     * every match.
     *
     * @param request the request (carrying the agent resolved by the WebAC filter)
     * @param result  the unfiltered result
     * @param base    the server base
     * @return the result without unreadable hits
     */
    SearchService.SearchResult readable(final HttpServletRequest request, final SearchService.SearchResult result,
            final String base) {
        if (!(request.getAttribute(WebAcFilter.AGENT_ATTRIBUTE) instanceof Agent requester)) {
            return result;
        }
        final String normalizedBase = Iris.normalizeBase(base);
        final List<java.util.Map<String, Object>> items = result.items().stream()
            .filter(item -> {
                final Object id = item.get(SearchField.FEDORA_ID.parameterName());
                if (!(id instanceof String iri) || !iri.startsWith(normalizedBase)) {
                    return true;
                }
                return access.isAllowed(requester, iri.substring(normalizedBase.length()), Acl.READ);
            })
            .toList();
        return new SearchService.SearchResult(items, result.pagination());
    }

    // ------------------------------------------------------------------------------------------
    // Access control lists
    // ------------------------------------------------------------------------------------------

    private ResponseEntity<?> acl(final ExtensionRequest extension, final String base) {
        final List<Triple> triples = access.find(extension.resourcePath())
            .orElseThrow(() -> new ResourceNotFoundException(extension.resourcePath() + "/fcr:acl"));
        final Model model = TripleMapper.toModel(triples, base);
        namespaces.applyTo(model);
        final HttpHeaders headers = new HttpHeaders();
        headers.setAllow(allowed(extension));
        headers.set(LdpHeaders.ACCEPT_PATCH, RdfMediaTypes.APPLICATION_SPARQL_UPDATE.toString());
        headers.setVary(List.of(HttpHeaders.ACCEPT));
        return ResponseEntity.ok().headers(headers).body(model);
    }

    /**
     * Serves PUT requests: {@code PUT <resource>/fcr:acl} creates or replaces an access control
     * list.
     *
     * @param request   the request
     * @param extension the extension request
     * @return 201 Created or 204 No Content
     * @throws IOException when the body cannot be read
     */
    public ResponseEntity<?> put(final HttpServletRequest request, final ExtensionRequest extension)
            throws IOException {
        if (extension.extension() != ExtensionRequest.Extension.ACL) {
            throw notAllowed(extension);
        }
        final String base = RequestTargets.base(request);
        final MediaType contentType = request.getContentType() == null
            ? null : MediaType.parseMediaType(request.getContentType());
        final Lang lang = contentType == null ? null : RdfMediaTypes.readableLang(contentType).orElse(null);
        if (lang == null) {
            throw new UnsupportedMediaTypeException("An access control list must be RDF", LdpHeaders.ACCEPT_POST,
                RdfMediaTypes.readableTypesHeaderValue());
        }
        final List<Triple> triples = AccessControlService.parse(body(request), lang, base, extension.resourcePath());
        final boolean created = access.put(extension.resourcePath(), triples, agent.name());
        if (created) {
            final String location = AccessControlService.aclIri(base, access.targetPath(extension.resourcePath()));
            return ResponseEntity.created(URI.create(location)).build();
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Serves PATCH requests: {@code PATCH <resource>/fcr:acl} applies a SPARQL Update to an access
     * control list.
     *
     * @param request   the request
     * @param extension the extension request
     * @return 204 No Content
     * @throws IOException when the body cannot be read
     */
    public ResponseEntity<?> patch(final HttpServletRequest request, final ExtensionRequest extension)
            throws IOException {
        if (extension.extension() != ExtensionRequest.Extension.ACL) {
            throw notAllowed(extension);
        }
        final String contentType = request.getContentType();
        if (contentType == null || !MediaType.parseMediaType(contentType)
                .equalsTypeAndSubtype(RdfMediaTypes.APPLICATION_SPARQL_UPDATE)) {
            throw new UnsupportedMediaTypeException("PATCH requires " + RdfMediaTypes.APPLICATION_SPARQL_UPDATE,
                LdpHeaders.ACCEPT_PATCH, RdfMediaTypes.APPLICATION_SPARQL_UPDATE.toString());
        }
        access.patch(extension.resourcePath(), new String(body(request), StandardCharsets.UTF_8),
            RequestTargets.base(request), agent.name());
        return ResponseEntity.noContent().build();
    }

    /**
     * Serves DELETE requests: {@code DELETE <resource>/fcr:acl} removes an access control list.
     *
     * @param extension the extension request
     * @return 204 No Content
     */
    public ResponseEntity<?> delete(final ExtensionRequest extension) {
        if (extension.extension() != ExtensionRequest.Extension.ACL) {
            throw notAllowed(extension);
        }
        access.delete(extension.resourcePath());
        return ResponseEntity.noContent().build();
    }

    private byte[] body(final HttpServletRequest request) throws IOException {
        return LdpController.readLimited(request.getInputStream(), ldpProperties.getMaxRdfSize().toBytes());
    }

    // ------------------------------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------------------------------

    private ResponseEntity<?> inbox(final HttpServletRequest request, final ExtensionRequest extension,
            final String base, final NotificationEntity.Direction direction) {
        final List<NotificationEntity> found = notifications.list(extension.resourcePath(), direction);
        final String resource = Iris.resolve(base, ResourcePaths.toRelativeIri(extension.resourcePath()));
        final String inbox = NotificationService.inboxIri(base, extension.resourcePath());
        final HttpHeaders headers = new HttpHeaders();
        headers.setAllow(allowed(extension));
        headers.setVary(List.of(HttpHeaders.ACCEPT));
        headers.set(LdpHeaders.ACCEPT_POST, CoarNotify.JSON_LD);
        final String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (direction == NotificationEntity.Direction.OUTBOUND || accept == null
                || (accept.contains(MediaType.APPLICATION_JSON_VALUE) && !accept.contains(CoarNotify.JSON_LD))) {
            headers.setContentType(MediaType.APPLICATION_JSON);
            return ResponseEntity.ok().headers(headers).body(new NotificationList(resource, inbox,
                found.stream().map(entity -> summary(entity, base)).toList()));
        }
        // LDN: the inbox is a container listing its notifications with ldp:contains
        final Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        final org.apache.jena.rdf.model.Resource subject = model.createResource(inbox);
        subject.addProperty(org.apache.jena.vocabulary.RDF.type, model.createResource(Ldp.CONTAINER));
        for (final NotificationEntity entity : found) {
            subject.addProperty(model.createProperty(Ldp.CONTAINS), model.createResource(inbox + "/" + entity.getId()));
        }
        namespaces.applyTo(model);
        return ResponseEntity.ok().headers(headers).body(model);
    }

    private static NotificationSummary summary(final NotificationEntity entity, final String base) {
        final String uri = entity.getDirection() == NotificationEntity.Direction.INBOUND
            ? NotificationService.inboxIri(base, entity.getPath()) + "/" + entity.getId()
            : null;
        return new NotificationSummary(entity.getId().toString(), uri, entity.getNotificationId(),
            entity.getTypes() == null || entity.getTypes().isBlank() ? List.of() : List.of(entity.getTypes().split(" ")),
            entity.getPattern(), entity.getOrigin(), entity.getTarget(), entity.getObject(), entity.getActor(),
            entity.getStatus().name(), entity.getStatusDetail(), entity.getAgent(), entity.getReceived());
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
        final String contentPath = history.contentPathOf(resource);
        for (final StoredVersion version : history.versions(extension.resourcePath())) {
            entries.add(new VersionEntry(version.versionId(), mementoIri(base, resource, version.versionId()),
                version.created(), version.agent(), version.message(), version.contains(contentPath)));
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
        final MediaType historicType = mementoType(memento);
        if (memento.hasContent() && !AcceptPreferences.prefersDescription(accept, historicType)) {
            headers.setContentType(historicType);
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
     * Returns the media type of a memento's bytes: the type recorded with that version, or the
     * binary's current type for versions written before technical metadata was recorded.
     *
     * @param memento the memento
     * @return the media type
     */
    static MediaType mementoType(final HistoryService.Memento memento) {
        final String recorded = memento.contentType();
        if (recorded != null) {
            try {
                return MediaType.parseMediaType(recorded);
            } catch (IllegalArgumentException e) {
                // fall back to the current type
            }
        }
        return binaryType(memento.resource());
    }

    /**
     * Builds the RDF representation of a memento: the client-managed triples at that version, the
     * technical metadata a binary had at that version (size, digest, media type, file name), the
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
        triples.addAll(memento.technical());
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
