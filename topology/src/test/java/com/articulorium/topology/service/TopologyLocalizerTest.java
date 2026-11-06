package com.articulorium.topology.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;

import com.articulorium.i18n.I18nAutoConfiguration;
import com.articulorium.i18n.LocaleDescriptor;
import com.articulorium.i18n.LocaleManifest;
import com.articulorium.i18n.MessageBundles;
import com.articulorium.i18n.Messages;
import com.articulorium.i18n.SupportedLocales;
import com.articulorium.topology.model.MetadataField;
import com.articulorium.topology.model.Topology;
import com.articulorium.topology.model.TopologyNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * Unit tests for {@link TopologyLocalizer} and the completeness of the topology bundle.
 */
class TopologyLocalizerTest {

    private final TopologyLocalizer localizer =
        new TopologyLocalizer(Messages.of(new SupportedLocales(LocaleManifest.load(), "en")));

    private static Topology topology(final boolean builtIn) {
        return new Topology("book", "Book", "A monograph", builtIn, new TopologyNode("book", "Book", "Object",
            List.of(), List.of(new MetadataField("http://purl.org/dc/terms/title", "Title", true, false, null, null),
                new MetadataField("http://example.org/x", "Shelf mark", false, false, null, null)),
            List.of(), List.of()));
    }

    @AfterEach
    void reset() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void translatesBuiltInTopologies() {
        LocaleContextHolder.setLocale(Locale.GERMAN);
        final Topology german = localizer.localize(topology(true));
        assertThat(german.name()).isEqualTo("Buch");
        assertThat(german.root().label()).isEqualTo("Buch");
        assertThat(german.root().metadata().get(0).label()).isEqualTo("Titel");
        // labels without a translation are kept
        assertThat(german.root().metadata().get(1).label()).isEqualTo("Shelf mark");
        assertThat(german.root().key()).isEqualTo("book");
    }

    @Test
    void keepsCustomTopologies() {
        LocaleContextHolder.setLocale(Locale.GERMAN);
        final Topology custom = topology(false);
        assertThat(localizer.localize(custom)).isSameAs(custom);
    }

    @Test
    void slugsLabels() {
        assertThat(TopologyLocalizer.slug("ISBN-13")).isEqualTo("isbn-13");
        assertThat(TopologyLocalizer.slug(" Issue date ")).isEqualTo("issue-date");
    }

    @Test
    void everyShippedLocaleHasEveryText() {
        final List<Locale> locales = LocaleManifest.load().stream().map(LocaleDescriptor::locale).toList();
        assertThat(MessageBundles.verify(I18nAutoConfiguration.MESSAGES, locales, Locale.ENGLISH)).isEmpty();
    }

}
