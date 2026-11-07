package com.articulorium.ai.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.articulorium.ai.config.AiProperties;
import com.articulorium.ai.platform.PlatformReader;
import com.articulorium.ai.topology.TopologyClient;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Tests that the community structure is read once per caller and catalog lifetime.
 */
class StructureCatalogTest {

    private static final MediaType N_TRIPLES = MediaType.parseMediaType("application/n-triples");

    /** A clock that can be moved. */
    static final class MovableClock extends Clock {

        private Instant now = Instant.parse("2026-11-07T00:00:00Z");

        void advance(final Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

    }

    @Test
    void keepsTheCatalogOfEachCallerForItsLifetime() {
        final AiProperties properties = new AiProperties();
        properties.setPlatformUrl("http://platform.test/");
        properties.setTopologyUrl("http://topology.test/");
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        final MovableClock clock = new MovableClock();
        final StructureCatalog catalogs = new StructureCatalog(new PlatformReader(builder, properties),
            new TopologyClient(builder, properties), properties, clock);
        server.expect(once(), requestTo("http://platform.test/communities")).andExpect(header("Authorization", "Bearer a"))
            .andRespond(withSuccess("", N_TRIPLES));
        server.expect(once(), requestTo("http://topology.test/topologies"))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://platform.test/communities")).andExpect(header("Authorization", "Bearer b"))
            .andRespond(withSuccess("", N_TRIPLES));
        server.expect(once(), requestTo("http://topology.test/topologies"))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(times(1), requestTo("http://platform.test/communities"))
            .andExpect(header("Authorization", "Bearer a"))
            .andRespond(withSuccess("", N_TRIPLES));
        server.expect(once(), requestTo("http://topology.test/topologies"))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        final StructureCatalog.Catalog first = catalogs.catalog("Bearer a");
        assertThat(catalogs.catalog("Bearer a")).isSameAs(first);
        // another caller may read something else
        catalogs.catalog("Bearer b");
        clock.advance(Duration.ofMinutes(2));
        assertThat(catalogs.catalog("Bearer a")).isNotSameAs(first);

        server.verify();
    }

}
