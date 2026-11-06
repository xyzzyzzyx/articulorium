package com.articulorium.oai.platform;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.articulorium.domain.rdf.ResourcePaths;
import com.articulorium.i18n.AcceptLanguageInterceptor;
import com.articulorium.oai.config.OaiProperties;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RiotException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;

/**
 * Read-only client of the platform: the Fedora search API ({@code fcr:search}) and RDF
 * representations of resources.
 *
 * <p>Every call carries the harvester's {@code Authorization} header, so the platform's Web Access
 * Control decides which records a harvester sees, and {@code X-Forwarded-*} headers describing the
 * harvester's public platform base, so every IRI the platform answers with is the one harvesters
 * can dereference whichever platform instance the load balancer picked.</p>
 */
@Component
public class PlatformClient {

    /** Media type requested for RDF. */
    static final String N_TRIPLES = "application/n-triples";

    private static final Logger LOG = LoggerFactory.getLogger(PlatformClient.class);

    private final RestClient client;

    private final String platformUrl;

    /**
     * Creates the client.
     *
     * @param builder    HTTP client builder (load-balanced when configured)
     * @param properties OAI-PMH settings
     */
    public PlatformClient(final RestClient.Builder builder, final OaiProperties properties) {
        // the user's locale travels with every call, so the platform answers in it
        this.client = builder.clone().requestInterceptor(new AcceptLanguageInterceptor()).build();
        this.platformUrl = properties.getPlatformUrl().endsWith("/")
            ? properties.getPlatformUrl()
            : properties.getPlatformUrl() + "/";
    }

    /**
     * Searches resources, ordered by modification time and then by path (both ascending).
     *
     * @param view       the harvester's view
     * @param conditions {@code fcr:search} conditions, combined with AND (see {@link Conditions})
     * @param maxResults the page size
     * @param offset     the number of results to skip
     * @return the page
     * @throws PlatformException when the platform fails or refuses the search
     */
    public SearchPage search(final PlatformView view, final List<String> conditions, final int maxResults,
            final int offset) {
        final StringBuilder uri = new StringBuilder(platformUrl).append("fcr:search?order_by=modified&order=asc")
            .append("&max_results=").append(maxResults)
            .append("&offset=").append(offset);
        conditions.forEach(condition -> uri.append("&condition=").append(URLEncoder.encode(condition,
            StandardCharsets.UTF_8)));
        final JsonNode body = call(() -> client.get()
            .uri(URI.create(uri.toString()))
            .headers(headers -> headers(headers, view))
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(JsonNode.class), "search");
        if (body == null) {
            throw new PlatformException(502, "The platform answered a search without a body", null);
        }
        final List<SearchHit> hits = new ArrayList<>();
        for (final JsonNode item : body.path("items")) {
            toHit(view, item).ifPresent(hits::add);
        }
        final JsonNode pagination = body.path("pagination");
        return new SearchPage(hits, pagination.path("totalResults").asLong(hits.size()),
            pagination.path("maxResults").asInt(maxResults));
    }

    /**
     * Reads the RDF representation of a resource.
     *
     * @param view the harvester's view
     * @param path the resource path
     * @return the model (with public IRIs), or empty when the resource does not exist or may not be
     *         read
     * @throws PlatformException when the platform fails
     */
    public Optional<Model> rdf(final PlatformView view, final String path) {
        final byte[] body;
        try {
            body = call(() -> client.get()
                .uri(URI.create(platformUrl + ResourcePaths.normalize(path)))
                .headers(headers -> headers(headers, view))
                .accept(MediaType.parseMediaType(N_TRIPLES))
                .retrieve()
                .body(byte[].class), "read /" + ResourcePaths.normalize(path));
        } catch (PlatformException e) {
            if (e.getStatus() == 401 || e.getStatus() == 403 || e.getStatus() == 404 || e.getStatus() == 410) {
                return Optional.empty();
            }
            throw e;
        }
        final Model model = ModelFactory.createDefaultModel();
        if (body != null) {
            try {
                RDFDataMgr.read(model, new ByteArrayInputStream(body), view.iri(path), Lang.NTRIPLES);
            } catch (RiotException e) {
                throw new PlatformException(502, "The platform answered unreadable N-Triples for /" + path, e);
            }
        }
        return Optional.of(model);
    }

    private static Optional<SearchHit> toHit(final PlatformView view, final JsonNode item) {
        final String path = view.pathOf(item.path("fedora_id").asString(null));
        final Instant modified = instant(item.path("modified"));
        if (path == null || modified == null) {
            LOG.warn("Ignoring a search hit outside {} or without a modification time: {}", view.base(), item);
            return Optional.empty();
        }
        final Set<String> types = new LinkedHashSet<>();
        item.path("rdf_type").forEach(type -> types.add(type.asString()));
        return Optional.of(new SearchHit(path, modified, types));
    }

    /**
     * Reads a time stamp the platform serialised either as an ISO-8601 instant or as a number of
     * seconds since the epoch.
     *
     * @param node the JSON value
     * @return the instant, or {@code null} when absent or malformed
     */
    static Instant instant(final JsonNode node) {
        if (node.isNumber()) {
            final java.math.BigDecimal seconds = node.decimalValue();
            return Instant.ofEpochSecond(seconds.longValue(),
                seconds.remainder(java.math.BigDecimal.ONE).movePointRight(9).longValue());
        }
        if (node.isString()) {
            try {
                return Instant.parse(node.asString());
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return null;
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

    private static void headers(final HttpHeaders headers, final PlatformView view) {
        headers.addAll(forwardedHeaders(URI.create(view.base())));
        if (StringUtils.hasText(view.authorization())) {
            headers.set(HttpHeaders.AUTHORIZATION, view.authorization());
        }
    }

    private static <T> T call(final Supplier<T> request, final String what) {
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
