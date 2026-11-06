package com.articulorium.harvest.source.dspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Instant;
import java.util.List;
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
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Tests the DSpace source against recorded REST API responses.
 */
class DSpaceSourceTest {

    private static final String API = "https://dspace.test/server/api/";

    private static final String SEARCH = API + "discover/search/objects?dsoType=ITEM&sort=lastModified%2CASC";

    private static final String DCTERMS = "http://purl.org/dc/terms/";

    private static final MediaType HAL = MediaType.parseMediaType("application/hal+json");

    private MockRestServiceServer server;

    private DSpaceSourceFactory factory;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        factory = new DSpaceSourceFactory(new SourceRestClients(builder));
    }

    private HarvestSource source(final Map<String, String> options) {
        return factory.create(Fixtures.settings("dspace", "https://dspace.test/server/api", 2,
            new SourceCredentials(null, null, "jwt"), options));
    }

    @Test
    void mapsItemsAndPagesOnTheModificationTime() {
        server.expect(requestTo(SEARCH + "&page=0&size=2"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer jwt"))
            .andRespond(withSuccess(Fixtures.bytes("dspace/search-page1.json"), HAL));
        server.expect(requestTo(SEARCH + "&page=0&size=2&query=lastModified%3A%5B2026-10-02T10%3A00%3A00Z+TO+*%5D"))
            .andRespond(withSuccess(Fixtures.bytes("dspace/search-page2.json"), HAL));
        final HarvestSource source = source(Map.of());

        final HarvestPage first = source.fetch(HarvestWindow.ALL, null);
        final HarvestPage second = source.fetch(HarvestWindow.ALL, first.nextCursor());

        assertThat(source.capabilities().incremental()).isTrue();
        assertThat(source.capabilities().deletions()).isFalse();
        assertThat(first.records()).extracting(HarvestedRecord::identifier).containsExactly("uuid-1", "uuid-2");
        final HarvestedRecord item = first.records().get(0);
        assertThat(item.subject()).isEqualTo("https://hdl.handle.net/123456789/1");
        assertThat(item.datestamp()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
        final Model model = item.metadata();
        final Resource subject = model.getResource(item.subject());
        assertThat(subject.getProperty(model.createProperty(DCTERMS + "title")).getLiteral().getLanguage())
            .isEqualTo("en-US");
        assertThat(subject.listProperties(model.createProperty(DCTERMS + "creator")).toList())
            .extracting(statement -> statement.getString()).containsExactlyInAnyOrder("Doe, Jane", "Roe, Richard");
        assertThat(subject.getProperty(model.createProperty(DCTERMS + "issued")).getString()).isEqualTo("2024-05");
        assertThat(subject.getProperty(model.createProperty(DCTERMS + "license")).getResource().getURI())
            .isEqualTo("https://creativecommons.org/licenses/by/4.0/");
        // dc.description.provenance maps to dcterms:description; other schemas are left out
        assertThat(model.size()).isEqualTo(6);
        assertThat(first.records().get(1).subject()).isEqualTo(API + "core/items/uuid-2");

        // uuid-2 was read at the boundary time and is not repeated; the withdrawn item is deleted
        assertThat(second.records()).singleElement().satisfies(record -> {
            assertThat(record.identifier()).isEqualTo("uuid-3");
            assertThat(record.deleted()).isTrue();
            assertThat(record.subject()).isEqualTo("https://hdl.handle.net/123456789/3");
        });
        assertThat(second.hasNext()).isTrue();
        assertThat(KeysetCursor.decode(second.nextCursor()))
            .isEqualTo(new KeysetCursor(Instant.parse("2026-10-03T10:00:00Z"), 1, Set.of("uuid-3")));
        server.verify();
    }

    @Test
    void pagesByPageNumberWhileItemsShareATimeAndStopsOnTheLastPage() {
        final Instant time = Instant.parse("2026-10-04T10:00:00Z");
        server.expect(requestTo(SEARCH + "&page=0&size=2&scope=coll-1&query=lastModified%3A%5B2026-10-04T10%3A00%3A00Z+TO+*%5D"))
            .andRespond(withSuccess(Fixtures.bytes("dspace/search-same-time.json"), HAL));
        server.expect(requestTo(SEARCH + "&page=1&size=2&scope=coll-1&query=lastModified%3A%5B2026-10-04T10%3A00%3A00Z+TO+*%5D"))
            .andRespond(withSuccess(Fixtures.bytes("dspace/search-last.json"), HAL));
        final HarvestSource source = source(Map.of("scope", "coll-1"));

        final HarvestPage same = source.fetch(new HarvestWindow(time), null);
        final HarvestPage last = source.fetch(new HarvestWindow(time), same.nextCursor());

        assertThat(same.records()).extracting(HarvestedRecord::identifier).containsExactly("uuid-4", "uuid-5");
        assertThat(same.records().get(0).sets()).containsExactly("coll-1");
        assertThat(KeysetCursor.decode(same.nextCursor())).isEqualTo(new KeysetCursor(time, 2, Set.of("uuid-4", "uuid-5")));
        assertThat(last.records()).extracting(HarvestedRecord::identifier).containsExactly("uuid-6");
        assertThat(last.records().get(0).datestamp()).isEqualTo(Instant.parse("2026-10-05T10:00:00Z"));
        assertThat(last.hasNext()).isFalse();
        server.verify();
    }

    @Test
    void referencesTheBitstreamsOfTheOriginalBundle() {
        server.expect(requestTo(SEARCH + "&page=0&size=2"))
            .andRespond(withSuccess(Fixtures.bytes("dspace/search-last.json"), HAL));
        server.expect(requestTo(API + "core/items/uuid-6/bundles?size=100&embed=bitstreams/format"))
            .andRespond(withSuccess(Fixtures.bytes("dspace/bundles.json"), HAL));

        final HarvestPage page = source(Map.of("include-files", "true")).fetch(HarvestWindow.ALL, null);

        assertThat(page.records().get(0).files()).containsExactly(
            new HarvestedFile(URI.create(API + "core/bitstreams/bs-1/content"), "article.pdf", "application/pdf", 12345L),
            new HarvestedFile(URI.create(API + "core/bitstreams/bs-2/content"), "data.csv", null, null));
        server.verify();
    }

    @Test
    void reportsItemsWhoseFilesCannotBeReadAndItemsWithoutIdentifier() {
        server.expect(requestTo(SEARCH + "&page=0&size=2"))
            .andRespond(withSuccess(Fixtures.bytes("dspace/search-last.json"), HAL));
        server.expect(requestTo(API + "core/items/uuid-6/bundles?size=100&embed=bitstreams/format"))
            .andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(requestTo(SEARCH + "&page=0&size=2"))
            .andRespond(withSuccess(Fixtures.bytes("dspace/search-no-uuid.json"), HAL));
        final HarvestSource source = source(Map.of("include-files", "true"));

        final HarvestPage forbidden = source.fetch(HarvestWindow.ALL, null);
        final HarvestPage anonymous = source.fetch(HarvestWindow.ALL, null);

        assertThat(forbidden.records()).isEmpty();
        assertThat(forbidden.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.identifier()).isEqualTo("uuid-6");
            assertThat(failure.reason()).contains("403");
        });
        assertThat(anonymous.failures()).singleElement().satisfies(failure ->
            assertThat(failure.reason()).contains("without uuid"));
    }

    @Test
    void refusesListingsNotOrderedByModificationTime() {
        server.expect(requestTo(SEARCH + "&page=0&size=2"))
            .andRespond(withSuccess(Fixtures.bytes("dspace/search-unordered.json"), HAL));

        assertThatThrownBy(() -> source(Map.of()).fetch(HarvestWindow.ALL, null))
            .isInstanceOfSatisfying(HarvestException.class, e -> {
                assertThat(e.isRecoverable()).isFalse();
                assertThat(e.getMessage()).contains("sort-field");
            });
    }

    @Test
    void serverErrorsAreRecoverable() {
        server.expect(requestTo(SEARCH + "&page=0&size=2")).andRespond(withServerError());

        assertThatThrownBy(() -> source(Map.of()).fetch(HarvestWindow.ALL, null))
            .isInstanceOfSatisfying(HarvestException.class, e -> assertThat(e.isRecoverable()).isTrue());
    }

    @Test
    void validatesOptions() {
        assertThatThrownBy(() -> source(Map.of("incremental-query", "lastModified:[* TO *]")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("{from}");
        assertThatThrownBy(() -> source(Map.of("include-files", "maybe")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source(Map.of("collection", "x")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scope");
        assertThat(List.of(source(Map.of("handle-resolver", "https://handle.test")))).hasSize(1);
    }

}
