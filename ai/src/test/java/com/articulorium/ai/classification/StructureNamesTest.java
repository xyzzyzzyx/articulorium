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
