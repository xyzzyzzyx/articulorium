package com.articulorium.ai.classification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.articulorium.ai.config.AiProperties;

import org.junit.jupiter.api.Test;

/**
 * Tests reading the classification a harvested record brings along.
 */
class RecordClassificationTest {

    private final AiProperties.Classification settings = new AiProperties().getClassification();

    private RecordClassification read(final List<String> subjects, final List<String> sets) {
        return RecordClassification.of(new ClassificationRequest("harvested/arxiv", "arxiv", "x",
            List.of("A Study of Something Quite Particular"), List.of(), subjects, List.of(), List.of(), List.of(),
            List.of(), sets, List.of()), settings.getFields(), settings.getSetDisciplines()).orElse(null);
    }

    @Test
    void readsTheDisciplineOfASetOrOfTheSetAboveIt() {
        final RecordClassification arxiv = read(List.of("Digital Libraries", "H.3.7", "Computation and Language"),
            List.of("cs:cs:DL"));

        assertThat(arxiv).isEqualTo(new RecordClassification("Natural Sciences", "Computer Science", "set cs:cs:DL",
            List.of("Digital Libraries", "Computation and Language")));
        assertThat(read(List.of("Astrophysics of Galaxies"), List.of("physics:astro-ph:GA")).discipline())
            .isEqualTo("Physics");
        assertThat(read(List.of("General Economics"), List.of("ECON")).field()).isEqualTo("Social Sciences");
    }

    @Test
    void readsTheDisciplineASubjectNames() {
        final RecordClassification classified = read(List.of("Computer Science - Digital Libraries", "H.3.7"),
            List.of("cs:cs:DL"));

        // a subject naming the discipline goes before the set; it names no field
        assertThat(classified.field()).isNull();
        assertThat(classified.discipline()).isEqualTo("Computer Science");
        assertThat(classified.source()).isEqualTo("subject \"Computer Science - Digital Libraries\"");
        assertThat(classified.topics()).containsExactly("Digital Libraries");
    }

    @Test
    void takesNoCodeFieldDisciplineOrRecordTitleForATopic() {
        final RecordClassification classified = read(List.of("None", "cs.DL", "Natural Sciences", "Computer Science",
            "A Study of Something Quite Particular", "Digital Libraries", "Digital libraries"), List.of("cs"));

        assertThat(classified.topics()).containsExactly("Digital Libraries");
    }

    @Test
    void namesNothingWithoutAKnownSetOrSubject() {
        assertThat(read(List.of(), List.of("theses"))).isNull();
        assertThat(read(List.of("Physics"), List.of("theses")).discipline()).as("topics alone").isNull();

        settings.setSetDisciplines(List.of("theses", "=Natural Sciences > Physics", "dl=Digital Libraries"));
        assertThat(read(List.of(), List.of("theses"))).as("malformed entries are left out").isNull();
        assertThat(read(List.of(), List.of("dl"))).as("a discipline without its field")
            .isEqualTo(new RecordClassification(null, "Digital Libraries", "set dl", List.of()));
    }

}
