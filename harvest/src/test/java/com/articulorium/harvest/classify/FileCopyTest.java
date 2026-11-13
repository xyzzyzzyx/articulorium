package com.articulorium.harvest.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.articulorium.domain.vocabulary.Ldp;
import com.articulorium.domain.vocabulary.Pcdm;
import com.articulorium.harvest.config.HarvestProperties;
import com.articulorium.harvest.ingest.FileCopier;
import com.articulorium.harvest.ingest.PlatformClient;

import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Tests copying the files of placed records into their items, against the in-memory platform and a
 * mocked source.
 */
class FileCopyTest {

    private static final String ITEM = "communities/science/theses/thesis-1";

    private static final byte[] PDF = "%PDF-1.4 thesis".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private final FakePlatform platform = new FakePlatform();

    private final HarvestProperties properties = new HarvestProperties();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final List<Duration> pauses = new ArrayList<>();

    private MockRestServiceServer source;

    private FileCopier copier;

    private PlatformClient harvester;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        source = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        final HarvestProperties.Source repo = new HarvestProperties.Source();
        repo.setUrl("https://repo.test/oai");
        repo.setCopyFiles(true);
        repo.getOptions().put("file-url", "https://Files.repo.test/pdf/{1}");
        properties.getSources().put("repo", repo);
        final HarvestProperties.Source referenced = new HarvestProperties.Source();
        referenced.setUrl("https://repo.test/oai");
        properties.getSources().put("referenced", referenced);
        properties.getFiles().setDelay(Duration.ofSeconds(3));
        copier = new FileCopier(builder.build(), properties.getFiles(), id -> FileCopier.hostsOf(properties, id),
            pauses::add, meters);
        harvester = platform.client("Bearer harvester");
    }

    /** An item referencing files: "location, name, media type" each ({@code -} for none). */
    private void item(final String... files) {
        final StringBuilder triples = new StringBuilder("<> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <"
            + Pcdm.OBJECT + "> .\n");
        for (final String file : files) {
            final String[] parts = file.split(", ");
            triples.append("<> <https://articulorium.org/ns/repository#harvestFile> <").append(parts[0]).append("> .\n");
            if (!"-".equals(parts[1])) {
                triples.append('<').append(parts[0])
                    .append("> <http://www.ebu.ch/metadata/ontologies/ebucore/ebucore#filename> \"").append(parts[1])
                    .append("\" .\n");
            }
            if (!"-".equals(parts[2])) {
                triples.append('<').append(parts[0])
                    .append("> <http://www.ebu.ch/metadata/ontologies/ebucore/ebucore#hasMimeType> \"").append(parts[2])
                    .append("\" .\n");
            }
        }
        platform.put(ITEM, triples.toString());
    }

    private double count(final String outcome) {
        return meters.counter("harvest.files", "source", "repo", "outcome", outcome).count();
    }

    @Test
    void copiesAFileIntoTheItemsFilesContainer() {
        item("https://files.repo.test/pdf/2611.17233, 2611.17233.pdf, application/pdf");
        source.expect(ExpectedCount.once(), requestTo("https://files.repo.test/pdf/2611.17233"))
            .andRespond(withSuccess(PDF, MediaType.APPLICATION_PDF));

        assertThat(copier.copy(harvester, "repo", ITEM)).isEqualTo(1);

        // the container makes its files pcdm:hasFile of the item, as the topology service's does
        final Model container = platform.get(ITEM + "/files");
        assertThat(container.contains(container.createResource(FakePlatform.BASE + ITEM + "/files"),
            container.createProperty(Ldp.MEMBERSHIP_RESOURCE), container.createResource(FakePlatform.BASE + ITEM))).isTrue();
        assertThat(container.contains(container.createResource(FakePlatform.BASE + ITEM + "/files"),
            container.createProperty(Ldp.HAS_MEMBER_RELATION), container.createResource(Pcdm.HAS_FILE))).isTrue();
        assertThat(platform.binaries()).hasSize(1).allSatisfy((path, file) -> {
            assertThat(path).matches(ITEM + "/files/2611-17233-[0-9a-f]{8}\\.pdf");
            assertThat(file).isEqualTo("application/pdf " + PDF.length + " 2611.17233.pdf");
        });
        assertThat(platform.requests()).anyMatch(request -> request.startsWith("POST " + FakePlatform.BASE + ITEM
            + "/files Bearer harvester"));
        assertThat(count("copied")).isEqualTo(1);
        source.verify();
    }

    @Test
    void copiesAFileOnceAndWaitsBetweenDownloads() {
        item("https://files.repo.test/pdf/1, 1.pdf, application/pdf", "https://repo.test/bitstream/cover.JPG, -, -");
        source.expect(ExpectedCount.once(), requestTo("https://files.repo.test/pdf/1"))
            .andRespond(withSuccess(PDF, MediaType.APPLICATION_PDF));
        source.expect(ExpectedCount.once(), requestTo("https://repo.test/bitstream/cover.JPG"))
            .andRespond(withSuccess(new byte[] {1, 2, 3}, MediaType.IMAGE_JPEG));

        assertThat(copier.copy(harvester, "repo", ITEM)).isEqualTo(2);
        assertThat(copier.copy(harvester, "repo", ITEM)).as("harvested again: nothing to copy").isZero();

        // the media type the source serves, when the record names none; the name from the address
        assertThat(platform.binaries().values()).hasSize(2).contains("application/pdf " + PDF.length + " 1.pdf")
            .anyMatch(file -> file.matches("image/jpeg 3 cover-[0-9a-f]{8}\\.jpg"));
        assertThat(pauses).as("one pause, before the second download").containsExactly(Duration.ofSeconds(3));
        source.verify();
    }

    @Test
    void leavesFilesItMayNotOrCannotCopyReferenced() {
        properties.getFiles().setMaxSize(DataSize.ofBytes(10));
        properties.getFiles().setMaxPerRecord(10);
        properties.getFiles().setMediaTypes(List.of("application/pdf", "image/*"));
        item("https://files.repo.test/pdf/large, large.pdf, application/pdf",
            "https://files.repo.test/pdf/missing, missing.pdf, application/pdf",
            "https://files.repo.test/pdf/page, page.pdf, application/pdf",
            "https://files.repo.test/data.zip, data.zip, application/zip",
            "http://platform:9000/harvested, internal.pdf, application/pdf",
            "file:///etc/passwd, passwd, application/pdf");
        source.expect(requestTo("https://files.repo.test/pdf/large"))
            .andRespond(withSuccess(PDF, MediaType.APPLICATION_PDF));
        source.expect(requestTo("https://files.repo.test/pdf/missing")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        source.expect(requestTo("https://files.repo.test/pdf/page"))
            .andRespond(withSuccess("<html>".getBytes(java.nio.charset.StandardCharsets.UTF_8), MediaType.TEXT_HTML));

        assertThat(copier.copy(harvester, "repo", ITEM)).isZero();

        assertThat(platform.binaries()).isEmpty();
        assertThat(platform.exists(ITEM + "/files")).as("no container without a file").isFalse();
        assertThat(count("too-large")).isEqualTo(1);
        assertThat(count("failed")).as("missing, and a web page instead of the PDF").isEqualTo(2);
        assertThat(count("skipped")).as("a media type that is not copied").isEqualTo(1);
        assertThat(count("refused")).as("hosts the source does not name").isEqualTo(2);
        // only the source's own hosts were asked
        source.verify();
    }

    @Test
    void copiesNothingForSourcesThatOnlyReferenceTheirFiles() {
        item("https://repo.test/bitstream/1.pdf, 1.pdf, application/pdf");

        assertThat(copier.copy(harvester, "referenced", ITEM)).isZero();
        assertThat(copier.copy(harvester, "unknown", ITEM)).isZero();

        assertThat(FileCopier.hostsOf(properties, "referenced")).isEmpty();
        assertThat(FileCopier.hostsOf(properties, "repo")).isEqualTo(Set.of("repo.test", "files.repo.test"));
        assertThat(platform.binaries()).isEmpty();
        source.verify();
    }

    @Test
    void namesFilesAfterWhatTheyAre() {
        item("https://files.repo.test/pdf/2611.17233, -, application/pdf",
            "https://files.repo.test/Final%20Slides.PPTX, -, -");
        source.expect(requestTo("https://files.repo.test/pdf/2611.17233"))
            .andRespond(withSuccess(PDF, MediaType.APPLICATION_PDF));
        source.expect(requestTo("https://files.repo.test/Final%20Slides.PPTX"))
            .andRespond(withSuccess(PDF, MediaType.APPLICATION_OCTET_STREAM));

        copier.copy(harvester, "repo", ITEM);

        assertThat(platform.binaries().keySet()).anyMatch(path -> path.matches(ITEM + "/files/2611-17233-[0-9a-f]{8}"))
            .anyMatch(path -> path.matches(ITEM + "/files/final-slides-[0-9a-f]{8}\\.pptx"));
    }

    @Test
    void copiesTheFirstFilesOfARecordOnly() {
        properties.getFiles().setMaxPerRecord(1);
        item("https://files.repo.test/pdf/b, b.pdf, application/pdf", "https://files.repo.test/pdf/a, a.pdf, application/pdf");
        source.expect(ExpectedCount.once(), requestTo("https://files.repo.test/pdf/a"))
            .andRespond(withSuccess(PDF, MediaType.APPLICATION_PDF));

        assertThat(copier.copy(harvester, "repo", ITEM)).isEqualTo(1);
        source.verify();
    }

}
