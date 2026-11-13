package com.articulorium.index.elasticsearch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.articulorium.event.DocumentEvent;
import com.articulorium.index.config.ElasticsearchProperties;

/**
 * The settings and mappings of an index created on start-up.
 *
 * <p>The mappings give the fields of the transform service's documents the names and behaviour
 * they have in Solr's {@code _default} config set, so the documents and the discovery layer's
 * settings (search fields, facets, filters, sort orders) are the same for both backends:</p>
 * <ul>
 * <li>{@code <field>}: text, with its values copied to {@code <field>_str} and to the catch-all
 * field {@code _text_};</li>
 * <li>{@code <field>_str}: exact values (keywords), for facets, filters and sorting;</li>
 * <li>{@code <field>_txt_<language>}: text analyzed for that language;</li>
 * <li>{@code id}: the exact document identifier.</li>
 * </ul>
 * <p>Dates are not detected: a field is a date only when it is declared
 * ({@code articulorium.elasticsearch.fields}), so one unusual value cannot decide a field's type.</p>
 */
public final class ElasticsearchMappings {

    /** The catch-all text field every text value is copied to. */
    public static final String CATCH_ALL_FIELD = "_text_";

    /** Suffix of the exact-value copies of text fields. */
    public static final String EXACT_SUFFIX = "_str";

    /**
     * Longest value indexed in an exact-value field, in characters: Lucene refuses terms beyond
     * 32766 bytes, and a character takes at most four.
     */
    static final int IGNORE_ABOVE = 8191;

    /**
     * Elasticsearch's language analyzers by the suffix of the transform service's language fields
     * ({@code <field>_txt_<suffix>}): the suffixes are those of Solr's {@code _default} config set.
     */
    static final Map<String, String> ANALYZERS = analyzers();

    private ElasticsearchMappings() {
    }

    /**
     * The body creating the index.
     *
     * @param properties Elasticsearch settings
     * @return its settings and mappings
     */
    public static Map<String, Object> index(final ElasticsearchProperties properties) {
        final Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("number_of_shards", properties.getNumberOfShards());
        settings.put("number_of_replicas", properties.getNumberOfReplicas());
        settings.put("refresh_interval", properties.getRefreshInterval());

        final Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(DocumentEvent.ID_FIELD, Map.of("type", "keyword"));
        fields.put(CATCH_ALL_FIELD, Map.of("type", "text"));

        final Map<String, Object> mappings = new LinkedHashMap<>();
        mappings.put("date_detection", false);
        mappings.put("dynamic_templates", dynamicTemplates());
        mappings.put("properties", fields);

        final Map<String, Object> index = new LinkedHashMap<>();
        index.put("settings", Map.of("index", settings));
        index.put("mappings", mappings);
        return index;
    }

    /**
     * The mapping of a declared field.
     *
     * @param type its Elasticsearch field type
     * @return the mapping; a date that cannot be parsed is left out of the index instead of
     *     failing its whole document
     */
    public static Map<String, Object> field(final String type) {
        final Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("type", type);
        if ("date".equals(type)) {
            mapping.put("ignore_malformed", true);
        }
        return mapping;
    }

    /** The first matching template maps a new field, so the specific ones come first. */
    private static List<Map<String, Object>> dynamicTemplates() {
        final List<Map<String, Object>> templates = new ArrayList<>();
        final Map<String, Object> exact = new LinkedHashMap<>();
        exact.put("type", "keyword");
        exact.put("ignore_above", IGNORE_ABOVE);
        templates.add(template("exact_values", "*" + EXACT_SUFFIX, exact));
        ANALYZERS.forEach((suffix, analyzer) ->
            templates.add(template("text_" + suffix, "*_txt_" + suffix, text(analyzer, List.of(CATCH_ALL_FIELD)))));
        templates.add(template("text_other_languages", "*_txt_*", text(null, List.of(CATCH_ALL_FIELD))));
        // {name} is replaced by the field's name: title is copied to title_str
        templates.add(template("text", null, text(null, List.of("{name}" + EXACT_SUFFIX, CATCH_ALL_FIELD))));
        return templates;
    }

    private static Map<String, Object> template(final String name, final String match,
            final Map<String, Object> mapping) {
        final Map<String, Object> template = new LinkedHashMap<>();
        if (match != null) {
            template.put("match", match);
        }
        template.put("match_mapping_type", "string");
        template.put("mapping", mapping);
        return Map.of(name, template);
    }

    private static Map<String, Object> text(final String analyzer, final List<String> copyTo) {
        final Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("type", "text");
        if (analyzer != null) {
            mapping.put("analyzer", analyzer);
        }
        mapping.put("copy_to", copyTo);
        return mapping;
    }

    private static Map<String, String> analyzers() {
        final Map<String, String> analyzers = new LinkedHashMap<>();
        analyzers.put("ar", "arabic");
        analyzers.put("bg", "bulgarian");
        analyzers.put("ca", "catalan");
        analyzers.put("cjk", "cjk");
        analyzers.put("cz", "czech");
        analyzers.put("da", "danish");
        analyzers.put("de", "german");
        analyzers.put("el", "greek");
        analyzers.put("en", "english");
        analyzers.put("es", "spanish");
        analyzers.put("et", "estonian");
        analyzers.put("eu", "basque");
        analyzers.put("fa", "persian");
        analyzers.put("fi", "finnish");
        analyzers.put("fr", "french");
        analyzers.put("ga", "irish");
        analyzers.put("gl", "galician");
        analyzers.put("hi", "hindi");
        analyzers.put("hu", "hungarian");
        analyzers.put("hy", "armenian");
        analyzers.put("id", "indonesian");
        analyzers.put("it", "italian");
        // Japanese needs the kuromoji plugin for morphological analysis; bigrams work without it
        analyzers.put("ja", "cjk");
        analyzers.put("lv", "latvian");
        analyzers.put("nl", "dutch");
        analyzers.put("no", "norwegian");
        analyzers.put("pt", "portuguese");
        analyzers.put("ro", "romanian");
        analyzers.put("ru", "russian");
        analyzers.put("sv", "swedish");
        analyzers.put("th", "thai");
        analyzers.put("tr", "turkish");
        return analyzers;
    }

}
