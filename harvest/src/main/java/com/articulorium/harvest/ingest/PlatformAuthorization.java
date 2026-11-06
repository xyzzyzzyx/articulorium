package com.articulorium.harvest.ingest;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.articulorium.harvest.config.HarvestProperties;
import com.articulorium.harvest.core.HarvestException;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * The {@code Authorization} header the harvester sends to the platform: a fixed value, or an access
 * token from an OAuth 2.0 client credentials grant (cached until shortly before it expires), or
 * none.
 */
public class PlatformAuthorization {

    /** Tokens are renewed this long before they expire. */
    private static final Duration MARGIN = Duration.ofSeconds(30);

    private final HarvestProperties.PlatformAuth settings;

    private final RestClient tokenClient;

    private final Clock clock;

    private String token;

    private Instant expires = Instant.MIN;

    /**
     * Creates the provider.
     *
     * @param settings    the platform credentials
     * @param tokenClient the client towards the token endpoint
     * @param clock       the clock
     */
    public PlatformAuthorization(final HarvestProperties.PlatformAuth settings, final RestClient tokenClient,
            final Clock clock) {
        this.settings = settings;
        this.tokenClient = tokenClient;
        this.clock = clock;
        if (StringUtils.hasText(settings.getTokenUri())
                && !(StringUtils.hasText(settings.getClientId()) && StringUtils.hasText(settings.getClientSecret()))) {
            throw new IllegalArgumentException("articulorium.harvest.platform-auth.token-uri needs client-id and "
                + "client-secret");
        }
    }

    /**
     * Returns the header value.
     *
     * @return the value, or {@code null} for anonymous requests
     * @throws HarvestException when a token cannot be obtained
     */
    public synchronized String header() {
        if (StringUtils.hasText(settings.getTokenUri())) {
            if (token == null || !clock.instant().isBefore(expires)) {
                fetchToken();
            }
            return "Bearer " + token;
        }
        return StringUtils.hasText(settings.getAuthorization()) ? settings.getAuthorization().trim() : null;
    }

    /**
     * Forgets the cached token (after the platform refused it), so the next request gets a new one.
     */
    public synchronized void invalidate() {
        token = null;
    }

    private void fetchToken() {
        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", settings.getClientId());
        form.add("client_secret", settings.getClientSecret());
        if (StringUtils.hasText(settings.getScope())) {
            form.add("scope", settings.getScope());
        }
        final JsonNode body;
        try {
            body = tokenClient.post().uri(settings.getTokenUri())
                .contentType(new MediaType(MediaType.APPLICATION_FORM_URLENCODED, StandardCharsets.UTF_8))
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        } catch (HttpClientErrorException e) {
            throw HarvestException.fatal("The token endpoint refused the harvester's client credentials ("
                + e.getStatusCode().value() + ")", e);
        } catch (RestClientException e) {
            throw HarvestException.recoverable("The token endpoint is not available: " + e.getMessage(), e);
        }
        final String accessToken = body == null ? null : body.path("access_token").asString(null);
        if (!StringUtils.hasText(accessToken)) {
            throw HarvestException.fatal("The token endpoint answered without access_token", null);
        }
        final long lifetime = body.path("expires_in").asLong(60);
        token = accessToken;
        expires = clock.instant().plusSeconds(lifetime).minus(MARGIN);
    }

}
