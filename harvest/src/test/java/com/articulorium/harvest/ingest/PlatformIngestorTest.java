package com.articulorium.harvest.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withCreatedEntity;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.articulorium.domain.vocabulary.Articulorium;
import com.articulorium.domain.vocabulary.Ldp;
import com.articulorium.harvest.config.HarvestProperties;
import com.articulorium.harvest.core.HarvestException;
import com.articulorium.harvest.core.HarvestedFile;
import com.articulorium.harvest.core.HarvestedRecord;
import com.articulorium.harvest.core.IngestAction;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

/**
 * Tests ingest into a mocked platform and the checkpoint store.
 */
class PlatformIngestorTest {

    private static final String PLATFORM = "http://articulorium-platform/";

    private static final String DCTERMS = "http://purl.org/dc/terms/";

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");

    private MockRestServiceServer server;

    private PlatformIngestor ingestor;

    private PlatformCheckpointStore checkpoints;

    private final RecordPaths paths = new RecordPaths("/harvested/");

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        final HarvestProperties.PlatformAuth auth = new HarvestProperties.PlatformAuth();
        auth.setAuthorization("Bearer harvester");
        final PlatformClient client = new PlatformClient(builder.build(), "http://articulorium-platform",
            new PlatformAuthorization(auth, RestClient.create(), Clock.systemUTC()));
        ingestor = new PlatformIngestor(client, paths, Clock.fixed(NOW, ZoneOffset.UTC));
        checkpoints = new PlatformCheckpointStore(client, paths);
    }

    /** Parses the N-Triples body of a request and checks it. */
    private static RequestMatcher body(final java.util.function.Consumer<Model> check) {
        return request -> {
            final Model model = ModelFactory.createDefaultModel();
            RDFDataMgr.read(model, new StringReader(((MockClientHttpRequest) request)
                .getBodyAsString(StandardCharsets.UTF_8)), null, Lang.NTRIPLES);
            check.accept(model);
        };
    }

    @Test
    void preparesTheContainersWithoutReplacingExistingOnes() {
        server.expect(requestTo(PLATFORM + "harvested"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(header(HttpHeaders.IF_NONE_MATCH, "*"))
            .andExpect(header(HttpHeaders.LINK, "<" + Ldp.BASIC_CONTAINER + ">; rel=\"type\""))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer harvester"))
            .andExpect(header("X-Forwarded-Host", "articulorium-platform"))
            .andExpect(body(model -> assertThat(model.contains(model.createResource(PLATFORM + "harvested"), null,
                model.createResource(Articulorium.HARVEST_ROOT))).isTrue()))
            .andRespond(withStatus(HttpStatus.PRECONDITION_FAILED));
        server.expect(requestTo(PLATFORM + "harvested/arxiv"))
            .andExpect(header(HttpHeaders.IF_NONE_MATCH, "*"))
            .andExpect(body(model -> assertThat(model.contains(model.createResource(PLATFORM + "harvested/arxiv"),
                model.createProperty(Articulorium.HARVEST_SOURCE), "arxiv")).isTrue()))
            .andRespond(withCreatedEntity(URI.create(PLATFORM + "harvested/arxiv")));

        ingestor.begin("arxiv");

        server.verify();
    }

    @Test
    void toleratesAConflictWhenAnotherHarvestCreatedTheContainerAtTheSameTime() {
        server.expect(requestTo(PLATFORM + "harvested")).andExpect(method(HttpMethod.PUT))
            .andRespond(withStatus(HttpStatus.CONFLICT).body("A resource already exists at this location"));
        server.expect(requestTo(PLATFORM + "harvested")).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("", MediaType.parseMediaType("application/n-triples")));
        server.expect(requestTo(PLATFORM + "harvested/oai")).andExpect(method(HttpMethod.PUT))
            .andRespond(withStatus(HttpStatus.CONFLICT).body("Parent container /x does not exist"));
        server.expect(requestTo(PLATFORM + "harvested/oai")).andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> ingestor.begin("oai"))
            .isInstanceOfSatisfying(HarvestException.class, e -> assertThat(e.getMessage()).contains("Parent"));
        server.verify();
    }

    @Test
    void storesRecordsWithProvenanceUnderTheirOwnIri() {
        final String path = paths.record("dspace", "uuid-1");
        final String copy = PLATFORM + path;
        final Model metadata = ModelFactory.createDefaultModel();
        final Resource original = metadata.createResource("https://hdl.handle.net/1/1");
        original.addProperty(metadata.createProperty(DCTERMS + "title"), "Light", "en");
        original.addProperty(metadata.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            metadata.createResource(Ldp.CONTAINER));
        original.addProperty(metadata.createProperty(Ldp.CONTAINS), metadata.createResource("https://x.test/child"));
        original.addProperty(metadata.createProperty("http://fedora.info/definitions/v4/repository#created"), "x");
        metadata.createResource("https://x.test/position").addProperty(metadata.createProperty(
            "http://vivoweb.org/ontology/core#relates"), original);
        original.addProperty(metadata.createProperty(DCTERMS + "creator"), metadata.createResource()
            .addProperty(metadata.createProperty("http://xmlns.com/foaf/0.1/name"), "Doe"));
        final HarvestedRecord record = new HarvestedRecord("uuid-1", "https://hdl.handle.net/1/1",
            Instant.parse("2026-10-01T10:00:00Z"), false, List.of("coll-1"), metadata,
            List.of(new HarvestedFile(URI.create("https://dspace.test/bitstreams/1/content"), "a.pdf",
                "application/pdf", 12L)));
        server.expect(requestTo(copy))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(headerDoesNotExist(HttpHeaders.IF_NONE_MATCH))
            .andExpect(content().contentType("application/n-triples"))
            .andExpect(body(model -> {
                final Resource self = model.createResource(copy);
                assertThat(model.contains(self, model.createProperty(DCTERMS + "title"), model.createLiteral("Light", "en")))
                    .isTrue();
                assertThat(model.contains(self, model.createProperty("http://www.w3.org/2000/01/rdf-schema#label"),
                    model.createLiteral("Light", "en"))).isTrue();
                assertThat(model.contains(model.createResource("https://x.test/position"), null, self)).isTrue();
                assertThat(model.contains(self, model.createProperty(PlatformIngestor.PROV_WAS_DERIVED_FROM),
                    model.createResource("https://hdl.handle.net/1/1"))).isTrue();
                assertThat(model.contains(self, model.createProperty(Articulorium.HARVEST_IDENTIFIER), "uuid-1")).isTrue();
                assertThat(model.contains(self, model.createProperty(Articulorium.HARVEST_SOURCE), "dspace")).isTrue();
                assertThat(model.contains(self, model.createProperty(Articulorium.HARVEST_SET), "coll-1")).isTrue();
                assertThat(model.contains(self, model.createProperty(Articulorium.HARVESTED_AT),
                    model.createTypedLiteral(NOW.toString(), "http://www.w3.org/2001/XMLSchema#dateTime"))).isTrue();
                assertThat(model.contains(self, model.createProperty(Articulorium.HARVEST_FILE),
                    model.createResource("https://dspace.test/bitstreams/1/content"))).isTrue();
                assertThat(model.listSubjectsWithProperty(model.createProperty("http://xmlns.com/foaf/0.1/name")).toList())
                    .singleElement().satisfies(creator -> assertThat(creator.isAnon()).isTrue());
                // nothing about the original subject is left, and no server-managed statements
                assertThat(model.containsResource(model.createResource("https://hdl.handle.net/1/1"))).isTrue();
                assertThat(model.listStatements(model.createResource("https://hdl.handle.net/1/1"), null,
                    (org.apache.jena.rdf.model.RDFNode) null).toList()).isEmpty();
                assertThat(model.listStatements().toList()).noneMatch(statement ->
                    statement.getPredicate().getURI().startsWith(Ldp.NAMESPACE)
                        || statement.getPredicate().getURI().startsWith("http://fedora.info/")
                        || statement.getObject().isURIResource()
                            && statement.getObject().asResource().getURI().startsWith(Ldp.NAMESPACE));
            }))
            .andRespond(withCreatedEntity(URI.create(copy)));
        server.expect(requestTo(copy)).andRespond(withNoContent());

        assertThat(ingestor.ingest("dspace", record)).isEqualTo(IngestAction.CREATED);
        assertThat(ingestor.ingest("dspace", record)).isEqualTo(IngestAction.UPDATED);
        server.verify();
    }

    @Test
    void deletesTheCopiesOfDeletedRecords() {
        final String path = paths.record("oai", "oai:x:1");
        server.expect(requestTo(PLATFORM + path)).andExpect(method(HttpMethod.DELETE)).andRespond(withNoContent());
        server.expect(requestTo(PLATFORM + path)).andExpect(method(HttpMethod.DELETE))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(ingestor.ingest("oai", HarvestedRecord.deleted("oai:x:1", "oai:x:1", null)))
            .isEqualTo(IngestAction.DELETED);
        assertThat(ingestor.ingest("oai", HarvestedRecord.deleted("oai:x:1", "oai:x:1", null)))
            .isEqualTo(IngestAction.SKIPPED);
        server.verify();
    }

    @Test
    void classifiesPlatformFailures() {
        final String path = paths.record("oai", "a");
        server.expect(requestTo(PLATFORM + path)).andRespond(withStatus(HttpStatus.CONFLICT).body("Parent missing"));
        server.expect(requestTo(PLATFORM + path)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(PLATFORM + path)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        final HarvestedRecord record = HarvestedRecord.of("a", "oai:x:a", null, null);

        assertThatThrownBy(() -> ingestor.ingest("oai", record))
            .isInstanceOfSatisfying(HarvestException.class, e -> {
                assertThat(e.isRecoverable()).isFalse();
                assertThat(e.getMessage()).contains("409", "Parent missing");
            });
        assertThatThrownBy(() -> ingestor.ingest("oai", record))
            .isInstanceOfSatisfying(HarvestException.class, e -> assertThat(e.isRecoverable()).isTrue());
        assertThatThrownBy(() -> ingestor.ingest("oai", record))
            .isInstanceOfSatisfying(HarvestException.class, e -> assertThat(e.isRecoverable()).isTrue());
    }

    @Test
    void keepsCheckpointsOnTheSourceContainer() {
        server.expect(requestTo(PLATFORM + "harvested/arxiv"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(PLATFORM + "harvested/arxiv"))
            .andRespond(withSuccess("<" + PLATFORM + "harvested/arxiv> <" + Articulorium.HARVEST_CHECKPOINT
                + "> \"2026-11-01T00:00:00Z\"^^<http://www.w3.org/2001/XMLSchema#dateTime> .\n",
                MediaType.parseMediaType("application/n-triples")));
        server.expect(requestTo(PLATFORM + "harvested/arxiv"))
            .andExpect(method(HttpMethod.PATCH))
            .andExpect(content().contentType("application/sparql-update"))
            .andExpect(content().string(org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.containsString("DELETE WHERE { <> <" + Articulorium.HARVEST_CHECKPOINT + ">"),
                org.hamcrest.Matchers.containsString("\"2026-11-06T11:55:00Z\"^^"))))
            .andRespond(withNoContent());

        assertThat(checkpoints.load("arxiv")).isEmpty();
        assertThat(checkpoints.load("arxiv")).isEqualTo(Optional.of(Instant.parse("2026-11-01T00:00:00Z")));
        checkpoints.save("arxiv", Instant.parse("2026-11-06T11:55:00Z"));
        server.verify();
    }

    @Test
    void slugsAreStableReadableAndDistinct() {
        assertThat(RecordPaths.slug("oai:arXiv.org:2401.00001")).startsWith("oai-arxiv-org-2401-00001-").hasSize(37);
        assertThat(RecordPaths.slug("oai:arXiv.org:2401.00001")).isEqualTo(RecordPaths.slug("oai:arXiv.org:2401.00001"));
        assertThat(RecordPaths.slug("A/B")).isNotEqualTo(RecordPaths.slug("a-b")).startsWith("a-b-");
        assertThat(RecordPaths.slug("Café")).startsWith("cafe-");
        assertThat(RecordPaths.slug("://")).matches("[0-9a-f]{12}");
        assertThat(RecordPaths.slug("https://vivo.test/individual/" + "x".repeat(200)))
            .hasSize(60 + 1 + 12).doesNotStartWith("-");
        assertThat(paths.record("s", "id")).startsWith("harvested/s/id-");
        assertThatThrownBy(() -> new RecordPaths("/")).isInstanceOf(IllegalArgumentException.class);
    }

}
