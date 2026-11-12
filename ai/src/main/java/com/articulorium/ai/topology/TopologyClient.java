package com.articulorium.ai.topology;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import com.articulorium.ai.AgentException;
import com.articulorium.ai.AgentException.Reason;
import com.articulorium.ai.config.AiConfiguration;
import com.articulorium.ai.config.AiProperties;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Reads topologies from the topology API, with the caller's credentials (custom topologies are
 * stored in the repository, under its Web Access Control), and validates drafts with the same rules
 * the API applies when a topology is saved.
 */
@Component
@RegisterReflectionForBinding({TopologySummary.class, TopologyDraft.class, TopologyValidation.class})
public class TopologyClient {

    private static final Logger LOG = LoggerFactory.getLogger(TopologyClient.class);

    private static final ParameterizedTypeReference<List<TopologySummary>> SUMMARIES = new ParameterizedTypeReference<>() {
    };

    private final RestClient client;

    private final String base;

    /**
     * Creates the client.
     *
     * @param builder    the builder of service clients (load balanced when configured)
     * @param properties the agent settings
     */
    public TopologyClient(@Qualifier(AiConfiguration.SERVICES) final RestClient.Builder builder,
            final AiProperties properties) {
        this.client = builder.clone().build();
        this.base = properties.getTopologyUrl().endsWith("/") ? properties.getTopologyUrl()
            : properties.getTopologyUrl() + "/";
    }

    /**
     * Lists every topology.
     *
     * @param authorization the caller's {@code Authorization} header, may be {@code null}
     * @return the summaries, built-in ones first
     */
    public List<TopologySummary> list(@Nullable final String authorization) {
        final List<TopologySummary> summaries = call("list the topologies", () -> client.get()
            .uri(URI.create(base + "topologies"))
            .headers(headers -> auth(headers, authorization))
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(SUMMARIES));
        return summaries == null ? List.of() : summaries;
    }

    /**
     * Reads a topology.
     *
     * @param id            the identifier
     * @param authorization the caller's {@code Authorization} header, may be {@code null}
     * @return the topology, or empty when there is none
     */
    public Optional<TopologyDraft> get(final String id, @Nullable final String authorization) {
        try {
            return Optional.ofNullable(client.get()
                .uri(UriComponentsBuilder.fromUriString(base + "topologies/{id}").buildAndExpand(id).encode().toUri())
                .headers(headers -> auth(headers, authorization))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(TopologyDraft.class));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw failure("read topology " + id, e);
        } catch (RestClientException e) {
            throw failure("read topology " + id, e);
        }
    }

    /**
     * Validates a draft as the topology API would when it is saved.
     *
     * @param draft         the draft
     * @param authorization the caller's {@code Authorization} header, may be {@code null}
     * @return the validation
     */
    public TopologyValidation validate(final TopologyDraft draft, @Nullable final String authorization) {
        final TopologyValidation validation = call("validate a topology", () -> client.post()
            .uri(URI.create(base + "topologies/validation"))
            .headers(headers -> auth(headers, authorization))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(draft)
            .retrieve()
            .body(TopologyValidation.class));
        return validation == null ? new TopologyValidation(false, List.of("The topology service answered nothing"), false)
            : validation;
    }

    private <T> T call(final String what, final java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientException e) {
            throw failure(what, e);
        }
    }

    private static AgentException failure(final String what, final RestClientException e) {
        if (e instanceof RestClientResponseException response && response.getStatusCode().value() == 401) {
            return new AgentException(Reason.UNAUTHENTICATED, "The topology service refused the credentials");
        }
        LOG.warn("The topology service failed to {}: {}", what, e.getMessage());
        return new AgentException(Reason.PLATFORM_UNAVAILABLE, "The topology service cannot " + what, e);
    }

    private static void auth(final HttpHeaders headers, @Nullable final String authorization) {
        if (StringUtils.hasText(authorization)) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
    }

}
