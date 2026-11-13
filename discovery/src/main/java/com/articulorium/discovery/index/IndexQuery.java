package com.articulorium.discovery.index;

import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * A search, independent of the search index backend.
 *
 * <p>Values that come from visitors are only ever carried as data ({@link Text#query()}, the
 * values of {@link AnyOf}, {@link YearRanges} and {@link ChildrenOf}): backends quote or escape
 * them. Query syntax only comes from the deployment's settings ({@link Raw},
 * {@link Text#boostQueries()}, {@link #sort()}), in the Lucene query syntax Solr and
 * Elasticsearch share.</p>
 *
 * @param text    the visitor's search terms, {@code null} to match by {@link #match()} only
 * @param match   what documents must match besides the filters when there are no search terms,
 *     {@code null} for every document
 * @param filters what documents must match, without influence on their ranking
 * @param fields  the fields to return, {@code *} for all
 * @param start   the offset of the first document
 * @param rows    the number of documents
 * @param sort    the order ({@code <field> asc|desc}, several separated by commas; ties are broken
 *     by identifier), {@code null} for relevance
 * @param facets  the facets to count
 */
public record IndexQuery(@Nullable Text text, @Nullable Filter match, List<Filter> filters, List<String> fields,
        int start, int rows, @Nullable String sort, List<Facet> facets) {

    /** The field identifying documents, which also breaks ties of a sort order. */
    public static final String ID_FIELD = "id";

    /**
     * Search terms and how they are matched.
     *
     * @param query        the terms as the visitor typed them
     * @param fields       the fields searched, with their precedence
     * @param phraseFields the fields in which the terms as a phrase rank higher
     * @param boostQueries queries from the settings whose matches rank higher
     * @param minimumMatch how many terms must match ({@code mm} syntax), {@code null} for the
     *     backend's default
     */
    public record Text(String query, List<Boost> fields, List<Boost> phraseFields, List<String> boostQueries,
            @Nullable String minimumMatch) {
    }

    /**
     * A field and its weight.
     *
     * @param field the field
     * @param boost the weight, 1 for none
     */
    public record Boost(String field, double boost) {

        /**
         * The field with its weight, as Solr and Elasticsearch write it.
         *
         * @return for example {@code title^10}, {@code description^2.5}, {@code creator}
         */
        public String term() {
            if (boost == 1) {
                return field;
            }
            return field + "^" + (boost == Math.rint(boost) ? Long.toString((long) boost)
                : String.format(Locale.ROOT, "%s", boost));
        }

    }

    /** A condition on documents. */
    public sealed interface Filter permits Raw, AnyOf, YearRanges, ChildrenOf, Tagged {
    }

    /**
     * A query from the settings, in Lucene query syntax.
     *
     * @param query the query, never from visitors
     */
    public record Raw(String query) implements Filter {
    }

    /**
     * Documents with any of several exact values in a field.
     *
     * @param field  the field (from the settings)
     * @param values the values
     */
    public record AnyOf(String field, List<String> values) implements Filter {
    }

    /**
     * Documents with a date in any of several ranges of years.
     *
     * @param field  the date field (from the settings)
     * @param ranges the ranges
     */
    public record YearRanges(String field, List<YearRange> ranges) implements Filter {
    }

    /**
     * A range of years.
     *
     * @param from the first year
     * @param to   the last year
     */
    public record YearRange(int from, int to) {
    }

    /**
     * The direct children of containers in a hierarchy of identifiers: documents whose value
     * continues a parent's value with {@code /} and one more segment.
     *
     * @param field   the field of identifiers (from the settings)
     * @param parents the parents' identifiers (one resource under several addresses)
     */
    public record ChildrenOf(String field, List<String> parents) implements Filter {
    }

    /**
     * A filter the facet of the same identifier ignores when it counts, so the other values of a
     * facet stay selectable once one is selected.
     *
     * @param tag    the facet identifier (from the settings)
     * @param filter the filter
     */
    public record Tagged(String tag, Filter filter) implements Filter {
    }

    /** Counts of the values of a field among the matching documents. */
    public sealed interface Facet permits Terms, Years {

        /**
         * The facet's identifier, under which {@link IndexResult#facets()} reports its counts.
         *
         * @return the identifier
         */
        String id();

        /**
         * The field counted.
         *
         * @return the field
         */
        String field();

    }

    /**
     * The most frequent values of a field.
     *
     * @param id          the facet identifier
     * @param field       the exact-value field
     * @param limit       the most values counted
     * @param sortByValue whether values are listed alphabetically instead of most frequent first
     */
    public record Terms(String id, String field, int limit, boolean sortByValue) implements Facet {
    }

    /**
     * Ranges of years of a date field.
     *
     * @param id    the facet identifier
     * @param field the date field
     * @param start the first year
     * @param end   the year the last range ends before
     * @param gap   the years per range
     */
    public record Years(String id, String field, int start, int end, int gap) implements Facet {
    }

    /**
     * The first instant of a year, as both backends write dates.
     *
     * @param year the year
     * @return for example {@code 1850-01-01T00:00:00Z}
     */
    public static String startOfYear(final int year) {
        return String.format(Locale.ROOT, "%04d-01-01T00:00:00Z", year);
    }

}
