package com.articulorium.harvest.source.fedora;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import com.articulorium.harvest.core.HarvestException;
import com.articulorium.harvest.core.HarvestPage;
import com.articulorium.harvest.core.HarvestSource;
import com.articulorium.harvest.core.HarvestWindow;
import com.articulorium.harvest.core.HarvestedFile;
import com.articulorium.harvest.core.HarvestedRecord;
import com.articulorium.harvest.core.SourceCredentials;
import com.articulorium.harvest.source.Fixtures;
import com.articulorium.harvest.source.KeysetCursor;
import com.articulorium.harvest.source.SourceRestClients;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Tests the Fedora source against recorded responses of the search and LDP APIs.
 */
class FedoraSourceTest {

    private static final String BASE = "http://fedora.test/rest/";

    private static final String SEARCH = BASE + "fcr:search?order_by=modified&order=asc&max_results=";

    private static final String BELOW_BOOKS = "&condition=fedora_id%3Dinfo%3Afedora%2Fbooks%2F*";

    private static final MediaType N_TRIPLES = MediaType.parseMediaType("application/n-triples");

    private MockRestServiceServer server;

    private FedoraSourceFactory factory;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        factory = new FedoraSourceFactory(new SourceRestClients(builder));
    }

    private HarvestSource source(final Map<String, String> options) {
        return factory.create(Fixtures.settings("fedora", "http://fedora.test/rest", 3,
            new SourceCredentials("fedoraAdmin", "fedoraAdmin", null), options));
    }

    @Test
    void harvestsRdfSourcesAndBinariesBelowTheRoot() {
        server.expect(requestTo(SEARCH + "3&offset=0" + BELOW_BOOKS))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic ZmVkb3JhQWRtaW46ZmVkb3JhQWRtaW4="))
            .andRespond(withSuccess(Fixtures.bytes("fedora/search-page1.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "books/moby"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.ACCEPT, "application/n-triples"))
            .andExpect(header("Prefer", "return=representation; omit=\"http://fedora.info/definitions/v4/repository#"
                + "ServerManaged http://www.w3.org/ns/ldp#PreferContainment http://www.w3.org/ns/ldp#PreferMembership\""))
            .andRespond(withSuccess(Fixtures.bytes("fedora/moby.nt"), N_TRIPLES));
        final HttpHeaders binary = new HttpHeaders();
        binary.add(HttpHeaders.LINK, "<http://www.w3.org/ns/ldp#NonRDFSource>; rel=\"type\"");
        binary.add(HttpHeaders.LINK, "<cover.png/fcr:metadata>; rel=\"describedby\"");
        binary.setContentType(MediaType.IMAGE_PNG);
        server.expect(requestTo(BASE + "books/moby/cover.png"))
            .andExpect(method(HttpMethod.HEAD))
            .andRespond(withSuccess().headers(binary));
        server.expect(requestTo(BASE + "books/moby/cover.png/fcr:metadata"))
            .andRespond(withSuccess(Fixtures.bytes("fedora/cover-description.nt"), N_TRIPLES));

        final HarvestPage page = source(Map.of("root", "/books/")).fetch(HarvestWindow.ALL, null);

        assertThat(page.records()).hasSize(2);
        final HarvestedRecord moby = page.records().get(0);
        assertThat(moby.identifier()).isEqualTo("books/moby");
        assertThat(moby.subject()).isEqualTo(BASE + "books/moby");
        assertThat(moby.datestamp()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
        assertThat(moby.metadata().size()).isEqualTo(4);
        final HarvestedRecord cover = page.records().get(1);
        assertThat(cover.identifier()).isEqualTo("books/moby/cover.png");
        assertThat(cover.subject()).isEqualTo(BASE + "books/moby/cover.png/fcr:metadata");
        assertThat(cover.files()).containsExactly(
            new HarvestedFile(URI.create(BASE + "books/moby/cover.png"), "cover.png", "image/png", 2048L));
        // the title describes the record; the technical metadata stays with the file
        final Model metadata = cover.metadata();
        assertThat(metadata.contains(metadata.createResource(cover.subject()),
            metadata.createProperty("http://purl.org/dc/terms/title"), "Cover")).isTrue();
        assertThat(metadata.listStatements(metadata.createResource(BASE + "books/moby/cover.png"), null,
            (RDFNode) null).toList()).hasSize(3);
        // the repository root is not a record; the cursor skips the one resource read at the last time
        assertThat(KeysetCursor.decode(page.nextCursor()))
            .isEqualTo(new KeysetCursor(Instant.parse("2026-10-01T11:00:00Z"), 1, Set.of()));
        server.verify();
    }

    @Test
    void continuesFromTheLastModificationTimeAndTreatsVanishedResourcesAsDeleted() {
        final String cursor = new KeysetCursor(Instant.parse("2026-10-01T11:00:00Z"), 2, Set.of()).encode();
        server.expect(requestTo(SEARCH + "5&offset=2" + BELOW_BOOKS + "&condition=modified%3E%3D2026-10-01T11%3A00%3A00Z"))
            .andRespond(withSuccess(Fixtures.bytes("fedora/search-page2.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "books/gone")).andRespond(withStatus(HttpStatus.GONE));

        final HarvestPage page = source(Map.of("root", "books")).fetch(HarvestWindow.ALL, cursor);

        assertThat(page.records()).singleElement().satisfies(record -> {
            assertThat(record.identifier()).isEqualTo("books/gone");
            assertThat(record.deleted()).isTrue();
            assertThat(record.datestamp()).isEqualTo(Instant.parse("2026-10-03T11:00:00.5Z"));
        });
        // the description listed by the search is not a record of its own
        assertThat(page.failures()).singleElement().satisfies(failure ->
            assertThat(failure.reason()).contains("outside"));
        // offset 2 + 3 results reach the 3 matching resources
        assertThat(page.hasNext()).isFalse();
        server.verify();
    }

    @Test
    void incrementalHarvestsFilterOnTheModificationTimeAndType() {
        server.expect(requestTo(SEARCH + "3&offset=0&condition=modified%3E%3D2026-10-05T00%3A00%3A00Z"
                + "&condition=rdf_type%3Dhttp%3A%2F%2Fpcdm.org%2Fmodels%23Object"))
            .andRespond(withSuccess(Fixtures.bytes("fedora/search-forbidden.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "books/secret")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        final HarvestPage page = source(Map.of("rdf-type", "http://pcdm.org/models#Object"))
            .fetch(new HarvestWindow(Instant.parse("2026-10-05T00:00:00Z")), null);

        assertThat(page.records()).isEmpty();
        assertThat(page.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.identifier()).isEqualTo("books/secret");
            assertThat(failure.reason()).contains("403");
        });
        server.verify();
    }

    @Test
    void skipsBinariesWhenAsked() {
        server.expect(requestTo(SEARCH + "3&offset=0"))
            .andRespond(withSuccess(Fixtures.bytes("fedora/search-page1.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "books/moby"))
            .andRespond(withSuccess(Fixtures.bytes("fedora/moby.nt"), N_TRIPLES));

        final HarvestPage page = source(Map.of("include-binaries", "false")).fetch(HarvestWindow.ALL, null);

        assertThat(page.records()).extracting(HarvestedRecord::identifier).containsExactly("books/moby");
        server.verify();
    }

    @Test
    void unreachableRepositoriesAreRecoverable() {
        server.expect(requestTo(SEARCH + "3&offset=0")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> source(Map.of()).fetch(HarvestWindow.ALL, null))
            .isInstanceOfSatisfying(HarvestException.class, e -> assertThat(e.isRecoverable()).isTrue());
    }

}
