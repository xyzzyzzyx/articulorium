package com.articulorum.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.articulorum.domain.BinaryContent;
import com.articulorum.domain.InteractionModel;
import com.articulorum.domain.LdpResource;
import com.articulorum.domain.vocabulary.Ldp;
import com.articulorum.domain.vocabulary.Rdf;
import com.articulorum.platform.service.ResolvedTarget;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HtmlView}.
 */
class HtmlViewTest {

    private static final String BASE = "http://localhost";

    private final HtmlView view = new HtmlView();

    @Test
    void containerPageListsChildrenAndOffersUploadForms() {
        final LdpResource container = new LdpResource("books", InteractionModel.BASIC_CONTAINER);
        final Model model = ModelFactory.createDefaultModel();
        model.createResource(BASE + "/books")
            .addProperty(model.createProperty(Ldp.CONTAINS), model.createResource(BASE + "/books/moby-dick"))
            .addProperty(model.createProperty(Rdf.RDFS_LABEL), "Books");

        final String html = view.render(new ResolvedTarget(container, false), model, BASE);

        assertThat(html).contains("<title>Books · Articulorum</title>");
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
        assertThat(HtmlView.humanSize(10)).isEqualTo("10 B");
        assertThat(HtmlView.humanSize(1536)).isEqualTo("1.5 KB");
        assertThat(HtmlView.humanSize(5L * 1024 * 1024 * 1024)).isEqualTo("5.0 GB");
    }

}
