package com.articulorium.harvest.source.oai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.articulorium.harvest.core.HarvestException;
import com.articulorium.harvest.core.HarvestPage;
import com.articulorium.harvest.core.HarvestSource;
import com.articulorium.harvest.core.HarvestWindow;
import com.articulorium.harvest.core.HarvestedRecord;
import com.articulorium.harvest.core.SourceCredentials;
import com.articulorium.harvest.source.Fixtures;
import com.articulorium.harvest.source.SourceRestClients;

import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Tests the generic OAI-PMH source against recorded responses.
 */
class OaiPmhSourceTest {

    private static final String BASE = "https://repo.test/oai";

    private static final String DCTERMS = "http://purl.org/dc/terms/";

    private MockRestServiceServer server;

    private OaiPmhSourceFactory factory;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        factory = new OaiPmhSourceFactory(new SourceRestClients(builder), List.of(new OaiDcMapper()));
    }

    private HarvestSource source(final Map<String, String> options, final SourceCredentials credentials) {
        return factory.create(Fixtures.settings("oai-pmh", BASE, 100, credentials, options));
    }

    private static MediaType xml() {
        return MediaType.TEXT_XML;
    }

    @Test
    void harvestsRecordsPageByPageWithResumptionTokens() {
        server.expect(requestTo(BASE + "?verb=ListRecords&metadataPrefix=oai_dc&set=physics"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer secret"))
            .andRespond(withSuccess(Fixtures.bytes("oai/list-page1.xml"), xml()));
        server.expect(requestTo(BASE + "?verb=ListRecords&resumptionToken=token+1%2F2"))
            .andRespond(withSuccess(Fixtures.bytes("oai/list-page2.xml"), xml()));
        final HarvestSource source = source(Map.of("set", "physics"), new SourceCredentials(null, null, "secret"));

        final HarvestPage first = source.fetch(HarvestWindow.ALL, null);
        final HarvestPage second = source.fetch(HarvestWindow.ALL, first.nextCursor());

        assertThat(source.capabilities().incremental()).isTrue();
        assertThat(source.capabilities().deletions()).isTrue();
        assertThat(first.responseTime()).isEqualTo(Instant.parse("2026-11-06T12:00:01Z"));
        assertThat(first.nextCursor()).isEqualTo("token 1/2");
        assertThat(first.records()).hasSize(2);

        final HarvestedRecord item = first.records().get(0);
        assertThat(item.identifier()).isEqualTo("oai:repo.test:item/1");
        assertThat(item.subject()).isEqualTo("oai:repo.test:item/1");
        assertThat(item.datestamp()).isEqualTo(Instant.parse("2026-10-01T08:30:00Z"));
        assertThat(item.sets()).containsExactly("physics", "physics:optics");
        final Resource subject = item.metadata().getResource(item.subject());
        assertThat(subject.listProperties(item.metadata().createProperty(DCTERMS + "title")).toList())
            .extracting(statement -> statement.getLiteral().getLexicalForm() + "@" + statement.getLiteral().getLanguage())
            .containsExactlyInAnyOrder("Light in fibres@en", "La lumière dans les fibres@fr");
        final Literal creator = subject.getProperty(item.metadata().createProperty(DCTERMS + "creator")).getLiteral();
        assertThat(creator.getLexicalForm()).isEqualTo("Doe, Jane");
        assertThat(creator.getLanguage()).isEmpty();
        assertThat(item.metadata().contains(subject, item.metadata().createProperty(DCTERMS + "subject"))).isFalse();
        assertThat(item.metadata().size()).isEqualTo(5);

        final HarvestedRecord deleted = first.records().get(1);
        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.identifier()).isEqualTo("oai:repo.test:item/2");

        assertThat(second.hasNext()).isFalse();
        assertThat(second.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.identifier()).isEqualTo("oai:repo.test:item/3");
            assertThat(failure.reason()).contains("oai_dc:dc");
        });
        assertThat(second.records()).singleElement().satisfies(record -> {
            assertThat(record.identifier()).isEqualTo("not a uri");
            assertThat(record.subject()).isEqualTo("urn:articulorium:harvest:test:not+a+uri");
        });
        server.verify();
    }

    @Test
    void incrementalHarvestsSendFromAtTheProvidersGranularity() {
        server.expect(requestTo(BASE + "?verb=Identify"))
            .andRespond(withSuccess(Fixtures.bytes("oai/identify.xml"), xml()));
        server.expect(requestTo(BASE + "?verb=ListRecords&metadataPrefix=oai_dc&from=2026-11-01T10%3A15%3A30Z"))
            .andRespond(withSuccess(Fixtures.bytes("oai/no-records.xml"), xml()));
        server.expect(requestTo(BASE + "?verb=ListRecords&metadataPrefix=oai_dc&from=2026-11-02T00%3A00%3A00Z"))
            .andRespond(withSuccess(Fixtures.bytes("oai/no-records.xml"), xml()));
        final HarvestSource source = source(Map.of(), null);

        final HarvestPage page = source.fetch(new HarvestWindow(Instant.parse("2026-11-01T10:15:30.789Z")), null);
        // Identify is asked once
        source.fetch(new HarvestWindow(Instant.parse("2026-11-02T00:00:00Z")), null);

        assertThat(page.records()).isEmpty();
        assertThat(page.hasNext()).isFalse();
        assertThat(page.responseTime()).isEqualTo(Instant.parse("2026-11-06T12:00:03Z"));
        server.verify();
    }

    @Test
    void configuredDayGranularityRoundsDown() {
        server.expect(requestTo(BASE + "?verb=ListRecords&metadataPrefix=oai_dc&from=2026-11-01"))
            .andRespond(withSuccess(Fixtures.bytes("oai/no-records.xml"), xml()));

        source(Map.of("granularity", "day"), null).fetch(new HarvestWindow(Instant.parse("2026-11-01T23:59:59Z")), null);

        server.verify();
    }

    @Test
    void expiredResumptionTokensFailTheHarvest() {
        server.expect(requestTo(BASE + "?verb=ListRecords&resumptionToken=old"))
            .andRespond(withSuccess(Fixtures.bytes("oai/bad-token.xml"), xml()));

        assertThatThrownBy(() -> source(Map.of(), null).fetch(HarvestWindow.ALL, "old"))
            .isInstanceOfSatisfying(HarvestException.class, e -> {
                assertThat(e.isRecoverable()).isFalse();
                assertThat(e.getMessage()).contains("resumption token", "expired");
            });
    }

    @Test
    void unavailableProvidersAreRetriedAfterTheirRetryAfter() {
        final HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "120");
        server.expect(requestTo(BASE + "?verb=ListRecords&metadataPrefix=oai_dc"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).headers(headers));

        assertThatThrownBy(() -> source(Map.of(), null).fetch(HarvestWindow.ALL, null))
            .isInstanceOfSatisfying(HarvestException.class, e -> {
                assertThat(e.isRecoverable()).isTrue();
                assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(120));
                assertThat(e.getMessage()).contains("503");
            });
    }

    @Test
    void refusedCredentialsAreNotRetried() {
        server.expect(requestTo(BASE + "?verb=ListRecords&metadataPrefix=oai_dc"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("go away"));

        assertThatThrownBy(() -> source(Map.of(), new SourceCredentials("u", "hunter2", null)).fetch(HarvestWindow.ALL, null))
            .isInstanceOfSatisfying(HarvestException.class, e -> {
                assertThat(e.isRecoverable()).isFalse();
                assertThat(e.getMessage()).contains("401", "go away").doesNotContain("hunter2");
            });
    }

    @Test
    void documentTypeDeclarationsAreRejected() {
        server.expect(requestTo(BASE + "?verb=ListRecords&metadataPrefix=oai_dc"))
            .andRespond(withSuccess(Fixtures.bytes("oai/doctype.xml"), xml()));

        assertThatThrownBy(() -> source(Map.of(), null).fetch(HarvestWindow.ALL, null))
            .isInstanceOfSatisfying(HarvestException.class, e -> assertThat(e.getMessage()).contains("malformed XML"));
    }

    @Test
    void rejectsUnknownOptionsFormatsAndGranularities() {
        assertThatThrownBy(() -> source(Map.of("sett", "x"), null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sett");
        assertThatThrownBy(() -> source(Map.of("metadata-prefix", "mods"), null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("oai_dc");
        assertThatThrownBy(() -> source(Map.of("granularity", "hour"), null))
            .isInstanceOf(IllegalArgumentException.class);
    }

}
