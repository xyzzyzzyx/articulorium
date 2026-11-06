package com.articulorium.oai.web;

import java.net.URI;

import com.articulorium.oai.config.OaiProperties;
import com.articulorium.oai.platform.PlatformException;
import com.articulorium.oai.platform.PlatformView;
import com.articulorium.oai.service.HarvestContext;
import com.articulorium.oai.service.OaiResponse;
import com.articulorium.oai.service.OaiService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The OAI-PMH 2.0 endpoint: the repository's base URL, answering GET (query string) and POST
 * ({@code application/x-www-form-urlencoded}) requests. Protocol errors are answered with HTTP 200
 * as the protocol requires; when the platform cannot be read the answer is 503 with
 * {@code Retry-After}, which harvesters retry.
 */
@RestController
public class OaiController {

    /** Content type of every OAI-PMH response. */
    static final MediaType TEXT_XML = MediaType.parseMediaType("text/xml;charset=UTF-8");

    /** Seconds harvesters are asked to wait when the platform is unavailable. */
    static final String RETRY_AFTER = "60";

    private static final Logger LOG = LoggerFactory.getLogger(OaiController.class);

    private final OaiService service;

    private final OaiXmlWriter writer;

    private final OaiProperties properties;

    private final MeterRegistry meters;

    /**
     * Creates the controller.
     *
     * @param service    the protocol implementation
     * @param writer     the XML serialisation
     * @param properties OAI-PMH settings
     * @param meters     metrics, when actuator is present
     */
    public OaiController(final OaiService service, final OaiXmlWriter writer, final OaiProperties properties,
            final ObjectProvider<MeterRegistry> meters) {
        this.service = service;
        this.writer = writer;
        this.properties = properties;
        this.meters = meters.getIfAvailable();
    }

    /**
     * Answers a request sent with GET.
     *
     * @param request the request
     * @return the OAI-PMH response
     */
    @GetMapping("/")
    @Operation(summary = "OAI-PMH 2.0 request",
        description = "Identify, ListMetadataFormats, ListSets, GetRecord, ListIdentifiers and ListRecords with "
            + "oai_dc metadata; communities and collections are sets. See the OAI-PMH 2.0 specification.",
        parameters = {
            @Parameter(name = "verb", in = ParameterIn.QUERY, required = true,
                schema = @Schema(allowableValues = {"Identify", "ListMetadataFormats", "ListSets", "GetRecord",
                    "ListIdentifiers", "ListRecords"})),
            @Parameter(name = "identifier", in = ParameterIn.QUERY,
                description = "oai:<repository-identifier>:<path> (GetRecord, ListMetadataFormats)"),
            @Parameter(name = "metadataPrefix", in = ParameterIn.QUERY, schema = @Schema(allowableValues = "oai_dc")),
            @Parameter(name = "from", in = ParameterIn.QUERY, description = "YYYY-MM-DD or YYYY-MM-DDThh:mm:ssZ"),
            @Parameter(name = "until", in = ParameterIn.QUERY, description = "YYYY-MM-DD or YYYY-MM-DDThh:mm:ssZ"),
            @Parameter(name = "set", in = ParameterIn.QUERY, description = "a setSpec from ListSets"),
            @Parameter(name = "resumptionToken", in = ParameterIn.QUERY,
                description = "continues an incomplete list (exclusive)")},
        responses = {
            @ApiResponse(responseCode = "200", description = "An OAI-PMH response, possibly an OAI-PMH error",
                content = @Content(mediaType = "text/xml")),
            @ApiResponse(responseCode = "503", description = "The platform is unavailable; retry later")})
    public ResponseEntity<byte[]> get(final HttpServletRequest request) {
        return answer(request);
    }

    /**
     * Answers a request sent with POST.
     *
     * @param request the request
     * @return the OAI-PMH response
     */
    @PostMapping(path = "/", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @Operation(summary = "OAI-PMH 2.0 request (form encoded)", description = "The same arguments as GET, in the body.")
    public ResponseEntity<byte[]> post(final HttpServletRequest request) {
        return answer(request);
    }

    /**
     * Answers a platform failure with 503, which harvesters retry after {@code Retry-After}.
     *
     * @param e the failure
     * @return the response
     */
    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<String> unavailable(final PlatformException e) {
        LOG.warn("OAI-PMH request failed: {}", e.getMessage());
        count("unknown", "unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER)
            .contentType(MediaType.TEXT_PLAIN)
            .body("The repository is temporarily unavailable; retry later.");
    }

    private ResponseEntity<byte[]> answer(final HttpServletRequest request) {
        final OaiResponse response = service.handle(request.getParameterMap(), context(request, properties));
        final String verb = response.verb() == null ? "invalid" : response.verb().value();
        final String outcome = response.error() == null ? "ok" : response.error().code();
        LOG.debug("OAI-PMH {} {}: {}", verb, response.arguments(), outcome);
        count(verb, outcome);
        return ResponseEntity.ok().contentType(TEXT_XML).body(writer.write(response));
    }

    private void count(final String verb, final String outcome) {
        if (meters != null) {
            Counter.builder("oai.requests")
                .description("OAI-PMH requests by verb and outcome (ok or the OAI-PMH error code)")
                .tag("verb", verb)
                .tag("outcome", outcome)
                .register(meters)
                .increment();
        }
    }

    /**
     * Determines how the harvester sees the system, from the request's public URL (the gateway's
     * {@code X-Forwarded-*} headers are honoured) and the configured relative locations.
     *
     * @param request    the request
     * @param properties OAI-PMH settings
     * @return the harvester's view
     */
    static HarvestContext context(final HttpServletRequest request, final OaiProperties properties) {
        final String baseUrl = ServletUriComponentsBuilder.fromContextPath(request).replaceQuery(null).build()
            .toUriString();
        final String serviceBase = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        final PlatformView platform = new PlatformView(resolve(serviceBase, properties.getPlatformPublicUrl()),
            request.getHeader(HttpHeaders.AUTHORIZATION));
        return new HarvestContext(baseUrl, platform, resolve(serviceBase, properties.getUiPublicUrl()));
    }

    /**
     * Resolves a reference against a base as RFC 3986 does: {@code ..} segments above the root are
     * dropped, so {@code ../platform/} against {@code http://host/} (this service reached without
     * the gateway) is {@code http://host/platform/}.
     *
     * @param base      the base
     * @param reference the reference
     * @return the resolved URL, ending with {@code /}
     */
    static String resolve(final String base, final String reference) {
        final URI resolved = URI.create(base).resolve(reference);
        String url = resolved.toString();
        String path = resolved.getRawPath();
        if (resolved.isAbsolute() && path != null && path.startsWith("/..")) {
            while (path.startsWith("/../") || "/..".equals(path)) {
                path = path.substring(3);
            }
            url = resolved.getScheme() + "://" + resolved.getRawAuthority() + (path.isEmpty() ? "/" : path);
        }
        return url.endsWith("/") ? url : url + "/";
    }

}
