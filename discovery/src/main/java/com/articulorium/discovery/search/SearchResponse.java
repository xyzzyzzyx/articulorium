package com.articulorium.discovery.search;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.jspecify.annotations.Nullable;

/**
 * A page of search results with its facets.
 *
 * @param query   the search terms
 * @param page    the page, from 1
 * @param size    results per page
 * @param total   the number of matching resources in the index (resources the visitor may not
 *                read are counted but never listed)
 * @param sort    the identifier of the sort order applied, {@code null} for relevance or browsing
 * @param results the results the visitor may read
 * @param hidden  results of this page left out because the visitor may not read them
 * @param facets  the facets with their values
 * @param filters the identifiers of the filters switched on
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchResponse(String query, int page, int size, long total, @Nullable String sort,
        List<Result> results, int hidden, List<FacetResult> facets, List<String> filters) {

    /**
     * A search result.
     *
     * @param path        the resource path (resource pages are {@code items/<path>})
     * @param title       the title, in the visitor's language when the resource has one in it
     * @param description the description, {@code null} when it has none
     * @param kind        what the resource is
     * @param types       its RDF types
     * @param date        its date as indexed, {@code null} when it has none
     * @param thumbnail   a thumbnail URL, {@code null} when the index has none
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(String path, String title, @Nullable String description, ResourceKind kind,
            List<String> types, @Nullable String date, @Nullable String thumbnail) {
    }

    /**
     * A facet.
     *
     * @param id     its identifier
     * @param values its values, selected ones included
     */
    public record FacetResult(String id, List<FacetValue> values) {
    }

    /**
     * A facet value.
     *
     * @param value    the value (a year range {@code 1900-1909} for year facets)
     * @param count    matching resources
     * @param selected whether the search is restricted to it
     */
    public record FacetValue(String value, long count, boolean selected) {
    }

}
