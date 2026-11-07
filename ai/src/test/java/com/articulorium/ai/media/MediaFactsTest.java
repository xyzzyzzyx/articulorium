package com.articulorium.ai.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.articulorium.ai.support.Files;

import org.junit.jupiter.api.Test;

/**
 * Tests the metadata read from files without a model.
 */
class MediaFactsTest {

    private static final String DCTERMS = "http://purl.org/dc/terms/";

    @Test
    void readsTheDimensionsOfAnImage() {
        final MediaFacts.Facts facts = MediaFacts.ofImageOrVideo(Files.image("jpeg", 64, 48), MediaKind.IMAGE);

        assertThat(facts.statements()).extracting(MetadataStatement::predicate, MetadataStatement::value,
            MetadataStatement::datatype, MetadataStatement::origin)
            .contains(tuple(MediaFacts.WIDTH, "64", MediaFacts.XSD + "integer", MetadataStatement.Origin.FILE),
                tuple(MediaFacts.HEIGHT, "48", MediaFacts.XSD + "integer", MetadataStatement.Origin.FILE));
        assertThat(facts.notes()).isEmpty();
    }

    @Test
    void notesUnreadableFiles() {
        final MediaFacts.Facts facts = MediaFacts.ofImageOrVideo("not an image".getBytes(), MediaKind.IMAGE);

        assertThat(facts.statements()).isEmpty();
        assertThat(facts.notes()).containsExactly("UNREADABLE_METADATA");
    }

    @Test
    void readsTheInformationAndTextOfAPdf() {
        final MediaFacts.Facts facts = MediaFacts.ofPdf(Files.pdf(3), 2, 10_000);

        assertThat(facts.pages()).isEqualTo(3);
        assertThat(facts.statements()).extracting(MetadataStatement::predicate, MetadataStatement::value)
            .contains(tuple(DCTERMS + "title", "Annual Report 1912"), tuple(DCTERMS + "creator", "Harbour Board"),
                tuple(DCTERMS + "subject", "shipping"), tuple(DCTERMS + "subject", "harbours"),
                tuple(DCTERMS + "extent", "3 p."), tuple(DCTERMS + "created", "2001-03-04T05:06:07Z"));
        assertThat(facts.text()).contains("Page 1 of the annual report").contains("Page 2").doesNotContain("Page 3");
        assertThat(facts.notes()).containsExactly("TEXT_FROM_FIRST_PAGES");
    }

    @Test
    void shortensLongTexts() {
        final MediaFacts.Facts facts = MediaFacts.ofPdf(Files.pdf(1), 5, 10);

        assertThat(facts.text()).hasSize(10);
        assertThat(facts.notes()).containsExactly("TEXT_SHORTENED");
    }

    @Test
    void notesUnreadablePdfs() {
        assertThat(MediaFacts.ofPdf("%PDF-1.7 broken".getBytes(), 5, 100).notes()).containsExactly("UNREADABLE_METADATA");
    }

    @Test
    void writesDurations() {
        assertThat(MediaFacts.duration(3723.5)).isEqualTo("PT1H2M3.5S");
        assertThat(MediaFacts.duration(42)).isEqualTo("PT42S");
        assertThat(MediaFacts.duration(60)).isEqualTo("PT1M0S");
    }

    @Test
    void tellsTheKindOfMediaTypes() {
        assertThat(MediaKind.of("image/jpeg; charset=binary")).contains(MediaKind.IMAGE);
        assertThat(MediaKind.of("video/mp4")).contains(MediaKind.VIDEO);
        assertThat(MediaKind.of("application/pdf")).contains(MediaKind.PDF);
        assertThat(MediaKind.of("text/plain")).isEmpty();
        assertThat(MediaKind.of(null)).isEmpty();
    }

}
