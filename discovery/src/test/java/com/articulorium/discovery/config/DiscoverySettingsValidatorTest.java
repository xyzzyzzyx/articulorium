package com.articulorium.discovery.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the start-up checks of the discovery settings.
 */
class DiscoverySettingsValidatorTest {

    private DiscoveryProperties properties;

    @BeforeEach
    void setUp() {
        properties = new DiscoveryProperties();
        final DiscoveryProperties.SearchField title = new DiscoveryProperties.SearchField();
        title.setField("title");
        title.setBoost(10);
        properties.getSearch().getFields().add(title);
    }

    private static DiscoveryProperties.Facet facet(final String id, final String field) {
        final DiscoveryProperties.Facet facet = new DiscoveryProperties.Facet();
        facet.setId(id);
        facet.setField(field);
        return facet;
    }

    @Test
    void acceptsTheDefaults() {
        assertThat(DiscoverySettingsValidator.validate(properties)).isEmpty();
    }

    @Test
    void acceptsAThemedDeployment() {
        properties.getTheme().setColors(Map.of("primary", "#1b5e7a", "on-primary", "white",
            "surface", "rgb(250 250 250)", "outline", "oklch(70% 0.1 200)"));
        properties.getTheme().getTypography().setBrandFamily("\"Source Serif 4\", Georgia, serif");
        properties.getTheme().setCornerRadius(4);
        properties.getTheme().setDensity(-2);
        properties.getTheme().setCustomCss(".disc-hero { background: var(--mat-sys-primary-container); }");
        properties.getSite().setLogoUrl("/branding/logo.svg");
        properties.getSite().setTwitterSite("@library");
        properties.getSearch().getFacets().add(facet("type", "type_str"));

        assertThat(DiscoverySettingsValidator.validate(properties)).isEmpty();
    }

    @Test
    void refusesValuesThatCouldBreakOutOfStyles() {
        properties.getTheme().setColors(Map.of("primary", "red;} body{display:none", "brand", "#fff"));
        properties.getTheme().setCustomCss("</style><script>alert(1)</script>");
        properties.getTheme().getTypography().setBrandFamily("x;}</style>");
        properties.getTheme().setContentWidth("100px; color: red");

        final List<String> problems = DiscoverySettingsValidator.validate(properties);

        assertThat(problems).anyMatch(problem -> problem.contains("theme.colors.primary is not a CSS color"))
            .anyMatch(problem -> problem.contains("unknown color token brand"))
            .anyMatch(problem -> problem.contains("theme.custom-css must not contain <"))
            .anyMatch(problem -> problem.contains("theme.typography.brand-family"))
            .anyMatch(problem -> problem.contains("theme.content-width"));
    }

    @Test
    void refusesScriptLinks() {
        properties.getSite().setLogoUrl("javascript:alert(1)");
        final DiscoveryProperties.Link link = new DiscoveryProperties.Link();
        link.setLabel(DiscoveryProperties.Text.of("About"));
        link.setUrl("data:text/html,<b>x</b>");
        properties.getLayout().getFooter().getLinks().add(link);

        assertThat(DiscoverySettingsValidator.validate(properties))
            .anyMatch(problem -> problem.startsWith("site.logo-url"))
            .anyMatch(problem -> problem.startsWith("layout.footer.links"));
    }

    @Test
    void refusesMalformedSearchSettings() {
        properties.getSearch().getFacets().add(facet("type", "type_str"));
        properties.getSearch().getFacets().add(facet("type", "other_str"));
        properties.getSearch().getFacets().add(facet("Bad Id", "x"));
        properties.getSearch().getFacets().add(facet("injected", "type_str OR id:*"));
        final DiscoveryProperties.Sort sort = new DiscoveryProperties.Sort();
        sort.setId("title");
        sort.setValue("title_str asc; drop");
        properties.getSearch().getSorts().add(sort);

        assertThat(DiscoverySettingsValidator.validate(properties))
            .anyMatch(problem -> problem.contains("identifier type is used twice"))
            .anyMatch(problem -> problem.contains("Bad Id"))
            .anyMatch(problem -> problem.contains("not a Solr field name: type_str OR id:*"))
            .anyMatch(problem -> problem.contains("search.sorts.title.value"));
    }

    @Test
    void needsAnAbsolutePublicUrl() {
        properties.setPublicUrl("/discover/");
        assertThat(DiscoverySettingsValidator.validate(properties)).anyMatch(problem -> problem.startsWith("public-url"));

        properties.setPublicUrl("https://collections.example.org/discover");
        assertThat(DiscoverySettingsValidator.validate(properties)).anyMatch(problem -> problem.startsWith("public-url"));
    }

    @Test
    void needsSearchFields() {
        properties.getSearch().getFields().clear();
        assertThat(DiscoverySettingsValidator.validate(properties)).contains("search.fields must list at least one field");
    }

}
