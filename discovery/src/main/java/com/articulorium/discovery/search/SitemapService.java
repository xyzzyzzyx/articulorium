package com.articulorium.discovery.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.articulorium.discovery.access.AccessVerifier;
import com.articulorium.discovery.config.DiscoveryProperties;
import com.articulorium.discovery.config.PublicUrls;
import com.articulorium.discovery.solr.SolrSearchClient;
import com.fasterxml.jackson.annotation.JsonInclude;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

/**
 * Lists the resources anonymous visitors may read, most recently modified first, for the sitemaps
 * the discovery layer offers search engines.
 */
@Service
public class SitemapService {

    /** Documents read from Solr per request. */
    static final int BATCH = 500;

    private final SolrSearchClient solr;

    private final AccessVerifier access;

    private final PublicUrls urls;

    private final DiscoveryProperties properties;

    private final SearchService search;

    /**
     * Creates the service.
     *
     * @param solr       the Solr client
     * @param access     the access checks
     * @param urls       the public addresses
     * @param properties the settings
     * @param search     the search (its base filters)
     */
    public SitemapService(final SolrSearchClient solr, final AccessVerifier access, final PublicUrls urls,
            final DiscoveryProperties properties, final SearchService search) {
        this.solr = solr;
        this.access = access;
        this.urls = urls;
        this.properties = properties;
        this.search = search;
    }

    /**
     * Lists the public resources.
     *
     * @return at most {@code articulorium.discovery.seo.sitemap-limit} entries; none when sitemaps
     *     or indexing are turned off
     */
    public List<Entry> entries() {
        final DiscoveryProperties.Seo seo = properties.getSeo();
        if (!seo.isSitemap() || seo.isNoindex()) {
            return List.of();
        }
        final String modified = properties.getSearch().getModifiedField();
        final List<Entry> entries = new ArrayList<>();
        final int limit = Math.max(0, seo.getSitemapLimit());
        int start = 0;
        while (entries.size() < limit) {
            final MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("q", "*:*");
            search.baseFilters().forEach(filter -> params.add("fq", filter));
            params.add("fl", "id," + modified);
            params.add("sort", modified + " desc, id asc");
            params.add("start", Integer.toString(start));
            params.add("rows", Integer.toString(BATCH));
            final JsonNode docs = solr.select(params).path("response").path("docs");
            if (!docs.isArray() || docs.isEmpty()) {
                break;
            }
            final Map<String, String> batch = new LinkedHashMap<>();
            for (final JsonNode doc : docs) {
                final String path = urls.pathOf(doc.path("id").asString(null));
                if (path != null && !path.isEmpty()) {
                    batch.putIfAbsent(path, SolrDocuments.first(doc, modified, null));
                }
            }
            for (final String path : access.readable(batch.keySet(), null)) {
                if (entries.size() >= limit) {
                    break;
                }
                entries.add(new Entry(path, batch.get(path)));
            }
            if (docs.size() < BATCH) {
                break;
            }
            start += BATCH;
        }
        return entries;
    }

    /**
     * A resource of the sitemap.
     *
     * @param path     its path
     * @param modified when it was last modified, {@code null} when unknown
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(String path, @Nullable String modified) {
    }

}
