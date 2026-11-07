package com.articulorium.harvest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;

import com.articulorium.harvest.config.HarvestProperties;
import com.articulorium.harvest.web.HarvestAccess.Caller;
import com.articulorium.harvest.web.HarvestAccess.Operation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Tests the access check of the harvest API against a mocked platform.
 */
class HarvestAccessTest {

    private static final String WEBAC = "http://platform.test/.well-known/webac?resource=harvested";

    private MockRestServiceServer platform;

    private HarvestAccess access;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        platform = MockRestServiceServer.bindTo(builder).build();
        final HarvestProperties properties = new HarvestProperties();
        properties.setPlatformUrl("http://platform.test");
        // the harvester's own credentials must never be used for the caller's access
        properties.getPlatformAuth().setAuthorization("Bearer harvester");
        access = new HarvestAccess(builder, properties);
    }

    private static String info(final String webId, final boolean authenticated, final String... modes) {
        return """
            {"agent": {"webId": %s, "groups": [], "authenticated": %s}, "enforced": true,
             "resource": "/harvested", "aclResource": null, "inherited": true,
             "userModes": [%s], "publicModes": ["read"], "authorizations": []}
            """.formatted(webId == null ? "null" : "\"" + webId + "\"", authenticated,
            String.join(",", java.util.Arrays.stream(modes).map(mode -> "\"" + mode + "\"").toList()));
    }

    @Test
    void presentsTheCallersCredentialsToThePlatform() {
        platform.expect(requestTo(WEBAC)).andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer alice-token"))
            .andExpect(header("X-Forwarded-Host", "platform.test"))
            .andRespond(withSuccess(info("info:oidc/agent/alice", true, "read", "write"), MediaType.APPLICATION_JSON));

        final Caller caller = access.require("Bearer alice-token", Operation.OPERATE);

        assertThat(caller.webId()).isEqualTo("info:oidc/agent/alice");
        assertThat(caller.name()).isEqualTo("info:oidc/agent/alice");
        assertThat(caller.authenticated()).isTrue();
        assertThat(caller.may(Operation.VIEW)).isTrue();
        assertThat(caller.may(Operation.OPERATE)).isTrue();
        platform.verify();
    }

    @Test
    void asksAnonymouslyWithoutCredentials() {
        platform.expect(requestTo(WEBAC)).andExpect(headerDoesNotExist("Authorization"))
            .andRespond(withSuccess(info(null, false, "read"), MediaType.APPLICATION_JSON));

        final Caller caller = access.require(null, Operation.VIEW);

        assertThat(caller.name()).isEqualTo("anonymous");
        assertThat(caller.may(Operation.OPERATE)).isFalse();
    }

    @Test
    void refusesAnonymousCallersWithoutTheModeAsUnauthenticated() {
        platform.expect(requestTo(WEBAC)).andRespond(withSuccess(info(null, false, "read"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> access.require(null, Operation.OPERATE))
            .isInstanceOfSatisfying(HarvestAccessException.class, e -> {
                assertThat(e.getReason()).isEqualTo(HarvestAccessException.Reason.UNAUTHENTICATED);
                assertThat(e.getMessage()).contains("write").contains("/harvested");
            });
    }

    @Test
    void refusesKnownCallersWithoutTheModeAsForbidden() {
        platform.expect(requestTo(WEBAC))
            .andRespond(withSuccess(info("info:oidc/agent/bob", true), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> access.require("Bearer bob", Operation.VIEW))
            .isInstanceOfSatisfying(HarvestAccessException.class,
                e -> assertThat(e.getReason()).isEqualTo(HarvestAccessException.Reason.FORBIDDEN));
    }

    @Test
    void reportsCredentialsThePlatformRefuses() {
        platform.expect(requestTo(WEBAC)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> access.require("Bearer expired", Operation.VIEW))
            .isInstanceOfSatisfying(HarvestAccessException.class,
                e -> assertThat(e.getReason()).isEqualTo(HarvestAccessException.Reason.UNAUTHENTICATED));
    }

    @Test
    void reportsAnUnavailablePlatformWithoutItsDetails() {
        platform.expect(requestTo(WEBAC)).andRespond(withServerError().body("stack trace with secrets"));

        assertThatThrownBy(() -> access.require("Bearer alice", Operation.VIEW))
            .isInstanceOfSatisfying(HarvestAccessException.class, e -> {
                assertThat(e.getReason()).isEqualTo(HarvestAccessException.Reason.UNAVAILABLE);
                assertThat(e.getMessage()).doesNotContain("secrets");
            });
    }

    @Test
    void reportsAnUnreachablePlatform() {
        platform.expect(requestTo(WEBAC)).andRespond(request -> {
            throw new IOException("connection refused");
        });

        assertThatThrownBy(() -> access.caller(null))
            .isInstanceOfSatisfying(HarvestAccessException.class,
                e -> assertThat(e.getReason()).isEqualTo(HarvestAccessException.Reason.UNAVAILABLE));
    }

    @Test
    void reportsAnswersWithoutModesAsUnavailable() {
        platform.expect(requestTo(WEBAC)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> access.caller(null))
            .isInstanceOfSatisfying(HarvestAccessException.class,
                e -> assertThat(e.getReason()).isEqualTo(HarvestAccessException.Reason.UNAVAILABLE));
    }

}
