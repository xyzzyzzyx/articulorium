package com.articulorum.platform.webac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Tests {@link ConsulAclClient}.
 */
class ConsulAclClientTest {

    private static final String SELF = "http://consul:8500/v1/acl/token/self";

    private WebAcProperties properties;

    private RestClient.Builder builder;

    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        properties = new WebAcProperties();
        properties.getConsul().setEnabled(true);
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    void mapsTokenDetailsToAgent() {
        final Agent agent = ConsulAclClient.toAgent(Map.of(
            "AccessorID", "6a1253d2", "Description", "Jane Doe",
            "Roles", List.of(Map.of("ID", "r1", "Name", "editors")),
            "Policies", List.of(Map.of("ID", "p1", "Name", "read-all"))), properties.getConsul());

        assertThat(agent.webId()).isEqualTo("info:consul/agent/Jane%20Doe");
        assertThat(agent.groups()).containsExactlyInAnyOrder("info:consul/role/editors", "info:consul/policy/read-all");
        assertThat(agent.authenticated()).isTrue();
    }

    @Test
    void fallsBackToTheAccessorId() {
        assertThat(ConsulAclClient.toAgent(Map.of("AccessorID", "6a1253d2", "Description", ""),
            properties.getConsul()).webId()).isEqualTo("info:consul/agent/6a1253d2");
        assertThatThrownBy(() -> ConsulAclClient.toAgent(Map.of(), properties.getConsul()))
            .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void validatesTokensWithConsulAndCachesTheResult() {
        server.expect(requestTo(SELF)).andExpect(header(ConsulAclClient.TOKEN_HEADER, "secret"))
            .andRespond(withSuccess("{\"AccessorID\":\"a1\",\"Description\":\"svc\"}", MediaType.APPLICATION_JSON));
        final ConsulAclClient client = new ConsulAclClient(builder, properties,
            Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC));

        assertThat(client.authenticate("secret").webId()).isEqualTo("info:consul/agent/svc");
        assertThat(client.authenticate("secret").webId()).isEqualTo("info:consul/agent/svc");
        server.verify();
    }

    @Test
    void cacheEntriesExpire() {
        properties.getConsul().setCacheTtl(Duration.ZERO);
        server.expect(requestTo(SELF))
            .andRespond(withSuccess("{\"AccessorID\":\"a1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(SELF)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        final ConsulAclClient client = new ConsulAclClient(builder, properties);

        client.authenticate("revoked-later");
        assertThatThrownBy(() -> client.authenticate("revoked-later"))
            .isInstanceOf(AuthenticationFailedException.class)
            .hasMessageContaining("not valid");
    }

}
