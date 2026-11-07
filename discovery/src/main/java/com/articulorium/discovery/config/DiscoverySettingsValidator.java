package com.articulorium.discovery.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Checks the deployment's discovery settings at start-up, so a typo in a color, a facet or a sort
 * order stops the service with a clear message instead of producing broken pages.
 *
 * <p>Values that end up in the pages' styles (colors, fonts, lengths, CSS) or links are also
 * restricted to safe shapes: settings come from operators, but a value that closes a style element
 * or opens a {@code javascript:} link must never reach a page.</p>
 */
@Component
public class DiscoverySettingsValidator implements InitializingBean {

    /** The Material 3 system color tokens a theme may set ({@code --mat-sys-<token>}). */
    public static final Set<String> COLOR_TOKENS = Set.of(
        "primary", "on-primary", "primary-container", "on-primary-container",
        "secondary", "on-secondary", "secondary-container", "on-secondary-container",
        "tertiary", "on-tertiary", "tertiary-container", "on-tertiary-container",
        "error", "on-error", "error-container", "on-error-container",
        "background", "on-background", "surface", "on-surface", "surface-variant", "on-surface-variant",
        "surface-container-lowest", "surface-container-low", "surface-container", "surface-container-high",
        "surface-container-highest", "surface-bright", "surface-dim", "surface-tint",
        "outline", "outline-variant", "inverse-surface", "inverse-on-surface", "inverse-primary", "shadow",
        "scrim");

    private static final Pattern COLOR = Pattern.compile(
        "#[0-9a-fA-F]{3,8}|(rgb|rgba|hsl|hsla|oklch|oklab|lab|lch|color-mix)\\([0-9a-zA-Z.,%#\\s/+-]{1,120}\\)|[a-zA-Z]{3,30}");

    private static final Pattern FONT_FAMILY = Pattern.compile("[\\p{L}\\p{N} ,'\"_-]{1,200}");

    private static final Pattern LENGTH = Pattern.compile("[0-9]{1,5}(\\.[0-9]{1,3})?(px|rem|em|vh|vw|%|ch)");

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    private static final Pattern SOLR_FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]{0,99}");

    private static final Pattern SOLR_SORT = Pattern.compile(
        "[A-Za-z_][A-Za-z0-9_.]{0,99} (asc|desc)(, ?[A-Za-z_][A-Za-z0-9_.]{0,99} (asc|desc)){0,4}");

    private static final Pattern TWITTER = Pattern.compile("@[A-Za-z0-9_]{1,30}");

    private static final Set<String> COLOR_SCHEMES = Set.of("light", "dark", "system");

    private static final Set<String> THUMBNAIL_NAVIGATION = Set.of("far-bottom", "far-right", "off");

    private final DiscoveryProperties properties;

    /**
     * Creates the validator.
     *
     * @param properties the settings
     */
    public DiscoverySettingsValidator(final DiscoveryProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        final List<String> problems = validate(properties);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid articulorium.discovery settings:\n  - "
                + String.join("\n  - ", problems));
        }
    }

    /**
     * Lists what is wrong with settings.
     *
     * @param settings the settings
     * @return one line per problem, empty when the settings are valid
     */
    public static List<String> validate(final DiscoveryProperties settings) {
        final List<String> problems = new ArrayList<>();
        final URI publicUrl = absolute(settings.getPublicUrl());
        if (publicUrl == null || !settings.getPublicUrl().endsWith("/")) {
            problems.add("public-url must be an absolute http(s) URL ending with /: " + settings.getPublicUrl());
        }
        link("platform-public-url", settings.getPlatformPublicUrl(), problems);
        link("transform-public-url", settings.getTransformPublicUrl(), problems);
        site(settings.getSite(), problems);
        theme(settings.getTheme(), problems);
        layout(settings.getLayout(), problems);
        viewer(settings.getViewer(), problems);
        search(settings.getSearch(), problems);
        if (settings.getLayout().getResults().getPageSize() > settings.getSearch().getMaxRows()) {
            problems.add("layout.results.page-size must not exceed search.max-rows");
        }
        return problems;
    }

    private static void site(final DiscoveryProperties.Site site, final List<String> problems) {
        if (site.getName() == null || (!StringUtils.hasText(site.getName().getValue())
                && !StringUtils.hasText(site.getName().getKey()))) {
            problems.add("site.name needs a value");
        }
        link("site.logo-url", site.getLogoUrl(), problems);
        link("site.favicon-url", site.getFaviconUrl(), problems);
        link("site.default-image-url", site.getDefaultImageUrl(), problems);
        link("site.organization-url", site.getOrganizationUrl(), problems);
        if (StringUtils.hasText(site.getTwitterSite()) && !TWITTER.matcher(site.getTwitterSite()).matches()) {
            problems.add("site.twitter-site must be an @handle: " + site.getTwitterSite());
        }
    }

    private static void theme(final DiscoveryProperties.Theme theme, final List<String> problems) {
        if (!COLOR_SCHEMES.contains(theme.getColorScheme())) {
            problems.add("theme.color-scheme must be light, dark or system: " + theme.getColorScheme());
        }
        colors("theme.colors", theme.getColors(), problems);
        colors("theme.dark-colors", theme.getDarkColors(), problems);
        final DiscoveryProperties.Typography typography = theme.getTypography();
        font("theme.typography.brand-family", typography.getBrandFamily(), problems);
        font("theme.typography.plain-family", typography.getPlainFamily(), problems);
        link("theme.typography.stylesheet-url", typography.getStylesheetUrl(), problems);
        length("theme.typography.base-size", typography.getBaseSize(), problems);
        length("theme.content-width", theme.getContentWidth(), problems);
        if (theme.getCornerRadius() != null && (theme.getCornerRadius() < 0 || theme.getCornerRadius() > 64)) {
            problems.add("theme.corner-radius must be between 0 and 64");
        }
        if (theme.getDensity() > 0 || theme.getDensity() < -4) {
            problems.add("theme.density must be between -4 and 0");
        }
        link("theme.stylesheet-url", theme.getStylesheetUrl(), problems);
        if (theme.getCustomCss() != null && theme.getCustomCss().contains("<")) {
            problems.add("theme.custom-css must not contain <");
        }
    }

    private static void layout(final DiscoveryProperties.Layout layout, final List<String> problems) {
        layout.getHeader().getLinks().forEach(link -> navigation("layout.header.links", link, problems));
        layout.getFooter().getLinks().forEach(link -> navigation("layout.footer.links", link, problems));
        final DiscoveryProperties.Results results = layout.getResults();
        if (results.getPageSize() < 1) {
            problems.add("layout.results.page-size must be positive");
        }
        if (results.getPageSizes().stream().anyMatch(size -> size < 1)) {
            problems.add("layout.results.page-sizes must be positive");
        }
        if (layout.getHome().getSections().isEmpty()) {
            problems.add("layout.home.sections must list at least one section");
        }
        fields("layout.item.fields", layout.getItem().getFields().stream()
            .map(DiscoveryProperties.Field::getField).toList(), problems);
        link("layout.item.repository-ui-url", layout.getItem().getRepositoryUiUrl(), problems);
    }

    private static void viewer(final DiscoveryProperties.Viewer viewer, final List<String> problems) {
        length("viewer.height", viewer.getHeight(), problems);
        if (!THUMBNAIL_NAVIGATION.contains(viewer.getThumbnailNavigation())) {
            problems.add("viewer.thumbnail-navigation must be far-bottom, far-right or off");
        }
    }

    private static void search(final DiscoveryProperties.Search search, final List<String> problems) {
        if (search.getFields().isEmpty()) {
            problems.add("search.fields must list at least one field");
        }
        fields("search.fields", search.getFields().stream().map(DiscoveryProperties.SearchField::getField).toList(),
            problems);
        fields("search.phrase-fields", search.getPhraseFields().stream()
            .map(DiscoveryProperties.SearchField::getField).toList(), problems);
        search.getFields().stream().filter(field -> field.getBoost() <= 0).forEach(field ->
            problems.add("search.fields." + field.getField() + ".boost must be positive"));
        final Set<String> ids = new HashSet<>();
        for (final DiscoveryProperties.Facet facet : search.getFacets()) {
            identifier("search.facets", facet.getId(), ids, problems);
            fields("search.facets." + facet.getId() + ".field", List.of(String.valueOf(facet.getField())), problems);
            if (facet.getLimit() < 1 || facet.getLimit() > 200) {
                problems.add("search.facets." + facet.getId() + ".limit must be between 1 and 200");
            }
            if (facet.getType() == DiscoveryProperties.FacetType.YEAR_RANGE && facet.getGap() < 1) {
                problems.add("search.facets." + facet.getId() + ".gap must be positive");
            }
        }
        for (final DiscoveryProperties.Filter filter : search.getFilters()) {
            identifier("search.filters", filter.getId(), ids, problems);
            if (!StringUtils.hasText(filter.getQuery())) {
                problems.add("search.filters." + filter.getId() + ".query is missing");
            }
        }
        final Set<String> sorts = new HashSet<>();
        for (final DiscoveryProperties.Sort sort : search.getSorts()) {
            identifier("search.sorts", sort.getId(), sorts, problems);
            sort("search.sorts." + sort.getId() + ".value", sort.getValue(), problems);
        }
        sort("search.browse-sort", search.getBrowseSort(), problems);
        fields("search.title-field", List.of(String.valueOf(search.getTitleField())), problems);
        fields("search.description-field", List.of(String.valueOf(search.getDescriptionField())), problems);
        if (StringUtils.hasText(search.getDateField())) {
            fields("search.date-field", List.of(search.getDateField()), problems);
        }
        if (StringUtils.hasText(search.getThumbnailField())) {
            fields("search.thumbnail-field", List.of(search.getThumbnailField()), problems);
        }
        fields("search.type-field", List.of(String.valueOf(search.getTypeField())), problems);
        fields("search.modified-field", List.of(String.valueOf(search.getModifiedField())), problems);
        if (search.getMaxRows() < 1 || search.getMaxRows() > 1000) {
            problems.add("search.max-rows must be between 1 and 1000");
        }
    }

    private static void identifier(final String setting, final String id, final Set<String> seen,
            final List<String> problems) {
        if (id == null || !IDENTIFIER.matcher(id).matches()) {
            problems.add(setting + ": identifiers are lower case letters, digits and -: " + id);
        } else if (!seen.add(id)) {
            problems.add(setting + ": identifier " + id + " is used twice");
        }
    }

    private static void fields(final String setting, final List<String> fields, final List<String> problems) {
        for (final String field : fields) {
            if (field == null || !SOLR_FIELD.matcher(field).matches()) {
                problems.add(setting + ": not a Solr field name: " + field);
            }
        }
    }

    private static void sort(final String setting, final String value, final List<String> problems) {
        if (StringUtils.hasText(value) && !SOLR_SORT.matcher(value.trim()).matches()) {
            problems.add(setting + " must be \"<field> asc|desc\": " + value);
        }
    }

    private static void colors(final String setting, final Map<String, String> colors, final List<String> problems) {
        colors.forEach((token, color) -> {
            if (!COLOR_TOKENS.contains(token.toLowerCase(Locale.ROOT))) {
                problems.add(setting + ": unknown color token " + token + " (known: Material 3 system colors)");
            }
            if (color == null || !COLOR.matcher(color.trim()).matches()) {
                problems.add(setting + "." + token + " is not a CSS color: " + color);
            }
        });
    }

    private static void font(final String setting, final String family, final List<String> problems) {
        if (StringUtils.hasText(family) && !FONT_FAMILY.matcher(family).matches()) {
            problems.add(setting + " is not a font family list: " + family);
        }
    }

    private static void length(final String setting, final String length, final List<String> problems) {
        if (StringUtils.hasText(length) && !LENGTH.matcher(length.trim()).matches()) {
            problems.add(setting + " is not a CSS length: " + length);
        }
    }

    private static void navigation(final String setting, final DiscoveryProperties.Link link,
            final List<String> problems) {
        if (link.getLabel() == null) {
            problems.add(setting + ": a link needs a label");
        }
        if (!StringUtils.hasText(link.getUrl())) {
            problems.add(setting + ": a link needs a url");
        } else {
            link(setting, link.getUrl(), problems);
        }
    }

    /**
     * Checks a link: an absolute http(s) URL, or a reference without a scheme.
     *
     * @param setting  the setting, for messages
     * @param value    the link, may be empty
     * @param problems where problems are added
     */
    static void link(final String setting, final String value, final List<String> problems) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        try {
            final URI uri = new URI(value.trim());
            if (uri.getScheme() != null && !"http".equalsIgnoreCase(uri.getScheme())
                    && !"https".equalsIgnoreCase(uri.getScheme())) {
                problems.add(setting + " must be an http(s) URL or a relative reference: " + value);
            }
        } catch (URISyntaxException e) {
            problems.add(setting + " is not a URL: " + value);
        }
        if (value.indexOf('"') >= 0 || value.indexOf('<') >= 0 || value.indexOf('>') >= 0
                || value.indexOf('\'') >= 0 || value.indexOf(')') >= 0 || value.indexOf('(') >= 0) {
            problems.add(setting + " must not contain quotes, angle brackets or parentheses: " + value);
        }
    }

    private static URI absolute(final String value) {
        try {
            final URI uri = value == null ? null : new URI(value);
            return uri != null && uri.isAbsolute() && uri.getHost() != null
                && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) ? uri : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

}
