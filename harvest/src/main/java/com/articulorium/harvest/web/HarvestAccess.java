package com.articulorium.harvest.web;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;

import com.articulorium.harvest.config.HarvestProperties;
import com.articulorium.harvest.ingest.PlatformClient;

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
 * Decides who may use the harvest API with the platform's Web Access Control: the caller's own
 * credentials (its {@code Authorization} header, passed on unchanged) are presented to the
 * platform's {@code /.well-known/webac} endpoint for the container of the harvested records
 * ({@code articulorium.harvest.container}).
 *
 * <ul>
 * <li>{@link Operation#VIEW viewing} sources and reports needs {@code read} on the container;</li>
 * <li>{@link Operation#OPERATE starting and cancelling} harvests needs {@code write} on it, the
 * mode that lets an agent change the records harvests write.</li>
 * </ul>
 *
 * <p>The platform stays the only place where credentials are validated and access is decided, so
 * the API follows whatever identity providers and ACLs the platform has (and, with Web Access
 * Control disabled there, is as open as the platform). The harvester's own platform credentials are
 * never used for these checks.</p>
 */
@Component
public class HarvestAccess {

    private static final Logger LOG = LoggerFactory.getLogger(HarvestAccess.class);

    /** Path of the platform's access information endpoint, below the LDP root. */
    static final String WEBAC_PATH = ".well-known/webac";

    private final RestClient client;

    private final String base;

    private final String container;

    private final HttpHeaders forwarded;

    /**
     * Creates the access check.
     *
     * @param builder    the platform client builder (load balanced when configured)
     * @param properties the harvest settings
     */
    public HarvestAccess(final RestClient.Builder builder, final HarvestProperties properties) {
        this.client = builder.clone().build();
        final String url = properties.getPlatformUrl();
        this.base = url.endsWith("/") ? url : url + "/";
        this.container = properties.getContainer();
        this.forwarded = PlatformClient.forwardedHeaders(URI.create(base));
    }

    /**
     * Checks that the caller may perform an operation.
     *
     * @param authorization the caller's {@code Authorization} header, {@code null} when anonymous
     * @param operation     what the caller asks for
     * @return the caller, as the platform identified it
     * @throws HarvestAccessException when the caller may not, or when access cannot be decided
     */
    public Caller require(@Nullable final String authorization, final Operation operation) {
        final Caller caller = caller(authorization);
        if (!caller.modes().contains(operation.mode())) {
            throw new HarvestAccessException(caller.authenticated() ? HarvestAccessException.Reason.FORBIDDEN
                : HarvestAccessException.Reason.UNAUTHENTICATED,
                "Harvests need " + operation.mode() + " access to /" + container);
        }
        return caller;
    }

    /**
     * Asks the platform who the caller is and what it may do with the harvested records.
     *
     * @param authorization the caller's {@code Authorization} header, {@code null} when anonymous
     * @return the caller
     * @throws HarvestAccessException when the credentials are refused or the platform cannot answer
     */
    public Caller caller(@Nullable final String authorization) {
        final URI uri = UriComponentsBuilder.fromUriString(base + WEBAC_PATH)
            .queryParam("resource", container).encode().build().toUri();
        final JsonNode body;
        try {
            body = client.get().uri(uri)
                .headers(headers -> {
                    headers.addAll(forwarded);
                    if (StringUtils.hasText(authorization)) {
                        headers.set(HttpHeaders.AUTHORIZATION, authorization);
                    }
                })
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new HarvestAccessException(HarvestAccessException.Reason.UNAUTHENTICATED,
                    "The platform refused the credentials");
            }
            LOG.warn("The platform answered {} to the access check of /{}", e.getStatusCode().value(), container);
            throw unavailable();
        } catch (RestClientException e) {
            LOG.warn("The platform cannot be reached for the access check of /{}: {}", container, e.getMessage());
            throw unavailable();
        }
        if (body == null || !body.path("userModes").isArray()) {
            LOG.warn("The platform's access check of /{} answered no access modes", container);
            throw unavailable();
        }
        final Set<String> modes = new HashSet<>();
        body.path("userModes").forEach(mode -> modes.add(mode.asString()));
        final JsonNode agent = body.path("agent");
        final String webId = agent.path("webId").isString() ? agent.path("webId").asString() : null;
        return new Caller(webId, agent.path("authenticated").asBoolean(false), Set.copyOf(modes));
    }

    private static HarvestAccessException unavailable() {
        return new HarvestAccessException(HarvestAccessException.Reason.UNAVAILABLE,
            "Access to harvests cannot be checked: the platform is unavailable");
    }

    /** What a caller asks for. */
    public enum Operation {

        /** Listing sources, their harvests in progress and their reports. */
        VIEW("read"),

        /** Starting and cancelling harvests. */
        OPERATE("write");

        private final String mode;

        Operation(final String mode) {
            this.mode = mode;
        }

        /**
         * Returns the Web Access Control mode the operation needs on the harvested records.
         *
         * @return {@code read} or {@code write}
         */
        public String mode() {
            return mode;
        }

    }

    /**
     * A caller, as the platform identified it.
     *
     * @param webId         its identifier, {@code null} when anonymous
     * @param authenticated whether it presented valid credentials
     * @param modes         its modes on the container of the harvested records
     */
    public record Caller(@Nullable String webId, boolean authenticated, Set<String> modes) {

        /**
         * Tells whether the caller may perform an operation.
         *
         * @param operation the operation
         * @return {@code true} when allowed
         */
        public boolean may(final Operation operation) {
            return modes.contains(operation.mode());
        }

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
