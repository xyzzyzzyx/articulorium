package com.articulorum.platform.webac;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import lombok.extern.slf4j.Slf4j;

/**
 * Validates Consul ACL tokens and turns them into WebAC {@link Agent}s.
 *
 * <p>A token is validated with Consul's {@code GET /v1/acl/token/self}, which returns the token's
 * accessor ID, description, policies and roles when the token is valid and 403 otherwise. Results
 * are cached for {@link WebAcProperties.Consul#getCacheTtl()}; a revoked token is therefore
 * rejected at the latest after that time.</p>
 */
@Slf4j
@Component
public class ConsulAclClient {

    /** Header Consul reads tokens from. */
    public static final String TOKEN_HEADER = "X-Consul-Token";

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
        new ParameterizedTypeReference<>() { };

    /** A cached validation result. */
    private record Cached(Agent agent, Instant expires) {
    }

    private final RestClient client;

    private final WebAcProperties properties;

    private final Clock clock;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * Creates the client.
     *
     * @param builder    HTTP client builder
     * @param properties WebAC settings
     */
    @Autowired
    public ConsulAclClient(final RestClient.Builder builder, final WebAcProperties properties) {
        this(builder, properties, Clock.systemUTC());
    }

    /**
     * Creates the client with a clock (for tests).
     *
     * @param builder    HTTP client builder
     * @param properties WebAC settings
     * @param clock      the clock deciding cache expiry
     */
    ConsulAclClient(final RestClient.Builder builder, final WebAcProperties properties, final Clock clock) {
        this.client = builder.build();
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Validates a token.
     *
     * @param token the secret ID of a Consul ACL token
     * @return the agent the token identifies
     * @throws AuthenticationFailedException when Consul rejects the token or cannot be asked
     */
    public Agent authenticate(final String token) {
        final Instant now = clock.instant();
        final Cached cached = cache.get(token);
        if (cached != null && cached.expires().isAfter(now)) {
            return cached.agent();
        }
        final Map<String, Object> details;
        try {
            details = client.get()
                .uri(trim(properties.getConsul().getUrl()) + "/v1/acl/token/self")
                .header(TOKEN_HEADER, token)
                .retrieve()
                .body(JSON_OBJECT);
        } catch (RestClientResponseException e) {
            final HttpStatusCode status = e.getStatusCode();
            cache.remove(token);
            throw new AuthenticationFailedException(status.value() == 403 || status.value() == 401
                ? "The Consul ACL token is not valid"
                : "Consul rejected the token check with status " + status.value());
        } catch (RestClientException e) {
            log.warn("Unable to validate a Consul ACL token: {}", e.getMessage());
            throw new AuthenticationFailedException("Consul is not reachable to validate the token");
        }
        final Agent agent = toAgent(details, properties.getConsul());
        cache.put(token, new Cached(agent, now.plus(properties.getConsul().getCacheTtl())));
        return agent;
    }

    /**
     * Maps Consul's token details to an agent.
     *
     * @param details  the JSON of {@code /v1/acl/token/self}
     * @param settings naming settings
     * @return the agent
     * @throws AuthenticationFailedException when the details identify nothing
     */
    @SuppressWarnings("unchecked")
    static Agent toAgent(final Map<String, Object> details, final WebAcProperties.Consul settings) {
        if (details == null) {
            throw new AuthenticationFailedException("Consul returned no token details");
        }
        final Object description = details.get("Description");
        final Object accessor = details.get("AccessorID");
        final String name = description instanceof String text && !text.isBlank()
            ? text.trim()
            : accessor == null ? null : String.valueOf(accessor);
        if (name == null || name.isBlank()) {
            throw new AuthenticationFailedException("The Consul ACL token has neither description nor accessor ID");
        }
        final Set<String> groups = new LinkedHashSet<>();
        for (final Object role : listOf(details.get("Roles"))) {
            if (role instanceof Map<?, ?> map && map.get("Name") != null) {
                groups.add(settings.getRolePrefix() + encode(String.valueOf(map.get("Name"))));
            }
        }
        for (final Object policy : listOf(details.get("Policies"))) {
            if (policy instanceof Map<?, ?> map && map.get("Name") != null) {
                groups.add(settings.getPolicyPrefix() + encode(String.valueOf(map.get("Name"))));
            }
        }
        return new Agent(settings.getAgentPrefix() + encode(name), groups, true);
    }

    private static List<Object> listOf(final Object value) {
        return value instanceof List<?> list ? List.copyOf(list.stream().map(Object.class::cast).toList()) : List.of();
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String trim(final String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

}
