package com.articulorum.transform.iiif;

import java.net.URI;
import java.util.Map;

import com.articulorum.domain.rdf.ResourcePaths;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Serves IIIF Presentation 3.0 exports: {@code GET /iiif/<resource path>} returns a Manifest for a
 * PCDM object and a Collection for PCDM collections and communities.
 *
 * <p>Documents are publicly readable from any origin ({@code Access-Control-Allow-Origin: *}) so
 * IIIF viewers hosted elsewhere can load them; the caller's {@code Authorization} header is passed
 * on to the platform, whose access control decides what can be exported.</p>
 */
@RestController
@Tag(name = "IIIF", description = "IIIF Presentation 3.0 exports of PCDM structures")
public class IiifController {

    /** Path prefix of the exports. */
    public static final String PREFIX = "/iiif/";

    private final IiifPresentationService presentation;

    private final IiifProperties properties;

    /**
     * Creates the controller.
     *
     * @param presentation builds the documents
     * @param properties   IIIF settings
     */
    public IiifController(final IiifPresentationService presentation, final IiifProperties properties) {
        this.presentation = presentation;
        this.properties = properties;
    }

    /**
     * Exports a resource.
     *
     * @param request the request; everything after {@code /iiif/} is the resource path
     * @return the Manifest or Collection
     */
    @GetMapping(path = "/iiif/**")
    @Operation(operationId = "iiifExport", summary = "IIIF Manifest of a PCDM object or Collection of a collection")
    public ResponseEntity<Map<String, Object>> export(final HttpServletRequest request) {
        final PublicContext context = context(request, properties);
        final String path = pathOf(request);
        return ResponseEntity.ok()
            .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
            .contentType(MediaType.parseMediaType(IiifPresentationService.MEDIA_TYPE))
            .body(presentation.export(path, context));
    }

    /**
     * Answers export failures with a problem document.
     *
     * @param e the failure
     * @return the problem, with the failure's status
     */
    @ExceptionHandler(IiifException.class)
    public ResponseEntity<ProblemDetail> failed(final IiifException e) {
        final HttpStatus status = HttpStatus.resolve(e.getStatus());
        return ResponseEntity.status(status == null ? HttpStatus.BAD_GATEWAY : status)
            .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
            .body(ProblemDetail.forStatusAndDetail(status == null ? HttpStatus.BAD_GATEWAY : status, e.getMessage()));
    }

    /**
     * Extracts the resource path of an export request.
     *
     * @param request the request
     * @return the normalised path after {@code /iiif/}
     */
    static String pathOf(final HttpServletRequest request) {
        String uri = request.getRequestURI();
        final String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        final int index = uri.indexOf(PREFIX);
        return index < 0 ? "" : ResourcePaths.normalize(uri.substring(index + PREFIX.length()));
    }

    /**
     * Determines how the caller sees the system, from the request's public URL (the gateway's
     * {@code X-Forwarded-*} headers are honoured) and the configured relative locations.
     *
     * @param request    the request
     * @param properties IIIF settings
     * @return the caller's view
     */
    static PublicContext context(final HttpServletRequest request, final IiifProperties properties) {
        final String service = ServletUriComponentsBuilder.fromContextPath(request).replaceQuery(null).build()
            .toUriString();
        final String serviceBase = service.endsWith("/") ? service : service + "/";
        return new PublicContext(serviceBase, resolve(serviceBase, properties.getPlatformPublicUrl()),
            resolve(serviceBase, properties.getUiPublicUrl()), request.getHeader(HttpHeaders.AUTHORIZATION));
    }

    private static String resolve(final String base, final String reference) {
        final String resolved = URI.create(base).resolve(reference).toString();
        return resolved.endsWith("/") ? resolved : resolved + "/";
    }

}
