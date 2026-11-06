package com.articulorium.harvest.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import com.articulorium.harvest.config.HarvestProperties;
import com.articulorium.harvest.core.HarvestException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Tests the harvester's credentials towards the platform.
 */
class PlatformAuthorizationTest {

    private static final String TOKEN_URI = "http://keycloak.test/auth/realms/articulorium/protocol/openid-connect/token";

    private MockRestServiceServer server;

    private RestClient client;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-06T12:00:00Z"));

    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
    }

    private static HarvestProperties.PlatformAuth clientCredentials() {
        final HarvestProperties.PlatformAuth auth = new HarvestProperties.PlatformAuth();
        auth.setTokenUri(TOKEN_URI);
        auth.setClientId("articulorium-harvest");
        auth.setClientSecret("s3cret");
        auth.setScope("openid");
        return auth;
    }

    @Test
    void sendsNothingOrAFixedHeader() {
        final HarvestProperties.PlatformAuth auth = new HarvestProperties.PlatformAuth();
        assertThat(new PlatformAuthorization(auth, client, clock).header()).isNull();
        auth.setAuthorization(" Bearer consul-token ");
        assertThat(new PlatformAuthorization(auth, client, clock).header()).isEqualTo("Bearer consul-token");
        assertThat(auth.toString()).doesNotContain("consul-token");
    }

    @Test
    void cachesClientCredentialsTokensUntilShortlyBeforeTheyExpire() {
        server.expect(once(), requestTo(TOKEN_URI))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().formDataContains(java.util.Map.of("grant_type", "client_credentials",
                "client_id", "articulorium-harvest", "client_secret", "s3cret", "scope", "openid")))
            .andRespond(withSuccess("{\"access_token\":\"t1\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(TOKEN_URI))
            .andRespond(withSuccess("{\"access_token\":\"t2\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(TOKEN_URI))
            .andRespond(withSuccess("{\"access_token\":\"t3\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
        final PlatformAuthorization authorization = new PlatformAuthorization(clientCredentials(), client, clock);

        assertThat(authorization.header()).isEqualTo("Bearer t1");
        now.set(now.get().plus(Duration.ofSeconds(269)));
        assertThat(authorization.header()).isEqualTo("Bearer t1");
        now.set(now.get().plus(Duration.ofSeconds(1)));
        assertThat(authorization.header()).isEqualTo("Bearer t2");
        authorization.invalidate();
        assertThat(authorization.header()).isEqualTo("Bearer t3");
        server.verify();
    }

    @Test
    void refusedClientCredentialsAreFatalAndAnUnavailableEndpointIsRecoverable() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        final PlatformAuthorization authorization = new PlatformAuthorization(clientCredentials(), client, clock);

        assertThatThrownBy(authorization::header)
            .isInstanceOfSatisfying(HarvestException.class, e -> {
                assertThat(e.isRecoverable()).isFalse();
                assertThat(e.getMessage()).doesNotContain("s3cret");
            });
        assertThatThrownBy(authorization::header)
            .isInstanceOfSatisfying(HarvestException.class, e -> assertThat(e.isRecoverable()).isTrue());
    }

    @Test
    void aTokenUriNeedsAClient() {
        final HarvestProperties.PlatformAuth auth = new HarvestProperties.PlatformAuth();
        auth.setTokenUri(TOKEN_URI);

        assertThatThrownBy(() -> new PlatformAuthorization(auth, client, clock))
            .isInstanceOf(IllegalArgumentException.class);
    }

}
