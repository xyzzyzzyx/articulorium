package com.articulorum.platform.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import com.articulorum.domain.BinaryContent;
import com.articulorum.domain.InteractionModel;
import com.articulorum.domain.LdpResource;
import com.articulorum.domain.rdf.Iris;
import com.articulorum.domain.rdf.ResourcePaths;
import com.articulorum.domain.vocabulary.Acl;
import com.articulorum.domain.vocabulary.Articulorum;
import com.articulorum.domain.vocabulary.Fedora;
import com.articulorum.domain.vocabulary.Ldp;
import com.articulorum.domain.vocabulary.Memento;
import com.articulorum.platform.config.LdpProperties;
import com.articulorum.platform.ldp.EntityTags;
import com.articulorum.platform.ldp.LdpHeaders;
import com.articulorum.platform.ldp.LinkHeader;
import com.articulorum.platform.ldp.Preference;
import com.articulorum.platform.ldp.exception.BadRequestException;
import com.articulorum.platform.ldp.exception.PayloadTooLargeException;
import com.articulorum.platform.ldp.exception.UnsupportedMediaTypeException;
import com.articulorum.platform.rdf.RdfMediaTypes;
import com.articulorum.platform.notify.NotificationService;
import com.articulorum.platform.service.LdpService;
import com.articulorum.platform.service.Payload;
import com.articulorum.platform.service.PutResult;
import com.articulorum.platform.service.ResolvedTarget;
import com.articulorum.platform.storage.SpooledUpload;
import com.articulorum.platform.storage.StorageProperties;
import com.articulorum.platform.webac.AccessControlService;

import org.apache.jena.rdf.model.Model;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;

/**
 * HTTP binding of the W3C Linked Data Platform 1.0.
 *
 * <p>Every path below the server root addresses an LDP resource. The controller only deals with
 * HTTP concerns: decoding the target path and base IRI, the {@code Link}, {@code Slug},
 * {@code Prefer}, {@code If-Match} and {@code If-None-Match} headers, reading request bodies
 * (RDF, raw binaries or multipart file uploads) and producing the LDP response headers
 * ({@code Link: rel="type"}, {@code ETag}, {@code Allow}, {@code Accept-Post},
 * {@code Accept-Patch}, {@code Location}, {@code Preference-Applied}, ...). The semantics live
 * in {@link LdpService}; errors are rendered by {@link LdpExceptionHandler}.</p>
 *
 * <table>
 *   <caption>Supported operations</caption>
 *   <tr><th>Method</th><th>Effect</th></tr>
 *   <tr><td>GET/HEAD</td><td>RDF representation (content negotiated, Turtle by default), HTML for
 *       browsers, or the bytes of a non-RDF source (with Range support)</td></tr>
 *   <tr><td>OPTIONS</td><td>Allowed methods and accepted formats</td></tr>
 *   <tr><td>POST</td><td>Create a resource in a container: RDF body, raw binary body or
 *       {@code multipart/form-data} upload</td></tr>
 *   <tr><td>PUT</td><td>Replace a resource, or create it at the given path</td></tr>
 *   <tr><td>PATCH</td><td>Apply a SPARQL Update ({@code application/sparql-update})</td></tr>
 *   <tr><td>DELETE</td><td>Delete a resource (recursively for containers)</td></tr>
 * </table>
 *
 * <p>Paths with an {@code fcr:} segment address Fedora API extensions (versions, fixity, search)
 * and are delegated to the {@link ExtensionHandler}.</p>
 */
@Hidden // documented by OpenApiConfiguration: springdoc cannot describe the catch-all mapping
@RestController
public class LdpController {

    /** Every path below the root. */
    static final String ALL = "/**";

    private final LdpService service;

    private final HtmlView htmlView;

    private final LdpProperties ldpProperties;

    private final StorageProperties storageProperties;

    private final ExtensionHandler extensions;

    /**
     * Creates the controller.
     *
     * @param service           the LDP service
     * @param htmlView          the browser view renderer
     * @param ldpProperties     LDP settings
     * @param storageProperties storage settings
     * @param extensions        the Fedora API extension handler
     */
    public LdpController(final LdpService service, final HtmlView htmlView, final LdpProperties ldpProperties,
            final StorageProperties storageProperties, final ExtensionHandler extensions) {
        this.service = service;
        this.htmlView = htmlView;
        this.ldpProperties = ldpProperties;
        this.storageProperties = storageProperties;
        this.extensions = extensions;
    }

    // ------------------------------------------------------------------------------------------
    // Safe methods
    // ------------------------------------------------------------------------------------------

    /**
     * Retrieves a resource (LDP 4.2.2).
     *
     * @param request the request
     * @return the representation
     * @throws IOException when the storage layer fails
     */
    @GetMapping(ALL)
    public ResponseEntity<?> get(final HttpServletRequest request) throws IOException {
        final Optional<ExtensionRequest> extension = ExtensionRequest.parse(RequestTargets.path(request));
        if (extension.isPresent()) {
            return extensions.get(request, extension.get(), false);
        }
        final String base = RequestTargets.base(request);
        final ResolvedTarget target = service.resolve(RequestTargets.path(request));
        final HttpHeaders headers = ldpHeaders(target, base);
        if (EntityTags.matches(request.getHeader(HttpHeaders.IF_NONE_MATCH), EntityTags.of(target.resource()))) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(headers).build();
        }
        if (!target.isRdf()) {
            binaryHeaders(target.resource(), headers);
            if (AcceptPreferences.prefersDescription(request.getHeader(HttpHeaders.ACCEPT), headers.getContentType())) {
                // the client only accepts RDF: point it at the description (RFC 9110 15.4.4)
                return ResponseEntity.status(HttpStatus.SEE_OTHER)
                    .location(URI.create(Iris.resolve(base, target.relativeIri() + "/" + LdpService.DESCRIPTION_SEGMENT)))
                    .header(HttpHeaders.VARY, HttpHeaders.ACCEPT)
                    .build();
            }
            return ResponseEntity.ok().headers(headers).body(new BinaryResource(service, target.resource()));
        }
        final Preference preference = Preference.parse(Collections.list(request.getHeaders(LdpHeaders.PREFER)));
        if (preference.applied()) {
            headers.set(LdpHeaders.PREFERENCE_APPLIED, Preference.APPLIED_HEADER_VALUE);
        }
        final Model model = service.render(target, base, preference);
        if (AcceptPreferences.prefersHtml(request.getHeader(HttpHeaders.ACCEPT))) {
            headers.setContentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8));
            return ResponseEntity.ok().headers(headers).body(htmlView.render(target, model, base));
        }
        return ResponseEntity.ok().headers(headers).body(model);
    }

    /**
     * Retrieves the headers of a resource without its body (LDP 4.2.6).
     *
     * @param request the request
     * @return the headers
     * @throws IOException when the storage layer fails
     */
    @RequestMapping(path = ALL, method = RequestMethod.HEAD)
    public ResponseEntity<?> head(final HttpServletRequest request) throws IOException {
        final Optional<ExtensionRequest> extension = ExtensionRequest.parse(RequestTargets.path(request));
        if (extension.isPresent()) {
            return extensions.get(request, extension.get(), true);
        }
        final String base = RequestTargets.base(request);
        final ResolvedTarget target = service.resolve(RequestTargets.path(request));
        final HttpHeaders headers = ldpHeaders(target, base);
        if (!target.isRdf()) {
            binaryHeaders(target.resource(), headers);
            final Long size = target.resource().getBinary() == null ? null : target.resource().getBinary().getSize();
            if (size != null) {
                headers.setContentLength(size);
            }
        } else {
            headers.setContentType(AcceptPreferences.prefersHtml(request.getHeader(HttpHeaders.ACCEPT))
                ? new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)
                : new MediaType(RdfMediaTypes.TEXT_TURTLE, StandardCharsets.UTF_8));
        }
        if (EntityTags.matches(request.getHeader(HttpHeaders.IF_NONE_MATCH), EntityTags.of(target.resource()))) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(headers).build();
        }
        return ResponseEntity.ok().headers(headers).build();
    }

    /**
     * Describes the operations a resource supports (LDP 4.2.8).
     *
     * @param request the request
     * @return 204 with {@code Allow}, {@code Accept-Post} and {@code Accept-Patch}
     */
    @RequestMapping(path = ALL, method = RequestMethod.OPTIONS)
    public ResponseEntity<Void> options(final HttpServletRequest request) {
        final Optional<ExtensionRequest> extension = ExtensionRequest.parse(RequestTargets.path(request));
        if (extension.isPresent()) {
            return extensions.options(extension.get());
        }
        final ResolvedTarget target = service.resolve(RequestTargets.path(request));
        return ResponseEntity.noContent().headers(ldpHeaders(target, RequestTargets.base(request))).build();
    }

    // ------------------------------------------------------------------------------------------
    // Unsafe methods
    // ------------------------------------------------------------------------------------------

    /**
     * Creates a resource in a container (LDP 5.2.3).
     *
     * <p>The body decides what is created: an RDF document creates an RDF source or container, any
     * other body creates a non-RDF source, and a {@code multipart/form-data} request uploads its
     * {@value HtmlView#FILE_FIELD} part as a non-RDF source (or, with
     * {@code kind=container}, creates an empty container). A {@code Link: rel="type"} header can
     * request a specific interaction model.</p>
     *
     * @param request the request
     * @return 201 Created with {@code Location}, or 303 See Other for browser form submissions
     * @throws IOException when the body cannot be read
     */
    @PostMapping(ALL)
    public ResponseEntity<?> post(final HttpServletRequest request) throws IOException {
        final String base = RequestTargets.base(request);
        final String path = RequestTargets.path(request);
        final Optional<ExtensionRequest> extension = ExtensionRequest.parse(path);
        if (extension.isPresent()) {
            return extensions.post(request, extension.get());
        }
        final InteractionModel requested = requestedModel(request);
        try (RequestBody body = readBody(request, requested, false)) {
            final String slug = Optional.ofNullable(request.getHeader(LdpHeaders.SLUG)).orElse(body.slug());
            final LdpResource created = service.create(path, slug, requested, requestsArchivalGroup(request),
                body.payload(), base);
            final String location = Iris.resolve(base, created.getRelativeIri());
            if (body.fromForm() && AcceptPreferences.prefersHtml(request.getHeader(HttpHeaders.ACCEPT))) {
                return ResponseEntity.status(HttpStatus.SEE_OTHER)
                    .location(URI.create(Iris.resolve(base, ResourcePaths.toRelativeIri(path))))
                    .build();
            }
            final HttpHeaders headers = ldpHeaders(new ResolvedTarget(created, false), base);
            headers.setLocation(URI.create(location));
            headers.setContentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8));
            return new ResponseEntity<>(location, headers, HttpStatus.CREATED);
        }
    }

    /**
     * Replaces a resource, or creates it at the request path (LDP 4.2.4, 5.2.4).
     *
     * @param request the request
     * @return 201 Created when the resource was created, otherwise 204 No Content
     * @throws IOException when the body cannot be read
     */
    @PutMapping(ALL)
    public ResponseEntity<?> put(final HttpServletRequest request) throws IOException {
        final String base = RequestTargets.base(request);
        final String path = RequestTargets.path(request);
        final Optional<ExtensionRequest> extension = ExtensionRequest.parse(path);
        if (extension.isPresent()) {
            return extensions.put(request, extension.get());
        }
        final InteractionModel requested = requestedModel(request);
        final Optional<ResolvedTarget> existing = service.find(path);
        final boolean binaryTarget = existing.map(target -> !target.isRdf()).orElse(false);
        try (RequestBody body = readBody(request, requested, binaryTarget)) {
            final PutResult result = service.put(path, requested, requestsArchivalGroup(request), body.payload(),
                request.getHeader(HttpHeaders.IF_MATCH), request.getHeader(HttpHeaders.IF_NONE_MATCH), base);
            final boolean description = existing.map(ResolvedTarget::description).orElse(false);
            final HttpHeaders headers = ldpHeaders(new ResolvedTarget(result.resource(), description), base);
            if (result.created()) {
                final String location = Iris.resolve(base, result.resource().getRelativeIri());
                headers.setLocation(URI.create(location));
                headers.setContentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8));
                return new ResponseEntity<>(location, headers, HttpStatus.CREATED);
            }
            return ResponseEntity.noContent().headers(headers).build();
        }
    }

    /**
     * Applies a SPARQL Update to an RDF resource.
     *
     * @param request the request
     * @return 204 No Content
     * @throws IOException when the body cannot be read
     */
    @PatchMapping(ALL)
    public ResponseEntity<?> patch(final HttpServletRequest request) throws IOException {
        final String base = RequestTargets.base(request);
        final String path = RequestTargets.path(request);
        final Optional<ExtensionRequest> extension = ExtensionRequest.parse(path);
        if (extension.isPresent()) {
            return extensions.patch(request, extension.get());
        }
        final MediaType contentType = contentType(request);
        if (contentType == null || !contentType.equalsTypeAndSubtype(RdfMediaTypes.APPLICATION_SPARQL_UPDATE)) {
            throw new UnsupportedMediaTypeException("PATCH requires " + RdfMediaTypes.APPLICATION_SPARQL_UPDATE,
                LdpHeaders.ACCEPT_PATCH, RdfMediaTypes.APPLICATION_SPARQL_UPDATE.toString());
        }
        final String update = new String(readLimited(request.getInputStream(), ldpProperties.getMaxRdfSize().toBytes()),
            StandardCharsets.UTF_8);
        final LdpResource patched = service.patch(path, update, request.getHeader(HttpHeaders.IF_MATCH), base);
        final boolean description = !ResourcePaths.normalize(path).equals(patched.getPath());
        return ResponseEntity.noContent().headers(ldpHeaders(new ResolvedTarget(patched, description), base)).build();
    }

    /**
     * Deletes a resource (LDP 4.2.5, 5.2.5).
     *
     * @param request the request
     * @return 204 No Content
     */
    @DeleteMapping(ALL)
    public ResponseEntity<?> delete(final HttpServletRequest request) {
        final Optional<ExtensionRequest> extension = ExtensionRequest.parse(RequestTargets.path(request));
        if (extension.isPresent()) {
            return extensions.delete(extension.get());
        }
        service.delete(RequestTargets.path(request), request.getHeader(HttpHeaders.IF_MATCH),
            RequestTargets.base(request));
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------------------------------
    // Headers
    // ------------------------------------------------------------------------------------------

    /**
     * Builds the LDP headers common to every successful response about a target.
     *
     * @param target the target
     * @param base   the server base
     * @return the headers
     */
    HttpHeaders ldpHeaders(final ResolvedTarget target, final String base) {
        final LdpResource resource = target.resource();
        final HttpHeaders headers = new HttpHeaders();
        final List<String> types = target.description()
            ? List.of(Ldp.RESOURCE, Ldp.RDF_SOURCE)
            : resource.getInteractionModel().getTypeIris();
        for (final String type : types) {
            headers.add(HttpHeaders.LINK, LinkHeader.format(type, LdpHeaders.REL_TYPE));
        }
        if (!target.description() && resource.isArchivalGroup()) {
            headers.add(HttpHeaders.LINK, LinkHeader.format(Fedora.ARCHIVAL_GROUP, LdpHeaders.REL_TYPE));
        } else if (!resource.isRoot()) {
            service.archivalGroupOf(resource).ifPresent(group -> headers.add(HttpHeaders.LINK,
                LinkHeader.format(Iris.resolve(base, group.getRelativeIri()), Articulorum.ARCHIVAL_GROUP_REL)));
        }
        headers.add(HttpHeaders.LINK, LinkHeader.format(Iris.normalizeBase(base) + ConstraintsController.PATH,
            Ldp.CONSTRAINED_BY));
        if (target.description()) {
            headers.add(HttpHeaders.LINK, LinkHeader.format(Iris.resolve(base, resource.getRelativeIri()),
                LdpHeaders.REL_DESCRIBES));
        } else if (resource.isNonRdfSource()) {
            headers.add(HttpHeaders.LINK, LinkHeader.format(
                Iris.resolve(base, resource.getRelativeIri() + "/" + LdpService.DESCRIPTION_SEGMENT),
                LdpHeaders.REL_DESCRIBED_BY));
        }
        if (!target.description()) {
            // Web Access Control: every resource has an ACL resource, whether or not it exists yet
            headers.add(HttpHeaders.LINK, LinkHeader.format(
                AccessControlService.aclIri(base, resource.getPath()), Acl.REL_ACL));
        }
        if (resource.isContainer() && !target.description()) {
            // Linked Data Notifications: containers receive COAR Notify notifications
            headers.add(HttpHeaders.LINK, LinkHeader.format(NotificationService.inboxIri(base, resource.getPath()),
                Ldp.INBOX));
        }
        if (!resource.isRoot()) {
            // Memento (RFC 7089): the resource is its own TimeGate and lists its versions in a TimeMap
            final String original = Iris.resolve(base, resource.getRelativeIri());
            headers.add(HttpHeaders.LINK, LinkHeader.format(Memento.ORIGINAL_RESOURCE, LdpHeaders.REL_TYPE));
            headers.add(HttpHeaders.LINK, LinkHeader.format(original, Memento.REL_TIMEGATE));
            headers.add(HttpHeaders.LINK, LinkHeader.format(ExtensionHandler.timemapIri(base, resource),
                Memento.REL_TIMEMAP));
        }
        headers.setAllow(target.allowedMethods());
        if (resource.isContainer() && !target.description()) {
            headers.set(LdpHeaders.ACCEPT_POST, RdfMediaTypes.readableTypesHeaderValue() + ", */*");
        }
        if (target.isRdf()) {
            headers.set(LdpHeaders.ACCEPT_PATCH, RdfMediaTypes.APPLICATION_SPARQL_UPDATE.toString());
            headers.setVary(List.of(HttpHeaders.ACCEPT, LdpHeaders.PREFER));
        }
        headers.setETag(EntityTags.of(resource));
        if (resource.getModified() != null) {
            headers.setLastModified(resource.getModified().toEpochMilli());
        }
        return headers;
    }

    private static void binaryHeaders(final LdpResource resource, final HttpHeaders headers) {
        final BinaryContent binary = resource.getBinary();
        MediaType contentType = MediaType.APPLICATION_OCTET_STREAM;
        if (binary != null && binary.getContentType() != null) {
            try {
                contentType = MediaType.parseMediaType(binary.getContentType());
            } catch (IllegalArgumentException e) {
                // keep application/octet-stream for unparseable stored types
            }
        }
        headers.setContentType(contentType);
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        if (binary != null && binary.getFilename() != null) {
            headers.setContentDisposition(ContentDisposition.inline()
                .filename(binary.getFilename(), StandardCharsets.UTF_8).build());
        }
        if (binary != null && binary.getSha256() != null) {
            headers.set(LdpHeaders.DIGEST,
                "sha-256=" + Base64.getEncoder().encodeToString(HexFormat.of().parseHex(binary.getSha256())));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Request bodies
    // ------------------------------------------------------------------------------------------

    /**
     * A decoded request body. Closing it deletes any spooled upload.
     *
     * @param payload  the payload for the service
     * @param slug     a name suggested by the body (form field or file name), may be {@code null}
     * @param fromForm whether the body was a multipart form submission
     */
    record RequestBody(Payload payload, String slug, boolean fromForm) implements AutoCloseable {

        @Override
        public void close() throws IOException {
            if (payload instanceof Payload.Binary binary) {
                binary.upload().close();
            }
        }

    }

    /**
     * Reads the request body into a payload.
     *
     * @param request      the request
     * @param requested    the requested interaction model, may be {@code null}
     * @param binaryTarget whether the target is an existing non-RDF source (its body is always
     *                     binary, whatever its media type)
     * @return the body
     * @throws IOException when the body cannot be read
     */
    RequestBody readBody(final HttpServletRequest request, final InteractionModel requested,
            final boolean binaryTarget) throws IOException {
        if (request instanceof MultipartHttpServletRequest multipart) {
            return readMultipart(multipart);
        }
        final MediaType contentType = contentType(request);
        final boolean hasBody = request.getContentLengthLong() > 0 || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null;
        final boolean binary = binaryTarget
            || requested == InteractionModel.NON_RDF_SOURCE
            || (contentType != null && !RdfMediaTypes.isRdf(contentType))
            || (contentType == null && hasBody);
        if (binary) {
            final SpooledUpload upload = SpooledUpload.spool(request.getInputStream(),
                storageProperties.getMaxUploadSize().toBytes(), storageProperties.getTempDirectory());
            final String filename = filename(request.getHeader(HttpHeaders.CONTENT_DISPOSITION));
            final String type = contentType == null
                ? MediaTypeFactory.getMediaType(filename).orElse(MediaType.APPLICATION_OCTET_STREAM).toString()
                : contentType.toString();
            return new RequestBody(new Payload.Binary(upload, type, filename), filename, false);
        }
        final byte[] content = readLimited(request.getInputStream(), ldpProperties.getMaxRdfSize().toBytes());
        if (content.length == 0 || contentType == null) {
            return new RequestBody(new Payload.Empty(), null, false);
        }
        return new RequestBody(new Payload.Rdf(content, RdfMediaTypes.readableLang(contentType).orElseThrow()), null, false);
    }

    private RequestBody readMultipart(final MultipartHttpServletRequest multipart) throws IOException {
        final String slug = multipart.getParameter(HtmlView.SLUG_FIELD);
        if (HtmlView.KIND_CONTAINER.equals(multipart.getParameter(HtmlView.KIND_FIELD))) {
            return new RequestBody(new Payload.Empty(), slug, true);
        }
        MultipartFile file = multipart.getFile(HtmlView.FILE_FIELD);
        if (file == null && !multipart.getFileMap().isEmpty()) {
            file = multipart.getFileMap().values().iterator().next();
        }
        if (file == null) {
            throw new BadRequestException("Multipart request without a '" + HtmlView.FILE_FIELD + "' part", null);
        }
        final String filename = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
            ? null
            : file.getOriginalFilename();
        final String type = file.getContentType() != null && !file.getContentType().isBlank()
            ? file.getContentType()
            : MediaTypeFactory.getMediaType(filename).orElse(MediaType.APPLICATION_OCTET_STREAM).toString();
        try (InputStream in = file.getInputStream()) {
            final SpooledUpload upload = SpooledUpload.spool(in, storageProperties.getMaxUploadSize().toBytes(),
                storageProperties.getTempDirectory());
            return new RequestBody(new Payload.Binary(upload, type, filename),
                slug != null && !slug.isBlank() ? slug : filename, true);
        }
    }

    /**
     * Reads the interaction model requested with {@code Link: <...>; rel="type"}.
     *
     * <p>Clients often send several types at once (for example {@code ldp:Container} and
     * {@code ldp:BasicContainer}, or {@code ldp:RDFSource} and {@code ldp:DirectContainer}); the
     * most specific one wins. {@code ldp:Resource} and non-LDP types are ignored.</p>
     *
     * @param request the request
     * @return the most specific requested model, or {@code null} when none was requested
     */
    static InteractionModel requestedModel(final HttpServletRequest request) {
        InteractionModel requested = null;
        for (final String type : LinkHeader.targets(Collections.list(request.getHeaders(HttpHeaders.LINK)),
                LdpHeaders.REL_TYPE)) {
            final Optional<InteractionModel> model = InteractionModel.fromIri(type);
            if (model.isPresent() && (requested == null || rank(model.get()) > rank(requested))) {
                requested = model.get();
            }
        }
        return requested;
    }

    /**
     * Tells whether a request asks for an archival group with
     * {@code Link: <http://fedora.info/definitions/v4/repository#ArchivalGroup>; rel="type"}.
     *
     * @param request the request
     * @return {@code true} when an archival group is requested
     */
    static boolean requestsArchivalGroup(final HttpServletRequest request) {
        return LinkHeader.targets(Collections.list(request.getHeaders(HttpHeaders.LINK)), LdpHeaders.REL_TYPE)
            .contains(Fedora.ARCHIVAL_GROUP);
    }

    /** Specificity of an interaction model when several are requested. */
    private static int rank(final InteractionModel model) {
        return switch (model) {
            case RDF_SOURCE -> 0;
            case BASIC_CONTAINER -> 1;
            case DIRECT_CONTAINER, INDIRECT_CONTAINER -> 2;
            case NON_RDF_SOURCE -> 3;
        };
    }

    private static MediaType contentType(final HttpServletRequest request) {
        final String header = request.getContentType();
        return header == null || header.isBlank() ? null : MediaType.parseMediaType(header);
    }

    private static String filename(final String contentDisposition) {
        if (contentDisposition == null || contentDisposition.isBlank()) {
            return null;
        }
        try {
            return ContentDisposition.parse(contentDisposition).getFilename();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Reads a stream fully, failing once it exceeds a limit.
     *
     * @param in       the stream
     * @param maxBytes the limit
     * @return the bytes
     * @throws IOException              when the stream fails
     * @throws PayloadTooLargeException when the limit is exceeded
     */
    static byte[] readLimited(final InputStream in, final long maxBytes) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

}
