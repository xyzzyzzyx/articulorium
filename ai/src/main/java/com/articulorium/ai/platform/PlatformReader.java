package com.articulorium.ai.platform;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import com.articulorium.ai.AgentException;
import com.articulorium.ai.AgentException.Reason;
import com.articulorium.ai.config.AiProperties;
import com.articulorium.domain.rdf.ResourcePaths;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RiotException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import tools.jackson.databind.JsonNode;

/**
 * Reads the repository on behalf of a caller: every request carries the caller's own
 * {@code Authorization} header, so the platform's Web Access Control decides what the agent sees
 * and nothing a caller may not read ever reaches the model. The agent never writes.
 *
 * <p>Requests carry {@code X-Forwarded-*} headers describing the configured platform URL, so the
 * platform mints every IRI under it whichever instance answers.</p>
 */
@Component
public class PlatformReader {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformReader.class);

    /** Media type requested for RDF. */
    static final String N_TRIPLES = "application/n-triples";

    /** Path of the platform's access information endpoint, below the LDP root. */
    static final String WEBAC_PATH = ".well-known/webac";

    /** Name of a binary's description below the binary. */
    public static final String DESCRIPTION = "description";

    private final RestClient client;

    private final String base;

    private final HttpHeaders forwarded;

    /**
     * Creates the reader.
     *
     * @param builder    the HTTP client builder (load balanced when configured)
     * @param properties the agent settings
     */
    public PlatformReader(final RestClient.Builder builder, final AiProperties properties) {
        this.client = builder.clone().build();
        this.base = properties.getPlatformUrl().endsWith("/") ? properties.getPlatformUrl()
            : properties.getPlatformUrl() + "/";
        this.forwarded = forwardedHeaders(URI.create(base));
    }

    /**
     * Returns the IRI of a resource.
     *
     * @param path the path below the LDP root
     * @return the IRI
     */
    public String iri(final String path) {
        return base + checked(path);
    }

    /**
     * Normalises a path from a request, refusing one that would address another resource than the
     * one whose access is checked ({@code ..} segments, queries, fragments, characters URIs do not
     * allow).
     *
     * @param path the path
     * @return the normalised path
     * @throws AgentException {@link Reason#INVALID_REQUEST} for an unsafe path
     */
    public static String checked(final String path) {
        if (!ResourcePaths.isSafe(path == null ? "" : path)) {
            throw new AgentException(Reason.INVALID_REQUEST, "The path is not a resource path");
        }
        return ResourcePaths.normalize(path);
    }

    /**
     * Turns an IRI of the platform into a path below the LDP root.
     *
     * @param iri the IRI
     * @return the path, or empty when the IRI is not below the LDP root
     */
    public Optional<String> pathOf(final String iri) {
        if (iri == null || !iri.startsWith(base) || iri.contains("#")) {
            return Optional.empty();
        }
        return Optional.of(ResourcePaths.normalize(iri.substring(base.length())));
    }

    /**
     * Reads the RDF of a resource; for a binary, the RDF of its description (whose subject is the
     * binary's IRI), which the platform redirects RDF clients to.
     *
     * @param path          the path
     * @param authorization the caller's {@code Authorization} header, may be {@code null}
     * @return the model, or empty when the resource does not exist or the caller may not read it
     * @throws AgentException when the platform cannot be reached or refuses the credentials
     */
    public Optional<Model> rdf(final String path, @Nullable final String authorization) {
        URI uri = URI.create(iri(path));
        for (int hop = 0; hop < 2; hop++) {
            final URI target = uri;
            final Fetched fetched;
            try {
                fetched = client.get().uri(target)
                    .headers(headers -> headers(headers, authorization))
                    .accept(MediaType.parseMediaType(N_TRIPLES))
                    .exchange((request, response) -> new Fetched(response.getStatusCode().value(),
                        response.getHeaders().getLocation(), response.getBody().readAllBytes()), true);
            } catch (RestClientException e) {
                throw unavailable(path, e);
            }
            final int status = fetched.status();
            if (status == 303 && fetched.location() != null) {
                // a binary: its description
                uri = target.resolve(fetched.location());
                continue;
            }
            if (status == 404 || status == 410 || status == 403) {
                // a resource the caller may not read is treated like one that does not exist
                return Optional.empty();
            }
            if (status == 401) {
                throw new AgentException(Reason.UNAUTHENTICATED, "The platform refused the credentials");
            }
            if (status >= 300) {
                throw new AgentException(Reason.PLATFORM_UNAVAILABLE, "The platform answered " + status
                    + " to reading /" + ResourcePaths.normalize(path));
            }
            final Model model = ModelFactory.createDefaultModel();
            try {
                RDFDataMgr.read(model, new ByteArrayInputStream(fetched.body()), uri.toString(), Lang.NTRIPLES);
            } catch (RiotException e) {
                throw new AgentException(Reason.PLATFORM_UNAVAILABLE, "The platform answered unreadable N-Triples for /"
                    + ResourcePaths.normalize(path), e);
            }
            return Optional.of(model);
        }
        throw new AgentException(Reason.PLATFORM_UNAVAILABLE, "The platform redirected /" + ResourcePaths.normalize(path)
            + " too often");
    }

    /**
     * Reads the bytes of a binary, at most a number of them.
     *
     * @param path          the binary's path
     * @param authorization the caller's {@code Authorization} header, may be {@code null}
     * @param maxBytes      the largest number of bytes read
     * @return the bytes, or empty when the binary does not exist or the caller may not read it
     * @throws AgentException {@link Reason#TOO_LARGE} when the binary is larger, or when the platform
     *                        cannot be reached
     */
    public Optional<byte[]> bytes(final String path, @Nullable final String authorization, final long maxBytes) {
        try {
            return Optional.ofNullable(client.get().uri(URI.create(iri(path)))
                .headers(headers -> headers(headers, authorization))
                .exchange((request, response) -> {
                    if (response.getStatusCode().value() == 404 || response.getStatusCode().value() == 410
                            || response.getStatusCode().value() == 403) {
                        return null;
                    }
                    if (response.getStatusCode().value() == 401) {
                        throw new AgentException(Reason.UNAUTHENTICATED, "The platform refused the credentials");
                    }
                    if (response.getStatusCode().isError()) {
                        throw new AgentException(Reason.PLATFORM_UNAVAILABLE, "The platform answered "
                            + response.getStatusCode().value() + " to reading /" + ResourcePaths.normalize(path));
                    }
                    return read(response.getBody(), maxBytes, path);
                }));
        } catch (RestClientException e) {
            throw unavailable(path, e);
        }
    }

    /**
     * Asks the platform who the caller is and what it may do with a resource.
     *
     * @param path          the resource
     * @param authorization the caller's {@code Authorization} header, may be {@code null}
     * @return the caller's access
     * @throws AgentException {@link Reason#UNAUTHENTICATED} when the credentials are refused,
     *                        {@link Reason#PLATFORM_UNAVAILABLE} when the platform cannot answer
     */
    public Access access(final String path, @Nullable final String authorization) {
        final URI uri = UriComponentsBuilder.fromUriString(base + WEBAC_PATH)
            .queryParam("resource", checked(path)).encode().build().toUri();
        final JsonNode body;
        try {
            body = client.get().uri(uri)
                .headers(headers -> headers(headers, authorization))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new AgentException(Reason.UNAUTHENTICATED, "The platform refused the credentials");
            }
            LOG.warn("The platform answered {} to the access check of /{}", e.getStatusCode().value(),
                ResourcePaths.normalize(path));
            throw new AgentException(Reason.PLATFORM_UNAVAILABLE, "Access cannot be checked: the platform answered "
                + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw unavailable(path, e);
        }
        if (body == null || !body.path("userModes").isArray()) {
            throw new AgentException(Reason.PLATFORM_UNAVAILABLE, "The platform's access check answered no access modes");
        }
        final Set<String> modes = new HashSet<>();
        body.path("userModes").forEach(mode -> modes.add(mode.asString()));
        final JsonNode agent = body.path("agent");
        final String webId = agent.path("webId").isString() ? agent.path("webId").asString() : null;
        return new Access(webId, agent.path("authenticated").asBoolean(false), Set.copyOf(modes));
    }

    private void headers(final HttpHeaders headers, @Nullable final String authorization) {
        headers.addAll(forwarded);
        if (StringUtils.hasText(authorization)) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
    }

    private static AgentException unavailable(final String path, final RestClientException e) {
        LOG.warn("The platform cannot be reached to read /{}: {}", ResourcePaths.normalize(path), e.getMessage());
        return new AgentException(Reason.PLATFORM_UNAVAILABLE, "The platform cannot be reached", e);
    }

    private static byte[] read(final InputStream in, final long maxBytes, final String path) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > maxBytes) {
                throw new AgentException(Reason.TOO_LARGE, "/" + ResourcePaths.normalize(path) + " is larger than "
                    + maxBytes + " bytes");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
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
        final String prefix = base.getRawPath();
        if (prefix != null && prefix.length() > 1) {
            headers.set("X-Forwarded-Prefix", prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix);
        }
        return headers;
    }

    private record Fetched(int status, @Nullable URI location, byte[] body) {
    }

    /**
     * A caller's access to a resource, as the platform decided it.
     *
     * @param webId         the caller's identifier, {@code null} when anonymous
     * @param authenticated whether it presented valid credentials
     * @param modes         its Web Access Control modes on the resource ({@code read}, {@code write}, ...)
     */
    public record Access(@Nullable String webId, boolean authenticated, Set<String> modes) {

        /**
         * Returns a name for logs.
         *
         * @return the WebID, or {@code anonymous}
         */
        public String name() {
            return webId == null ? "anonymous" : webId;
        }

    }

}
