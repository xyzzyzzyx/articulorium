package com.articulorium.index.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.articulorium.index.config.ElasticsearchProperties;

import org.junit.jupiter.api.Test;

/**
 * Tests the mappings that give Elasticsearch fields the names they have in Solr.
 */
class ElasticsearchMappingsTest {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Map<String, Object>>> templates() {
        final Map<String, Object> mappings = (Map<String, Object>) ElasticsearchMappings
            .index(new ElasticsearchProperties()).get("mappings");
        return (List<Map<String, Map<String, Object>>>) mappings.get("dynamic_templates");
    }

    private static Map<String, Object> template(final String name) {
        return templates().stream().filter(template -> template.containsKey(name)).findFirst().orElseThrow().get(name);
    }

    @Test
    void textFieldsAreCopiedToTheirExactValueFieldAndTheCatchAllField() {
        final Map<String, Object> text = template("text");

        assertThat(text).doesNotContainKey("match").containsEntry("match_mapping_type", "string");
        assertThat(text.get("mapping")).isEqualTo(Map.of("type", "text", "copy_to", List.of("{name}_str", "_text_")));
    }

    @Test
    void exactValueFieldsAreKeywordsAndAreNotCopiedAgain() {
        final Map<String, Object> exact = template("exact_values");

        assertThat(exact).containsEntry("match", "*_str");
        assertThat(exact.get("mapping")).isEqualTo(Map.of("type", "keyword", "ignore_above", 8191));
    }

    @Test
    void languageFieldsUseTheirLanguagesAnalyzer() {
        assertThat(template("text_de")).containsEntry("match", "*_txt_de");
        assertThat(template("text_de").get("mapping"))
            .isEqualTo(Map.of("type", "text", "analyzer", "german", "copy_to", List.of("_text_")));
        assertThat(template("text_cz").get("mapping")).asInstanceOf(
            org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("analyzer", "czech");
    }

    @Test
    void theFirstMatchingTemplateWinsSoSpecificTemplatesComeFirst() {
        final List<String> names = templates().stream().map(template -> template.keySet().iterator().next()).toList();

        assertThat(names).first().isEqualTo("exact_values");
        assertThat(names).last().isEqualTo("text");
        assertThat(names.indexOf("text_en")).isLessThan(names.indexOf("text_other_languages"));
        assertThat(names.indexOf("text_other_languages")).isLessThan(names.indexOf("text"));
    }

    @Test
    void everyLanguageSuffixTheTransformServiceWritesHasAnAnalyzer() {
        // the suffixes of FieldMappingTransformer.SOLR_LANGUAGES
        assertThat(ElasticsearchMappings.ANALYZERS).containsKeys("ar", "bg", "ca", "cz", "da", "de", "el", "en", "es",
            "et", "eu", "fa", "fi", "fr", "ga", "gl", "hi", "hu", "hy", "id", "it", "ja", "cjk", "lv", "nl", "no", "pt",
            "ro", "ru", "sv", "th", "tr");
    }

    @Test
    void declaredDatesTolerateValuesThatAreNotDates() {
        assertThat(ElasticsearchMappings.field("date")).isEqualTo(Map.of("type", "date", "ignore_malformed", true));
        assertThat(ElasticsearchMappings.field("keyword")).isEqualTo(Map.of("type", "keyword"));
    }

}
