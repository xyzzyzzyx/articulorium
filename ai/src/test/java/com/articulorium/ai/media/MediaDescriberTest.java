package com.articulorium.ai.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.Locale;

import com.articulorium.ai.AgentException;
import com.articulorium.ai.AgentException.Reason;
import com.articulorium.ai.support.Agents;
import com.articulorium.ai.support.Files;
import com.articulorium.ai.support.Platform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.unit.DataSize;

/**
 * Tests media metadata suggestions against a mocked platform and a scripted model.
 */
class MediaDescriberTest {

    private static final String DCTERMS = "http://purl.org/dc/terms/";

    private static final String ITEM = "communities/lib/postcards/card-1";

    private static final String BINARY = ITEM + "/files/harbour.jpg";

    private static final String ANSWER = """
        {"title": "Harbour", "description": "Steamships moored at a quay.", "subjects": ["ships", "harbours", "ships"],
         "creators": [], "date": "1912", "language": "en", "places": ["Hamburg"], "period": null,
         "genre": "postcard"}
        """;

    private Agents agents;

    private MediaDescriber describer;

    @BeforeEach
    void setUp() {
        agents = new Agents();
        describer = new MediaDescriber(agents.model, agents.access, agents.reader, agents.properties);
        agents.platform.rdf(ITEM + "/files", "<> <http://www.w3.org/ns/ldp#hasMemberRelation> <http://pcdm.org/models#hasFile> .\n")
            .rdf(ITEM, "<> <http://purl.org/dc/terms/title> \"Postcard of the harbour\" .\n")
            .rdf("communities/lib/postcards", "<> <http://purl.org/dc/terms/title> \"Postcards\" .\n")
            .rdf("communities/lib", "<> <http://purl.org/dc/terms/title> \"Library\" .\n");
    }

    private static String description(final String mediaType, final long size, final String filename) {
        return """
            <> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/ns/ldp#NonRDFSource> .
            <> <http://www.ebu.ch/metadata/ontologies/ebucore/ebucore#hasMimeType> "%s" .
            <> <http://www.loc.gov/premis/rdf/v1#hasSize> "%d"^^<http://www.w3.org/2001/XMLSchema#long> .
            <> <http://www.ebu.ch/metadata/ontologies/ebucore/ebucore#filename> "%s" .
            <> <http://purl.org/dc/terms/title> "Harbour" .
            """.formatted(mediaType, size, filename);
    }

    @Test
    void suggestsFileAndModelMetadataForAnImage() {
        final byte[] jpeg = Files.image("jpeg", 40, 30);
        agents.platform.access(BINARY, true, "read", "write")
            .binary(BINARY, "image/jpeg", jpeg, description("image/jpeg", jpeg.length, "harbour.jpg"));
        agents.chat.answer(ANSWER);

        final MediaMetadataSuggestion suggestion = describer.describe(BINARY, Platform.TOKEN, Locale.ENGLISH);

        assertThat(suggestion.kind()).isEqualTo(MediaKind.IMAGE);
        assertThat(suggestion.description()).isEqualTo(BINARY + "/description");
        assertThat(suggestion.imageShown()).isTrue();
        assertThat(suggestion.notes()).isEmpty();
        assertThat(suggestion.statements()).extracting(MetadataStatement::predicate, MetadataStatement::value,
            MetadataStatement::language, MetadataStatement::origin)
            .containsExactlyInAnyOrder(
                tuple(MediaFacts.WIDTH, "40", null, MetadataStatement.Origin.FILE),
                tuple(MediaFacts.HEIGHT, "30", null, MetadataStatement.Origin.FILE),
                tuple(DCTERMS + "description", "Steamships moored at a quay.", "en", MetadataStatement.Origin.MODEL),
                tuple(DCTERMS + "subject", "ships", "en", MetadataStatement.Origin.MODEL),
                tuple(DCTERMS + "subject", "harbours", "en", MetadataStatement.Origin.MODEL),
                tuple(DCTERMS + "date", "1912", null, MetadataStatement.Origin.MODEL),
                tuple(DCTERMS + "language", "en", null, MetadataStatement.Origin.MODEL),
                tuple(DCTERMS + "spatial", "Hamburg", "en", MetadataStatement.Origin.MODEL),
                tuple(DCTERMS + "type", "postcard", "en", MetadataStatement.Origin.MODEL));
        final UserMessage user = agents.chat.prompts().get(0).getUserMessage();
        assertThat(user.getMedia()).hasSize(1);
        assertThat(user.getMedia().get(0).getMimeType().toString()).isEqualTo("image/jpeg");
        assertThat(user.getText()).contains("<<<BEGIN file>>>").contains("file name: harbour.jpg")
            .contains("embedded width: 40").contains("in: Postcard of the harbour").contains("within: Postcards");
        agents.platform.server().verify();
    }

    @Test
    void writesDescriptionsInTheCallersLanguageAndDropsUnsupportedDates() {
        final byte[] jpeg = Files.image("jpeg", 10, 10);
        agents.platform.access(BINARY, true, "write")
            .binary(BINARY, "image/jpeg", jpeg, description("image/jpeg", jpeg.length, "harbour.jpg"));
        agents.chat.answer("""
            {"title": "Hafen", "description": "Dampfer am Kai.", "date": "um 1910", "language": "not a tag!"}
            """);

        final MediaMetadataSuggestion suggestion = describer.describe(BINARY, Platform.TOKEN, Locale.GERMAN);

        assertThat(suggestion.statements()).filteredOn(statement -> statement.origin() == MetadataStatement.Origin.MODEL)
            .extracting(MetadataStatement::predicate, MetadataStatement::value, MetadataStatement::language)
            .containsExactlyInAnyOrder(tuple(DCTERMS + "title", "Hafen", null),
                tuple(DCTERMS + "description", "Dampfer am Kai.", "de"));
        assertThat(agents.chat.prompts().get(0).getSystemMessage().getText()).contains("German (de)");
    }

    @Test
    void describesImagesTheModelCannotSeeFromTheirMetadata() {
        final byte[] bmp = Files.image("bmp", 8, 8);
        agents.platform.access(BINARY, true, "write")
            .binary(BINARY, "image/bmp", bmp, description("image/bmp", bmp.length, "harbour.bmp"));
        agents.chat.answer(ANSWER);

        final MediaMetadataSuggestion suggestion = describer.describe(BINARY, Platform.TOKEN, Locale.ENGLISH);

        assertThat(suggestion.imageShown()).isFalse();
        assertThat(suggestion.notes()).containsExactly("IMAGE_FORMAT_NOT_SHOWN");
        assertThat(agents.chat.prompts().get(0).getUserMessage().getMedia()).isEmpty();
    }

    @Test
    void doesNotShowImagesLargerThanTheLimit() {
        agents.properties.getMedia().setMaxImageBytes(DataSize.ofBytes(100));
        final byte[] png = Files.image("png", 200, 200);
        agents.platform.access(BINARY, true, "write")
            .binary(BINARY, "image/png", png, description("image/png", png.length, "harbour.png"));
        agents.chat.answer(ANSWER);

        assertThat(describer.describe(BINARY, Platform.TOKEN, Locale.ENGLISH).notes())
            .containsExactly("IMAGE_TOO_LARGE_TO_SHOW");
    }

    @Test
    void readsTheTextOfPdfs() {
        final byte[] pdf = Files.pdf(2);
        agents.platform.access(BINARY, true, "write")
            .binary(BINARY, "application/pdf", pdf, description("application/pdf", pdf.length, "report.pdf"));
        agents.chat.answer("{\"description\": \"The harbour board's report for 1912.\"}");

        final MediaMetadataSuggestion suggestion = describer.describe(BINARY, Platform.TOKEN, Locale.ENGLISH);

        assertThat(suggestion.kind()).isEqualTo(MediaKind.PDF);
        assertThat(suggestion.statements()).extracting(MetadataStatement::predicate, MetadataStatement::value)
            .contains(tuple(DCTERMS + "extent", "2 p."), tuple(DCTERMS + "creator", "Harbour Board"),
                tuple(DCTERMS + "title", "Annual Report 1912"));
        assertThat(agents.chat.prompts().get(0).getUserMessage().getText()).contains("<<<BEGIN extracted-text>>>")
            .contains("Page 2 of the annual report").contains("pages: 2");
    }

    @Test
    void describesLargeVideosFromTheirContextWithoutReadingThem() {
        agents.properties.getMedia().setMaxBytes(DataSize.ofBytes(1000));
        agents.platform.access(BINARY, true, "write")
            // the description says 5 MB: the bytes are never read
            .binary(BINARY, "video/mp4", new byte[0], description("video/mp4", 5_000_000, "harbour.mp4"));
        agents.chat.answer("{\"title\": \"Harbour film\"}");

        final MediaMetadataSuggestion suggestion = describer.describe(BINARY, Platform.TOKEN, Locale.ENGLISH);

        assertThat(suggestion.kind()).isEqualTo(MediaKind.VIDEO);
        assertThat(suggestion.notes()).containsExactly("FILE_NOT_READ", "VIDEO_NOT_SHOWN");
        assertThat(suggestion.statements()).extracting(MetadataStatement::value).containsExactly("Harbour film");
    }

    @Test
    void refusesPdfsAndImagesLargerThanTheLimit() {
        agents.properties.getMedia().setMaxBytes(DataSize.ofBytes(1000));
        agents.platform.access(BINARY, true, "write")
            .binary(BINARY, "application/pdf", new byte[0], description("application/pdf", 5_000, "report.pdf"));

        assertThatThrownBy(() -> describer.describe(BINARY, Platform.TOKEN, Locale.ENGLISH))
            .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.TOO_LARGE));
        assertThat(agents.chat.prompts()).isEmpty();
    }

    @Test
    void refusesUnsupportedBinaries() {
        agents.platform.access(BINARY, true, "write")
            .binary(BINARY, "text/plain", "hello".getBytes(), description("text/plain", 5, "notes.txt"));

        assertThatThrownBy(() -> describer.describe(BINARY, Platform.TOKEN, Locale.ENGLISH))
            .isInstanceOfSatisfying(AgentException.class, e -> {
                assertThat(e.getReason()).isEqualTo(Reason.UNSUPPORTED);
                assertThat(e.getMessage()).contains("text/plain");
            });
        assertThat(agents.chat.prompts()).isEmpty();
    }

    @Test
    void refusesResourcesThatAreNoBinaries() {
        agents.platform.access(ITEM, true, "write");

        assertThatThrownBy(() -> describer.describe(ITEM, Platform.TOKEN, Locale.ENGLISH))
            .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.UNSUPPORTED));
    }

    @Test
    void refusesCallersWhoMayNotChangeTheDescription() {
        agents.platform.access(BINARY, true, "read");

        assertThatThrownBy(() -> describer.describe(BINARY, Platform.TOKEN, Locale.ENGLISH))
            .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.FORBIDDEN));
        assertThat(agents.chat.prompts()).isEmpty();
    }

    @Test
    void refusesAnonymousCallersWithoutAccess() {
        agents.platform.access(BINARY, false, "read");

        assertThatThrownBy(() -> describer.describe(BINARY, null, Locale.ENGLISH))
            .isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getReason()).isEqualTo(Reason.UNAUTHENTICATED));
    }

    @Test
    void reportsMissingBinaries() {
        agents.platform.access(BINARY, true, "write").refused(BINARY, org.springframework.http.HttpStatus.NOT_FOUND);

        assertThatThrownBy(() -> describer.describe(BINARY, Platform.TOKEN, Locale.ENGLISH))
            .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.NOT_FOUND));
    }

}
