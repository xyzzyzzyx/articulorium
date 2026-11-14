package com.articulorium.discovery.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.articulorium.discovery.config.DiscoveryProperties;
import com.articulorium.discovery.config.PublicUrls;
import com.articulorium.i18n.LocaleDescriptor;
import com.articulorium.i18n.SupportedLocales;
import com.fasterxml.jackson.annotation.JsonInclude;

import org.jspecify.annotations.Nullable;

/**
 * What the discovery layer's user interface needs to render a deployment: its identity, theme,
 * layouts, viewer and search options, search engine directives, locales and public addresses.
 *
 * <p>Built from the settings, but only from their public part: internal addresses (Solr, the
 * platform's and the transform service's internal URLs), base filters, boosts and Solr fields of
 * facets never leave the service.</p>
 *
 * @param site    the site's identity
 * @param theme   colors, typography, shape and extra styles
 * @param layout  page layouts and presentation options
 * @param viewer  the Mirador viewer
 * @param search  facets, filters and sort orders offered
 * @param seo     search engine directives
 * @param locales the enabled locales
 * @param urls    public addresses
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClientConfiguration(Site site, DiscoveryProperties.Theme theme, Layout layout,
        DiscoveryProperties.Viewer viewer, Search search, Seo seo, Locales locales, Urls urls) {

    /**
     * Builds the configuration of the user interface.
     *
     * @param properties the settings
     * @param urls       the public addresses
     * @param locales    the enabled locales
     * @return the configuration
     */
    public static ClientConfiguration of(final DiscoveryProperties properties, final PublicUrls urls,
            final SupportedLocales locales) {
        final DiscoveryProperties.Site site = properties.getSite();
        final DiscoveryProperties.Layout layout = properties.getLayout();
        final DiscoveryProperties.Search search = properties.getSearch();
        final DiscoveryProperties.Seo seo = properties.getSeo();
        return new ClientConfiguration(
            new Site(site.getName(), site.getDescription(), site.getTagline(), urls.absolute(site.getLogoUrl()),
                urls.absolute(site.getFaviconUrl()), urls.absolute(site.getDefaultImageUrl()),
                site.getOrganizationName(), urls.absolute(site.getOrganizationUrl()), site.getTwitterSite()),
            theme(properties.getTheme(), urls),
            new Layout(layout.getHeader(), layout.getFooter(), layout.getHome(), layout.getResults(),
                new Item(layout.getItem().getViewerPosition(), layout.getItem().getFields().stream()
                    .map(field -> new Field(field.getField(), field.getLabel(), field.isLink())).toList(),
                    layout.getItem().getMembersLimit()),
                layout.getCollections()),
            properties.getViewer(),
            new Search(search.getFacets().stream()
                .map(facet -> new Facet(facet.getId(), facet.getLabel(), facet.getType().name(), facet.getLimit(),
                    facet.isExpanded(), facet.getValues()))
                .toList(),
                search.getFilters().stream().map(filter -> new Choice(filter.getId(), filter.getLabel())).toList(),
                search.getSorts().stream().map(sort -> new Choice(sort.getId(), sort.getLabel())).toList(),
                search.getSorts().isEmpty() ? null : search.getSorts().get(0).getId()),
            new Seo(seo.getRobots(), seo.getSearchRobots(), seo.isNoindex(), seo.getTwitterCard(),
                seo.isSitemap() && !seo.isNoindex()),
            new Locales(locales.defaultLocale().tag(), locales.all()),
            new Urls(urls.publicUrl(), urls.platformBase(), urls.transformBase() + "iiif/"));
    }

    private static DiscoveryProperties.Theme theme(final DiscoveryProperties.Theme source, final PublicUrls urls) {
        final DiscoveryProperties.Theme theme = new DiscoveryProperties.Theme();
        theme.setColorScheme(source.getColorScheme());
        theme.setColors(new LinkedHashMap<>(source.getColors()));
        theme.setDarkColors(new LinkedHashMap<>(source.getDarkColors()));
        final DiscoveryProperties.Typography typography = new DiscoveryProperties.Typography();
        typography.setBrandFamily(source.getTypography().getBrandFamily());
        typography.setPlainFamily(source.getTypography().getPlainFamily());
        typography.setStylesheetUrl(urls.absolute(source.getTypography().getStylesheetUrl()));
        typography.setBaseSize(source.getTypography().getBaseSize());
        theme.setTypography(typography);
        theme.setCornerRadius(source.getCornerRadius());
        theme.setDensity(source.getDensity());
        theme.setContentWidth(source.getContentWidth());
        theme.setStylesheetUrl(urls.absolute(source.getStylesheetUrl()));
        theme.setCustomCss(source.getCustomCss());
        return theme;
    }

    /**
     * The site's identity; links are absolute.
     *
     * @param name             the name
     * @param description      the description
     * @param tagline          the tagline
     * @param logoUrl          the logo
     * @param faviconUrl       the favicon
     * @param defaultImageUrl  the image shared when a page has none
     * @param organizationName the publisher
     * @param organizationUrl  the publisher's web site
     * @param twitterSite      the site's X/Twitter handle
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Site(DiscoveryProperties.Text name, DiscoveryProperties.@Nullable Text description,
            DiscoveryProperties.@Nullable Text tagline, @Nullable String logoUrl, @Nullable String faviconUrl,
            @Nullable String defaultImageUrl, @Nullable String organizationName, @Nullable String organizationUrl,
            @Nullable String twitterSite) {
    }

    /**
     * Page layouts.
     *
     * @param header  the header
     * @param footer  the footer
     * @param home    the home page
     * @param results the search page
     * @param item        resource pages
     * @param collections the communities and collections page
     */
    public record Layout(DiscoveryProperties.Header header, DiscoveryProperties.Footer footer,
            DiscoveryProperties.Home home, DiscoveryProperties.Results results, Item item,
            DiscoveryProperties.Collections collections) {
    }

    /**
     * Resource pages.
     *
     * @param viewerPosition where the viewer is
     * @param fields         the metadata fields listed
     * @param membersLimit   members listed per page on community and collection pages
     */
    public record Item(DiscoveryProperties.ViewerPosition viewerPosition, List<Field> fields, int membersLimit) {
    }

    /**
     * A metadata field of resource pages.
     *
     * @param field its identifier (the Solr field)
     * @param label its label, {@code null} for the translation key {@code fields.<field>}
     * @param link  whether values are links
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Field(String field, DiscoveryProperties.@Nullable Text label, boolean link) {
    }

    /**
     * Search options.
     *
     * @param facets      the facets, in order
     * @param filters     the filters
     * @param sorts       the sort orders
     * @param defaultSort the sort order of searches with terms
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Search(List<Facet> facets, List<Choice> filters, List<Choice> sorts, @Nullable String defaultSort) {
    }

    /**
     * A facet.
     *
     * @param id       its identifier
     * @param label    its label, {@code null} for the translation key {@code facets.<id>}
     * @param type     {@code TERMS} or {@code YEAR_RANGE}
     * @param limit    values listed
     * @param expanded whether it starts expanded
     * @param values   labels of values
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Facet(String id, DiscoveryProperties.@Nullable Text label, String type, int limit, boolean expanded,
            Map<String, DiscoveryProperties.Text> values) {
    }

    /**
     * A filter or a sort order.
     *
     * @param id    its identifier
     * @param label its label, {@code null} for the translation key {@code filters.<id>} or
     *              {@code sorts.<id>}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Choice(String id, DiscoveryProperties.@Nullable Text label) {
    }

    /**
     * Search engine directives.
     *
     * @param robots       robots directives of indexable pages
     * @param searchRobots robots directives of search pages
     * @param noindex      whether nothing is indexed
     * @param twitterCard  the Twitter card type
     * @param sitemap      whether sitemaps are offered
     */
    public record Seo(String robots, String searchRobots, boolean noindex, String twitterCard, boolean sitemap) {
    }

    /**
     * The enabled locales.
     *
     * @param defaultLocale the default locale's tag
     * @param available     every enabled locale, in menu order
     */
    public record Locales(String defaultLocale, List<LocaleDescriptor> available) {
    }

    /**
     * Public addresses.
     *
     * @param publicUrl the discovery layer
     * @param platform  the platform's LDP root
     * @param iiif      the base of IIIF exports ({@code <iiif><path>})
     */
    public record Urls(String publicUrl, String platform, String iiif) {
    }

}
