package com.articulorium.harvest.core;

/**
 * An external repository records are harvested from: the source-specific half of a harvest.
 *
 * <p>A source turns its repository's API or protocol into pages of {@link HarvestedRecord}s in the
 * shared ingest representation. It knows nothing about where records go: the {@link Harvester}
 * hands every record to a {@link RecordIngestor}. A source pages with an opaque cursor of its own
 * making (an OAI-PMH resumption token, a search offset, the last identifier seen), so the
 * harvester can page through any protocol the same way.</p>
 *
 * <p>Implementations are created per configured source by a {@link HarvestSourceFactory} and may
 * keep state between pages of one harvest (cached protocol settings), but the harvester calls one
 * source from one thread at a time.</p>
 */
public interface HarvestSource {

    /**
     * Returns the identifier of the configured source (unique among the configured sources).
     *
     * @return the identifier
     */
    String id();

    /**
     * Returns the type of the source ({@code oai-pmh}, {@code dspace}, ...), the
     * {@link HarvestSourceFactory#type() type} of the factory that created it.
     *
     * @return the type
     */
    String type();

    /**
     * Tells what the source's protocol supports.
     *
     * @return the capabilities
     */
    SourceCapabilities capabilities();

    /**
     * Fetches one page of records.
     *
     * @param window which changes to harvest; {@link HarvestWindow#from()} is only set when the
     *               source is {@link SourceCapabilities#incremental() incremental}
     * @param cursor the {@link HarvestPage#nextCursor() cursor} of the previous page, or
     *               {@code null} for the first page
     * @return the page
     * @throws HarvestException when the page cannot be fetched; {@link HarvestException#isRecoverable()
     *                          recoverable} failures (network errors, 5xx, 429) are retried
     */
    HarvestPage fetch(HarvestWindow window, String cursor);

}
