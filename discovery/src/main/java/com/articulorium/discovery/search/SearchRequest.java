package com.articulorium.discovery.search;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * A search, as a visitor asked for it.
 *
 * @param query         the search terms, may be empty (browsing)
 * @param page          the page, from 1
 * @param size          results per page, 0 for the configured default
 * @param sort          the identifier of a configured sort order, may be {@code null}
 * @param facets        selected facet values by facet identifier
 * @param filters       identifiers of the filters switched on
 * @param within        the path of a container whose direct children are searched, may be {@code null}
 * @param kinds         only resources of these kinds; empty for any kind
 * @param locale        the visitor's language tag (titles, language-specific fields), may be {@code null}
 * @param authorization the visitor's {@code Authorization} header, {@code null} when anonymous
 */
public record SearchRequest(String query, int page, int size, @Nullable String sort, Map<String, List<String>> facets,
        Set<String> filters, @Nullable String within, Set<ResourceKind> kinds, @Nullable String locale,
        @Nullable String authorization) {

    /**
     * Copies the kinds, so the request stays immutable.
     */
    public SearchRequest {
        kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
    }

    /**
     * A request browsing a container's children.
     *
     * @param within the container's path
     * @param size   results per page
     * @param locale the visitor's language tag
     * @param authorization the visitor's credentials
     * @return the request
     */
    public static SearchRequest within(final String within, final int size, final @Nullable String locale,
            final @Nullable String authorization) {
        return new SearchRequest("", 1, size, null, Map.of(), Set.of(), within, Set.of(), locale, authorization);
    }

}
