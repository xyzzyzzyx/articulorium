package com.articulorium.discovery.solr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.articulorium.discovery.index.IndexQuery;
import com.articulorium.discovery.index.IndexResult;
import com.articulorium.discovery.index.SearchIndex;

import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;

/**
 * The Solr collection as the discovery layer's search index: renders {@link IndexQuery}s as
 * requests to Solr's select handler and reads its answers.
 *
 * <p>Search terms are read by the {@code edismax} parser with field queries turned off
 * ({@code uf=-*}); values from visitors are quoted, facet filters are tagged so their own facet
 * ignores them.</p>
 */
public class SolrSearchIndex implements SearchIndex {

    private final SolrSearchClient solr;

    private final SolrSchema schema;

    /**
     * Creates the index.
     *
     * @param solr   the Solr client
     * @param schema the fields the collection has
     */
    public SolrSearchIndex(final SolrSearchClient solr, final SolrSchema schema) {
        this.solr = solr;
        this.schema = schema;
    }

    @Override
    public boolean hasField(final String field) {
        return schema.has(field);
    }

    @Override
    public IndexResult search(final IndexQuery query) {
        final JsonNode response = solr.select(params(query));
        final List<JsonNode> documents = new ArrayList<>();
        response.path("response").path("docs").forEach(documents::add);
        final Map<String, List<IndexResult.Count>> facets = new LinkedHashMap<>();
        final JsonNode counts = response.path("facet_counts");
        for (final IndexQuery.Facet facet : query.facets()) {
            // Solr lists values and their counts in turn
            final JsonNode list = facet instanceof IndexQuery.Years
                ? counts.path("facet_ranges").path(facet.id()).path("counts")
                : counts.path("facet_fields").path(facet.id());
            final List<IndexResult.Count> values = new ArrayList<>();
            for (int i = 0; i + 1 < list.size(); i += 2) {
                values.add(new IndexResult.Count(list.get(i).asString(""), list.get(i + 1).asLong(0)));
            }
            facets.put(facet.id(), values);
        }
        return new IndexResult(response.path("response").path("numFound").asLong(0), documents, facets);
    }

    /**
     * The request parameters of a query.
     *
     * @param query the query
     * @return the parameters of Solr's select handler
     */
    static MultiValueMap<String, String> params(final IndexQuery query) {
        final MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        final IndexQuery.Text text = query.text();
        if (text != null) {
            params.add("defType", "edismax");
            params.add("q", text.query());
            params.add("qf", boosts(text.fields()));
            if (!text.phraseFields().isEmpty()) {
                params.add("pf", boosts(text.phraseFields()));
            }
            text.boostQueries().forEach(boost -> params.add("bq", boost));
            if (StringUtils.hasText(text.minimumMatch())) {
                params.add("mm", text.minimumMatch());
            }
            // the search terms may not address fields of their own (access control, internal fields)
            params.add("uf", "-*");
        } else {
            params.add("q", query.match() == null ? "*:*" : filter(query.match()));
        }
        query.filters().forEach(filter -> params.add("fq", filter(filter)));
        params.add("fl", String.join(",", query.fields()));
        params.add("start", Integer.toString(query.start()));
        params.add("rows", Integer.toString(query.rows()));
        if (query.sort() != null) {
            params.add("sort", query.sort() + ", " + IndexQuery.ID_FIELD + " asc");
        }
        facets(params, query.facets());
        return params;
    }

    private static String boosts(final List<IndexQuery.Boost> fields) {
        return fields.stream().map(IndexQuery.Boost::term).collect(Collectors.joining(" "));
    }

    private static String filter(final IndexQuery.Filter filter) {
        return switch (filter) {
            case IndexQuery.Raw raw -> raw.query();
            case IndexQuery.AnyOf anyOf -> SolrSyntax.anyOf(anyOf.field(), anyOf.values());
            case IndexQuery.YearRanges years -> years.ranges().stream()
                .map(range -> years.field() + ":[" + IndexQuery.startOfYear(range.from()) + " TO "
                    + IndexQuery.startOfYear(range.to() + 1) + "}")
                .collect(Collectors.joining(" OR ", "(", ")"));
            case IndexQuery.ChildrenOf children -> SolrSyntax.childrenOf(children.field(), children.parents());
            case IndexQuery.Tagged tagged -> SolrSyntax.tagged(tagged.tag(), filter(tagged.filter()));
        };
    }

    private static void facets(final MultiValueMap<String, String> params, final List<IndexQuery.Facet> facets) {
        if (facets.isEmpty()) {
            return;
        }
        params.add("facet", "true");
        params.add("facet.mincount", "1");
        for (final IndexQuery.Facet facet : facets) {
            final String field = facet.field();
            // counted without the filter tagged with the facet's identifier
            final String local = "{!ex=" + facet.id() + " key=" + facet.id() + "}" + field;
            switch (facet) {
                case IndexQuery.Years years -> {
                    params.add("facet.range", local);
                    params.add("f." + field + ".facet.range.start", years.start() + "-01-01T00:00:00Z");
                    params.add("f." + field + ".facet.range.end", years.end() + "-01-01T00:00:00Z");
                    params.add("f." + field + ".facet.range.gap", "+" + years.gap() + "YEARS");
                    params.add("f." + field + ".facet.range.hardend", "true");
                }
                case IndexQuery.Terms terms -> {
                    params.add("facet.field", local);
                    params.add("f." + field + ".facet.limit", Integer.toString(terms.limit()));
                    params.add("f." + field + ".facet.sort", terms.sortByValue() ? "index" : "count");
                }
            }
        }
    }

}
