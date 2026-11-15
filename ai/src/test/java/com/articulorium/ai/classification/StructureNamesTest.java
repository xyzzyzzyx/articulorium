package com.articulorium.ai.classification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class StructureNamesTest {

    @Test
    void namesTheSameCommunityWhateverItsSpelling() {
        assertThat(StructureNames.key("Digital Libraries")).isEqualTo("digital-library")
            .isEqualTo(StructureNames.key("the digital library"))
            .isEqualTo(StructureNames.key("Digital  Libraries "));
        assertThat(StructureNames.key("Computers & Society")).isEqualTo(StructureNames.key("Computers and Society"));
        assertThat(StructureNames.key("Méthodes")).isEqualTo("methode");
        assertThat(StructureNames.key("Physics")).isEqualTo(StructureNames.key("physics"));
        assertThat(StructureNames.key("Analysis")).as("not a plural").isEqualTo("analysis");
        assertThat(StructureNames.key("Information Retrieval")).isNotEqualTo(StructureNames.key("Information Science"));
    }

    @Test
    void takesTheLabelsOffProposedTitles() {
        assertThat(StructureNames.clean("New Community: Digital Libraries")).isEqualTo("Digital Libraries");
        assertThat(StructureNames.clean("New Collection - \"Web Science\".")).isEqualTo("Web Science");
        assertThat(StructureNames.clean("Sub-community: Community: History")).isEqualTo("History");
        assertThat(StructureNames.clean("Collections Management")).as("a name, not a label").isEqualTo("Collections Management");
        assertThat(StructureNames.clean("New collection for EqRank")).isEqualTo("EqRank");
        assertThat(StructureNames.clean("A collection of records about the Semantic Web")).isEqualTo("Semantic Web");
        assertThat(StructureNames.clean(null)).isEmpty();
    }

    @Test
    void tellsPlaceholdersFromNames() {
        assertThat(List.of("None", "none", "N/A", "null", "Unknown", "-", "", "New Community: None", "Other"))
            .allMatch(StructureNames::isPlaceholder);
        assertThat(StructureNames.isPlaceholder(null)).isTrue();
        assertThat(List.of("Nonlinear Dynamics", "General Relativity", "Other Worlds Literature", "AI"))
            .noneMatch(StructureNames::isPlaceholder);
    }

    @Test
    void tellsClassificationCodesFromNames() {
        assertThat(List.of("H.3.7", "cs.DL", "physics.soc-ph", "68T50", "42", " - ", "cs:cs:DL", "physics:astro-ph"))
            .allMatch(StructureNames::isCode);
        assertThat(List.of("Computer Science", "e-Science", "AI", "Health Sciences")).noneMatch(StructureNames::isCode);
    }

    @Test
    void tellsNamesStandingForOneRecord() {
        final List<String> record = List.of("Fedora: An Architecture for Complex Objects and their Relationships");

        assertThat(StructureNames.namesOneRecord("Fedora: An Architecture for Complex Objects", record)).as("a subtitle")
            .isTrue();
        assertThat(StructureNames.namesOneRecord("Fedora", record)).as("the record's short title").isTrue();
        assertThat(StructureNames.namesOneRecord("An Architecture for Complex Objects and their Relationships", record))
            .as("most of its title").isTrue();
        assertThat(StructureNames.namesOneRecord("Studies of Ranking Search Results on the Web Today", List.of()))
            .as("six words besides stop words").isTrue();

        // topics sharing a few words with the title are topics
        assertThat(StructureNames.namesOneRecord("Latin American Studies",
            List.of("Latin American Studies since the 1980s: From Apocalyptic Dreams"))).isFalse();
        assertThat(StructureNames.namesOneRecord("Disaster Mental Health Research",
            List.of("A Computational Map of the Disaster Mental Health Research Landscape"))).isFalse();
        assertThat(StructureNames.namesOneRecord("Neural Network Datasets",
            List.of("LEMUR Neural Network Dataset: Towards Seamless AutoML"))).isFalse();
        assertThat(StructureNames.namesOneRecord("Digital Object Architectures", record)).isFalse();
        assertThat(StructureNames.namesOneRecord("Theses", record)).isFalse();
        assertThat(StructureNames.namesOneRecord("New Collection for AI in Scientific Writing", record))
            .as("an announced topic").isFalse();
    }

}
