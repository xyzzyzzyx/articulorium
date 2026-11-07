package com.articulorium.harvest.ingest;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Supplier;

import com.articulorium.domain.rdf.ResourcePaths;
import com.articulorium.harvest.core.HarvestException;
import com.articulorium.harvest.source.SourceHttp;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RiotException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The harvester's LDP client of the platform.
 *
 * <p>Requests carry the harvester's own credentials ({@link PlatformAuthorization}), so Web Access
 * Control decides what it may write, and {@code X-Forwarded-*} headers describing the configured
 * platform URL, so the IRIs the harvester writes are the ones the platform mints whichever instance
 * the load balancer picked. Failures become {@link HarvestException}s: 5xx, 408, 429, network errors
 * and a refused token (401, after which a new token is fetched) are recoverable; other 4xx are
 * not.</p>
 */
public class PlatformClient {

    /** Media type of the RDF the harvester writes and reads. */
    static final String N_TRIPLES = "application/n-triples";

    private static final int EXCERPT = 300;

    private final RestClient client;

    private final String base;

    private final HttpHeaders forwarded;

    private final PlatformAuthorization authorization;

    /**
     * Creates the client.
     *
     * @param client        the HTTP client (load balanced when configured)
     * @param platformUrl   the platform's LDP root
     * @param authorization the harvester's credentials
     */
    public PlatformClient(final RestClient client, final String platformUrl, final PlatformAuthorization authorization) {
        this.client = client;
        this.base = platformUrl.endsWith("/") ? platformUrl : platformUrl + "/";
        this.forwarded = forwardedHeaders(URI.create(base));
        this.authorization = authorization;
    }

    /**
     * Returns the IRI of a resource.
     *
     * @param path the path below the LDP root
     * @return the IRI
     */
    public String iri(final String path) {
        return base + ResourcePaths.normalize(path);
    }

    /**
     * Creates or replaces an RDF resource.
     *
     * @param path        the path
     * @param ntriples    the body (N-Triples, with {@link #iri(String)} as the resource's IRI)
     * @param type        the interaction model sent as {@code Link: <type>; rel="type"}
     * @param onlyIfAbsent whether to send {@code If-None-Match: *} (leave an existing resource alone)
     * @return {@code true} when the resource was created, {@code false} when it was replaced or, with
     *         {@code onlyIfAbsent}, already existed
     */
    public boolean put(final String path, final String ntriples, final String type, final boolean onlyIfAbsent) {
        try {
            final ResponseEntity<Void> response = call("store /" + ResourcePaths.normalize(path), () -> client.put()
                .uri(URI.create(iri(path)))
                .headers(headers -> {
                    headers(headers);
                    headers.add(HttpHeaders.LINK, "<" + type + ">; rel=\"type\"");
                    if (onlyIfAbsent) {
                        headers.set(HttpHeaders.IF_NONE_MATCH, "*");
                    }
                })
                .contentType(MediaType.parseMediaType(N_TRIPLES))
                .body(ntriples.getBytes(StandardCharsets.UTF_8))
                .retrieve()
                .toBodilessEntity());
            return response.getStatusCode().value() == 201;
        } catch (HarvestException e) {
            if (onlyIfAbsent && status(e) == 412) {
                return false;
            }
            // two creations racing: the loser may get a conflict instead of 412 from the platform
            if (onlyIfAbsent && status(e) == 409 && rdf(path).isPresent()) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Deletes a resource.
     *
     * @param path the path
     * @return {@code true} when it was deleted, {@code false} when it did not exist
     */
    public boolean delete(final String path) {
        try {
            call("delete /" + ResourcePaths.normalize(path), () -> client.delete()
                .uri(URI.create(iri(path)))
                .headers(this::headers)
                .retrieve()
                .toBodilessEntity());
            return true;
        } catch (HarvestException e) {
            if (status(e) == 404 || status(e) == 410) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Reads the RDF of a resource.
     *
     * @param path the path
     * @return the model, or empty when the resource does not exist
     */
    public Optional<Model> rdf(final String path) {
        final byte[] body;
        try {
            body = call("read /" + ResourcePaths.normalize(path), () -> client.get()
                .uri(URI.create(iri(path)))
                .headers(this::headers)
                .accept(MediaType.parseMediaType(N_TRIPLES))
                .retrieve()
                .body(byte[].class));
        } catch (HarvestException e) {
            if (status(e) == 404 || status(e) == 410) {
                return Optional.empty();
            }
            throw e;
        }
        final Model model = ModelFactory.createDefaultModel();
        if (body != null) {
            try {
                RDFDataMgr.read(model, new ByteArrayInputStream(body), iri(path), Lang.NTRIPLES);
            } catch (RiotException e) {
                throw HarvestException.recoverable("The platform answered unreadable N-Triples for /" + path, e);
            }
        }
        return Optional.of(model);
    }

    /**
     * Applies a SPARQL Update to a resource.
     *
     * @param path   the path
     * @param update the update ({@code <>} is the resource)
     */
    public void patch(final String path, final String update) {
        call("update /" + ResourcePaths.normalize(path), () -> client.patch()
            .uri(URI.create(iri(path)))
            .headers(this::headers)
            .contentType(MediaType.parseMediaType("application/sparql-update"))
            .body(update.getBytes(StandardCharsets.UTF_8))
            .retrieve()
            .toBodilessEntity());
    }

    /**
     * Builds the {@code X-Forwarded-*} headers that make the platform mint IRIs under a base.
     *
     * @param base the base URL
     * @return the headers
     */
    public static HttpHeaders forwardedHeaders(final URI base) {
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

    private void headers(final HttpHeaders headers) {
        headers.addAll(forwarded);
        final String value = authorization.header();
        if (StringUtils.hasText(value)) {
            headers.set(HttpHeaders.AUTHORIZATION, value);
        }
    }

    private <T> T call(final String what, final Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            final int status = e.getStatusCode().value();
            final String body = e.getResponseBodyAsString(StandardCharsets.UTF_8).strip().replaceAll("\\s+", " ");
            final String message = "The platform answered " + status + " to " + what
                + (body.isEmpty() ? "" : ": " + (body.length() > EXCERPT ? body.substring(0, EXCERPT) + "…" : body));
            if (status == 401) {
                // an expired or revoked token: a new one may be accepted; refused credentials fail every record alike
                authorization.invalidate();
                throw new PlatformFailure(message, true, status, e);
            }
            throw new PlatformFailure(message, status == 408 || status == 429 || status >= 500, status, e);
        } catch (ResourceAccessException e) {
            throw new PlatformFailure("The platform is not reachable to " + what + ": "
                + SourceHttp.describe(e.getMostSpecificCause()), true, 0, e);
        } catch (RestClientException e) {
            throw new PlatformFailure("The platform answered " + what + " unreadably: " + e.getMessage(), true, 0, e);
        }
    }

    private static int status(final HarvestException e) {
        return e instanceof PlatformFailure failure ? failure.status : 0;
    }

    /** A failed platform request with its HTTP status (0 when there was no response). */
    static final class PlatformFailure extends HarvestException {

        private static final long serialVersionUID = 1L;

        private final int status;

        PlatformFailure(final String message, final boolean recoverable, final int status, final Throwable cause) {
            super(message, recoverable, null, cause);
            this.status = status;
        }

        int status() {
            return status;
        }

    }

}
