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
        assertThat(StructureNames.clean("New collection for EqRank")).as("left for namesOneRecord")
            .isEqualTo("New collection for EqRank");
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
        assertThat(List.of("H.3.7", "cs.DL", "physics.soc-ph", "68T50", "42", " - "))
            .allMatch(StructureNames::isCode);
        assertThat(List.of("Computer Science", "e-Science", "AI", "Health Sciences")).noneMatch(StructureNames::isCode);
    }

    @Test
    void tellsCollectionsNamedAfterOneRecord() {
        final List<String> record = List.of("Fedora: An Architecture for Complex Objects and their Relationships");

        assertThat(StructureNames.namesOneRecord("New Collection for Microsoft TerraServer", record)).isTrue();
        assertThat(StructureNames.namesOneRecord("A collection of records about EqRank", record)).isTrue();
        assertThat(StructureNames.namesOneRecord("Fedora: An Architecture for Complex Objects", record)).isTrue();
        assertThat(StructureNames.namesOneRecord("Architecture for Complex Objects", record)).as("the title's words").isTrue();
        assertThat(StructureNames.namesOneRecord("Studies of Ranking Search Results on the Web Today", List.of()))
            .as("six words besides stop words").isTrue();

        assertThat(StructureNames.namesOneRecord("Digital Object Architectures", record)).isFalse();
        assertThat(StructureNames.namesOneRecord("Information Retrieval", List.of("Information retrieval in libraries")))
            .isFalse();
        assertThat(StructureNames.namesOneRecord("Theses", record)).isFalse();
        assertThat(StructureNames.namesOneRecord("Collections Management", record)).isFalse();
    }

}
