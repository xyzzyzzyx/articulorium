package com.articulorium.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.articulorium.domain.BinaryContent;
import com.articulorium.domain.InteractionModel;
import com.articulorium.domain.LdpResource;
import com.articulorium.domain.vocabulary.Ldp;
import com.articulorium.domain.vocabulary.Rdf;
import com.articulorium.i18n.LocaleManifest;
import com.articulorium.i18n.Messages;
import com.articulorium.i18n.SupportedLocales;
import com.articulorium.platform.service.ResolvedTarget;

import java.util.Locale;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * Unit tests for {@link HtmlView}.
 */
class HtmlViewTest {

    private static final String BASE = "http://localhost";

    private final HtmlView view = new HtmlView(Messages.of(new SupportedLocales(LocaleManifest.load(), "en")));

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void containerPageListsChildrenAndOffersUploadForms() {
        final LdpResource container = new LdpResource("books", InteractionModel.BASIC_CONTAINER);
        final Model model = ModelFactory.createDefaultModel();
        model.createResource(BASE + "/books")
            .addProperty(model.createProperty(Ldp.CONTAINS), model.createResource(BASE + "/books/moby-dick"))
            .addProperty(model.createProperty(Rdf.RDFS_LABEL), "Books");

        final String html = view.render(new ResolvedTarget(container, false), model, BASE);

        assertThat(html).contains("<title>Books · Articulorium</title>");
        assertThat(html).contains("href=\"http://localhost/books/moby-dick\"");
        assertThat(html).contains("enctype=\"multipart/form-data\"");
        assertThat(html).contains("name=\"file\"").contains("value=\"container\"");
        assertThat(html).contains("data-delete=\"http://localhost/books/moby-dick\"");
    }

    @Test
    void escapesUserContent() {
        final LdpResource resource = new LdpResource("x", InteractionModel.RDF_SOURCE);
        final Model model = ModelFactory.createDefaultModel();
        model.createResource(BASE + "/x").addProperty(model.createProperty(Rdf.RDFS_LABEL), "<script>alert(1)</script>");

        final String html = view.render(new ResolvedTarget(resource, false), model, BASE);

        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    void keepsSubjectAndPredicateOnOneLineWhenTheObjectIsLong() {
        final LdpResource resource = new LdpResource("x", InteractionModel.RDF_SOURCE);
        final Model model = ModelFactory.createDefaultModel();
        model.createResource(BASE + "/x").addProperty(model.createProperty(Rdf.RDFS_LABEL), "word ".repeat(500));

        final String html = view.render(new ResolvedTarget(resource, false), model, BASE);

        assertThat(html).contains("<div class=\"table-scroll\"><table class=\"triples\">");
        assertThat(html).contains("table.triples td.term { width: 1%; white-space: nowrap;");
        assertThat(html).contains("<td class=\"mono term\"><span class=\"clip\" title=\"" + BASE + "/x\">");
        assertThat(html).contains("<td class=\"mono term\"><span class=\"clip\" title=\"" + Rdf.RDFS_LABEL + "\">");
        assertThat(html).contains("<td class=\"value\">word word");
    }

    @Test
    void rdfSourcesHaveNoUploadForms() {
        final LdpResource resource = new LdpResource("x", InteractionModel.RDF_SOURCE);
        final String html = view.render(new ResolvedTarget(resource, false), ModelFactory.createDefaultModel(), BASE);
        assertThat(html).doesNotContain("multipart/form-data");
    }

    @Test
    void descriptionPageLinksToTheBinary() {
        final LdpResource binary = new LdpResource("files/report.pdf", InteractionModel.NON_RDF_SOURCE);
        binary.setBinary(new BinaryContent("k", "v1", "application/pdf", "report.pdf", 2048, "ab"));

        final String html = view.render(new ResolvedTarget(binary, true), ModelFactory.createDefaultModel(), BASE);

        assertThat(html).contains("href=\"http://localhost/files/report.pdf\" download");
        assertThat(html).contains("report.pdf · application/pdf · 2.0 KB");
    }

    @Test
    void formatsSizes() {
        assertThat(HtmlView.humanSize(10, Locale.ENGLISH)).isEqualTo("10 B");
        assertThat(HtmlView.humanSize(1536, Locale.ENGLISH)).isEqualTo("1.5 KB");
        assertThat(HtmlView.humanSize(5L * 1024 * 1024 * 1024, Locale.ENGLISH)).isEqualTo("5.0 GB");
        assertThat(HtmlView.humanSize(1536, Locale.GERMAN)).isEqualTo("1,5 KB");
    }

    @Test
    void rendersInTheRequestLocale() {
        LocaleContextHolder.setLocale(Locale.GERMAN);
        final LdpResource container = new LdpResource("books", InteractionModel.BASIC_CONTAINER);

        final String html = view.render(new ResolvedTarget(container, false), ModelFactory.createDefaultModel(), BASE);

        assertThat(html).startsWith("<!doctype html><html lang=\"de\" dir=\"ltr\">");
        assertThat(html).contains("Datei hochladen").doesNotContain("Upload a file");
        assertThat(view.language()).isEqualTo("de");
    }

    @Test
    void prefersTitlesInTheReaderLanguage() {
        LocaleContextHolder.setLocale(Locale.GERMAN);
        final LdpResource resource = new LdpResource("x", InteractionModel.RDF_SOURCE);
        final Model model = ModelFactory.createDefaultModel();
        model.createResource(BASE + "/x")
            .addProperty(model.createProperty(Rdf.RDFS_LABEL), "Whale", "en")
            .addProperty(model.createProperty(Rdf.RDFS_LABEL), "Wal", "de");

        final String html = view.render(new ResolvedTarget(resource, false), model, BASE);

        assertThat(html).contains("<h1>Wal</h1>").contains("<span lang=\"en\">Whale</span>");
    }

    @Test
    void writesRightToLeftLocalesRightToLeft() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ar"));
        final String html = view.render(new ResolvedTarget(new LdpResource("x", InteractionModel.RDF_SOURCE), false),
            ModelFactory.createDefaultModel(), BASE);
        assertThat(html).startsWith("<!doctype html><html lang=\"ar\" dir=\"rtl\">");
    }

    @Test
    void fallsBackToTheDefaultLocale() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("sv"));
        final String html = view.render(new ResolvedTarget(new LdpResource("x", InteractionModel.RDF_SOURCE), false),
            ModelFactory.createDefaultModel(), BASE);
        assertThat(html).startsWith("<!doctype html><html lang=\"en\" dir=\"ltr\">").contains("No triples.");
    }

}
