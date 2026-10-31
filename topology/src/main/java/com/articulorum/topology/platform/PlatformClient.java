package com.articulorum.topology.platform;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import com.articulorum.domain.rdf.ResourcePaths;
import com.articulorum.topology.config.TopologyProperties;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Minimal LDP client of the platform used to store custom topologies and to build topology
 * instances.
 *
 * <p>Every call forwards the caller's {@code Authorization} header, so the platform's Web Access
 * Control decides what the caller may create. Resources are addressed by path below the LDP root.
 * Requests carry {@code X-Forwarded-*} headers describing {@link #url(String) the configured
 * platform URL}, so the platform mints every IRI (in bodies and {@code Location} headers) under
 * that URL whichever instance the load balancer picked.</p>
 */
@Component
public class PlatformClient {

    /** Media type requested for RDF. */
    static final String N_TRIPLES = "application/n-triples";

    private final RestClient client;

    private final String base;

    private final HttpHeaders forwarded;

    /**
     * Creates the client.
     *
     * @param builder    HTTP client builder (load-balanced when configured)
     * @param properties topology settings
     */
    public PlatformClient(final RestClient.Builder builder, final TopologyProperties properties) {
        this.client = builder.build();
        this.base = properties.getPlatformUrl().endsWith("/")
            ? properties.getPlatformUrl()
            : properties.getPlatformUrl() + "/";
        this.forwarded = forwardedHeaders(URI.create(base));
    }

    /**
     * Builds the {@code X-Forwarded-*} headers that make the platform mint IRIs under a base.
     *
     * @param base the base URL
     * @return the headers
     */
    static HttpHeaders forwardedHeaders(final URI base) {
        final HttpHeaders headers = new HttpHeaders();
        final String scheme = base.getScheme() == null ? "http" : base.getScheme();
        final int port = base.getPort() >= 0 ? base.getPort() : ("https".equals(scheme) ? 443 : 80);
        headers.set("X-Forwarded-Proto", scheme);
        headers.set("X-Forwarded-Host", base.getHost());
        headers.set("X-Forwarded-Port", Integer.toString(port));
        final String path = base.getRawPath();
        if (path != null && path.length() > 1) {
            headers.set("X-Forwarded-Prefix", path.endsWith("/") ? path.substring(0, path.length() - 1) : path);
        }
        return headers;
    }

    /**
     * Returns the absolute URL of a resource as seen from this service.
     *
     * @param path the resource path
     * @return the URL
     */
    public String url(final String path) {
        return base + ResourcePaths.normalize(path);
    }

    /**
     * Creates a resource in a container.
     *
     * @param parent        the container path
     * @param slug          the suggested name, may be {@code null}
     * @param turtle        the Turtle body ({@code <>} is the new resource)
     * @param typeLinks     interaction model IRIs sent as {@code Link: <...>; rel="type"}
     * @param authorization the caller's {@code Authorization} header, may be {@code null}
     * @return the path of the created resource
     */
    public String create(final String parent, final String slug, final String turtle, final List<String> typeLinks,
            final String authorization) {
        final ResponseEntity<Void> response = call(() -> client.post()
            .uri(URI.create(url(parent)))
            .headers(headers -> {
                auth(headers, authorization);
                if (StringUtils.hasText(slug)) {
                    headers.set("Slug", slug);
                }
                typeLinks.forEach(type -> headers.add(HttpHeaders.LINK, "<" + type + ">; rel=\"type\""));
            })
            .contentType(MediaType.parseMediaType("text/turtle"))
            .body(turtle)
            .retrieve()
            .toBodilessEntity(), "create a resource in /" + ResourcePaths.normalize(parent));
        final URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new PlatformException(502, "The platform created a resource without a Location", null);
        }
        return pathOf(location);
    }

    /**
     * Creates or replaces a resource at a path.
     *
     * @param path          the resource path
     * @param body          the body
     * @param contentType   its media type
     * @param typeLinks     interaction model IRIs
     * @param authorization the caller's {@code Authorization} header, may be {@code null}
     */
    public void put(final String path, final byte[] body, final String contentType, final List<String> typeLinks,
            final String authorization) {
        call(() -> client.put()
            .uri(URI.create(url(path)))
            .headers(headers -> {
                auth(headers, authorization);
                typeLinks.forEach(type -> headers.add(HttpHeaders.LINK, "<" + type + ">; rel=\"type\""));
            })
            .contentType(MediaType.parseMediaType(contentType))
            .body(body)
            .retrieve()
            .toBodilessEntity(), "store /" + ResourcePaths.normalize(path));
    }

    /**
     * Reads the bytes of a resource.
     *
     * @param path          the resource path
     * @param authorization the caller's {@code Authorization} header, may be {@code null}
     * @return the bytes, or empty when the resource does not exist
     */
    public Optional<byte[]> bytes(final String path, final String authorization) {
        try {
            return Optional.ofNullable(call(() -> client.get()
                .uri(URI.create(url(path)))
                .headers(headers -> auth(headers, authorization))
                .retrieve()
                .body(byte[].class), "read /" + ResourcePaths.normalize(path)));
        } catch (PlatformException e) {
            if (e.getStatus() == 404 || e.getStatus() == 410) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * Reads the RDF representation of a resource.
     *
     * @param path          the resource path
     * @param authorization the caller's {@code Authorization} header, may be {@code null}
     * @return the model (with the platform's IRIs), or empty when the resource does not exist
     */
    public Optional<Model> rdf(final String path, final String authorization) {
        final Optional<byte[]> body;
        try {
            body = Optional.ofNullable(call(() -> client.get()
                .uri(URI.create(url(path)))
                .headers(headers -> auth(headers, authorization))
                .accept(MediaType.parseMediaType(N_TRIPLES))
                .retrieve()
                .body(byte[].class), "read /" + ResourcePaths.normalize(path)));
        } catch (PlatformException e) {
            if (e.getStatus() == 404 || e.getStatus() == 410) {
                return Optional.empty();
            }
            throw e;
        }
        final Model model = ModelFactory.createDefaultModel();
        body.ifPresent(bytes -> RDFDataMgr.read(model, new ByteArrayInputStream(bytes), url(path), Lang.NTRIPLES));
        return Optional.of(model);
    }

    /**
     * Deletes a resource.
     *
     * @param path          the resource path
     * @param authorization the caller's {@code Authorization} header, may be {@code null}
     */
    public void delete(final String path, final String authorization) {
        call(() -> client.delete()
            .uri(URI.create(url(path)))
            .headers(headers -> auth(headers, authorization))
            .retrieve()
            .toBodilessEntity(), "delete /" + ResourcePaths.normalize(path));
    }

    /**
     * Turns an IRI the platform returned into a path below the LDP root.
     *
     * @param iri the IRI
     * @return the normalised path
     */
    public String pathOf(final URI iri) {
        String path = iri.getRawPath() == null ? "" : iri.getRawPath();
        final String basePath = URI.create(base).getRawPath();
        if (basePath != null && basePath.length() > 1 && path.startsWith(basePath)) {
            path = path.substring(basePath.length());
        }
        return ResourcePaths.normalize(path);
    }

    private void auth(final HttpHeaders headers, final String authorization) {
        headers.addAll(forwarded);
        if (StringUtils.hasText(authorization)) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
    }

    private static <T> T call(final java.util.function.Supplier<T> request, final String what) {
        try {
            return request.get();
        } catch (HttpClientErrorException e) {
            throw new PlatformException(e.getStatusCode().value(), "The platform refused to " + what + ": "
                + e.getResponseBodyAsString(StandardCharsets.UTF_8), e);
        } catch (RestClientResponseException e) {
            throw new PlatformException(502, "The platform failed to " + what + " (status "
                + e.getStatusCode().value() + ")", e);
        } catch (RestClientException e) {
            throw new PlatformException(502, "The platform is not reachable to " + what + ": " + e.getMessage(), e);
        }
    }

}
