package com.articulorium.oai.platform;

import java.util.List;

/**
 * A page of a platform search.
 *
 * <p>The platform leaves out the hits the harvester may not read but still counts them, so
 * {@link #hits()} may be shorter than the page even when more results follow; whether more follow
 * is told by {@link #total()}.</p>
 *
 * @param hits       the readable hits, in search order
 * @param total      the number of matching resources, readable or not
 * @param maxResults the page size the platform applied (it caps the size asked for)
 */
public record SearchPage(List<SearchHit> hits, long total, int maxResults) {

    /**
     * Copies the hits.
     *
     * @param hits       the hits
     * @param total      the total
     * @param maxResults the applied page size
     */
    public SearchPage {
        hits = List.copyOf(hits);
        maxResults = Math.max(1, maxResults);
    }

    /**
     * How many results this page covers when read at an offset. The platform pages by multiples
     * of the page size: an offset inside a page only returns the rest of that page.
     *
     * @param offset the offset the page was read at
     * @return the number of results covered
     */
    public int covered(final int offset) {
        return maxResults - offset % maxResults;
    }

}
