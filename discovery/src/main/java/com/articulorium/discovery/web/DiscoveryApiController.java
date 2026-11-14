package com.articulorium.discovery.web;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.articulorium.discovery.config.DiscoveryProperties;
import com.articulorium.discovery.config.PublicUrls;
import com.articulorium.discovery.search.ItemService;
import com.articulorium.discovery.search.ResourceKind;
import com.articulorium.discovery.search.SearchRequest;
import com.articulorium.discovery.search.SearchResponse;
import com.articulorium.discovery.search.SearchService;
import com.articulorium.discovery.search.SitemapService;
import com.articulorium.i18n.SupportedLocales;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The API of the discovery layer's user interface: its configuration, searches, resource pages and
 * sitemap entries. The gateway serves it under {@code /discover/api/}, next to the server-rendered
 * pages.
 *
 * <p>Visitors' {@code Authorization} headers are passed to the platform for the access checks;
 * answers for anonymous visitors may be cached briefly by browsers and proxies, answers for
 * signed-in visitors are private.</p>
 */
@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Discovery", description = "Configuration, search and resources of the discovery layer")
public class DiscoveryApiController {

    /** How long anonymous answers may be cached. */
    static final Duration ANONYMOUS_MAX_AGE = Duration.ofSeconds(30);

    private final DiscoveryProperties properties;

    private final PublicUrls urls;

    private final SupportedLocales locales;

    private final SearchService search;

    private final ItemService items;

    private final SitemapService sitemap;

    /**
     * Creates the controller.
     *
     * @param properties the settings
     * @param urls       the public addresses
     * @param locales    the enabled locales
     * @param search     the search
     * @param items      the resource descriptions
     * @param sitemap    the sitemap entries
     */
    public DiscoveryApiController(final DiscoveryProperties properties, final PublicUrls urls,
            final SupportedLocales locales, final SearchService search, final ItemService items,
            final SitemapService sitemap) {
        this.properties = properties;
        this.urls = urls;
        this.locales = locales;
        this.search = search;
        this.items = items;
        this.sitemap = sitemap;
    }

    /**
     * The configuration of the user interface: identity, theme, layouts, viewer, facets, filters,
     * sort orders, search engine directives, locales and public addresses.
     *
     * @return the configuration
     */
    @GetMapping("/config")
    @Operation(operationId = "discoveryConfig", summary = "Configuration of the discovery layer")
    public ResponseEntity<ClientConfiguration> config() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
            .body(ClientConfiguration.of(properties, urls, locales));
    }

    /**
     * Searches the resources of the discovery layer the visitor may read.
     *
     * @param query         the search terms; empty to browse
     * @param page          the page, from 1
     * @param size          results per page; default {@code layout.results.page-size}
     * @param sort          a configured sort order
     * @param facets        selected facet values, {@code <facet>:<value>}
     * @param filters       filters switched on
     * @param within        only the direct children of this container
     * @param kinds         only communities, collections or objects (repeatable: any of them)
     * @param lang          the visitor's language
     * @param authorization the visitor's credentials
     * @return a page of results with facets
     */
    @GetMapping("/search")
    @Operation(operationId = "discoverySearch", summary = "Search the discovery layer")
    public ResponseEntity<SearchResponse> search(
            @RequestParam(name = "q", defaultValue = "") final String query,
            @RequestParam(defaultValue = "1") final int page,
            @RequestParam(defaultValue = "0") final int size,
            @RequestParam(required = false) final @Nullable String sort,
            @Parameter(description = "Selected facet values, <facet>:<value>")
            @RequestParam(name = "f", required = false) final @Nullable List<String> facets,
            @RequestParam(name = "filter", required = false) final @Nullable List<String> filters,
            @RequestParam(required = false) final @Nullable String within,
            @Parameter(description = "Only resources of these kinds: community, collection, object, resource")
            @RequestParam(name = "kind", required = false) final @Nullable List<String> kinds,
            @RequestParam(required = false) final @Nullable String lang,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) final @Nullable String authorization) {
        final Set<ResourceKind> resourceKinds = EnumSet.noneOf(ResourceKind.class);
        for (final String kind : kinds == null ? List.<String>of() : kinds) {
            if (!StringUtils.hasText(kind)) {
                continue;
            }
            try {
                resourceKinds.add(ResourceKind.parse(kind));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown kind of resource: " + kind, e);
            }
        }
        final SearchResponse response = search.search(new SearchRequest(query, page, size,
            StringUtils.hasText(sort) ? sort : null, selections(facets),
            filters == null ? Set.of() : new LinkedHashSet<>(filters), within, resourceKinds, locale(lang), authorization));
        return respond(response, authorization);
    }

    /**
     * Describes one resource for its page.
     *
     * @param path          the resource path
     * @param lang          the visitor's language
     * @param authorization the visitor's credentials
     * @return the resource
     */
    @GetMapping("/item")
    @Operation(operationId = "discoveryItem", summary = "Describe a resource of the discovery layer")
    public ResponseEntity<ItemService.ItemView> item(@RequestParam final String path,
            @RequestParam(required = false) final @Nullable String lang,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) final @Nullable String authorization) {
        return respond(items.item(path, locale(lang), authorization), authorization);
    }

    /**
     * Lists the resources anonymous visitors may read, for sitemaps.
     *
     * @return the resources, most recently modified first
     */
    @GetMapping("/sitemap")
    @Operation(operationId = "discoverySitemap", summary = "Public resources of the discovery layer, for sitemaps")
    public ResponseEntity<List<SitemapService.Entry>> sitemap() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
            .body(sitemap.entries());
    }

    private @Nullable String locale(@Nullable final String lang) {
        return StringUtils.hasText(lang) ? locales.match(lang).orElse(locales.defaultLocale()).tag()
            : locales.defaultLocale().tag();
    }

    private static Map<String, List<String>> selections(@Nullable final List<String> facets) {
        final Map<String, List<String>> selected = new LinkedHashMap<>();
        if (facets == null) {
            return selected;
        }
        for (final String facet : facets) {
            final int colon = facet.indexOf(':');
            if (colon <= 0 || colon == facet.length() - 1) {
                throw new IllegalArgumentException("Facet values are <facet>:<value>: " + facet);
            }
            selected.computeIfAbsent(facet.substring(0, colon), key -> new ArrayList<>()).add(facet.substring(colon + 1));
        }
        return selected;
    }

    private static <T> ResponseEntity<T> respond(final T body, @Nullable final String authorization) {
        final ResponseEntity.BodyBuilder response = ResponseEntity.ok().header(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION);
        return (StringUtils.hasText(authorization)
            ? response.cacheControl(CacheControl.noStore().cachePrivate())
            : response.cacheControl(CacheControl.maxAge(ANONYMOUS_MAX_AGE).cachePublic())).body(body);
    }

}
