package com.articulorium.platform.web;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.articulorium.domain.BinaryContent;
import com.articulorium.domain.LdpResource;
import com.articulorium.domain.rdf.Iris;
import com.articulorium.domain.vocabulary.Ldp;
import com.articulorium.domain.vocabulary.Rdf;
import com.articulorium.i18n.LocaleDescriptor;
import com.articulorium.i18n.Messages;
import com.articulorium.platform.service.LdpService;
import com.articulorium.platform.service.ResolvedTarget;

import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Statement;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Renders the browser view of an LDP resource: its triples, the resources it contains and, for
 * containers, forms to upload files and create child containers.
 *
 * <p>The page is plain server-rendered HTML with a few lines of script for delete buttons; it
 * talks to the same LDP endpoints as any other client (multipart POST to create, DELETE to
 * remove). Every interpolated value is HTML-escaped.</p>
 *
 * <p>The page is written in the request's locale ({@code ?lang=}, the user interface's locale
 * cookie, {@code Accept-Language}): its texts, document language, writing direction and number
 * formats. Resource data (titles, triples) is shown as stored.</p>
 */
@Component
public class HtmlView {

    /** Multipart field holding an uploaded file. */
    public static final String FILE_FIELD = "file";

    /** Multipart field holding the suggested name. */
    public static final String SLUG_FIELD = "slug";

    /** Multipart field choosing what the form creates: {@value #KIND_FILE} or {@value #KIND_CONTAINER}. */
    public static final String KIND_FIELD = "kind";

    /** {@link #KIND_FIELD} value creating a non-RDF source from the uploaded file. */
    public static final String KIND_FILE = "file";

    /** {@link #KIND_FIELD} value creating an empty basic container. */
    public static final String KIND_CONTAINER = "container";

    private static final String STYLE = """
        :root { --bg:#fbfbfa; --fg:#1d1d1b; --muted:#6b6b66; --line:#e3e2dc; --accent:#2f5d8a; --card:#fff; }
        @media (prefers-color-scheme: dark) {
          :root { --bg:#161614; --fg:#ecebe6; --muted:#a19f97; --line:#34332f; --accent:#8ab4e0; --card:#1f1e1b; }
        }
        * { box-sizing: border-box; }
        body { margin:0; background:var(--bg); color:var(--fg);
               font:15px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }
        main { max-width: 1040px; margin: 0 auto; padding: 24px 16px 64px; }
        a { color: var(--accent); }
        nav { font-size: 13px; color: var(--muted); margin-bottom: 8px; word-break: break-all; }
        h1 { font-size: 24px; margin: 0 0 4px; }
        h2 { font-size: 16px; margin: 32px 0 8px; }
        .iri { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 13px; color: var(--muted);
               word-break: break-all; }
        .types { margin: 12px 0; display: flex; gap: 6px; flex-wrap: wrap; }
        .badge { border: 1px solid var(--line); border-radius: 999px; padding: 2px 10px; font-size: 12px; }
        table { width: 100%; border-collapse: collapse; background: var(--card); border: 1px solid var(--line); }
        th, td { text-align: left; vertical-align: top; padding: 6px 10px; border-bottom: 1px solid var(--line);
                 font-size: 13px; word-break: break-word; }
        th { color: var(--muted); font-weight: 600; }
        td.mono { font-family: ui-monospace, Menlo, Consolas, monospace; }
        .meta { color: var(--muted); font-size: 12px; }
        ul.children { list-style: none; padding: 0; margin: 0; border: 1px solid var(--line); background: var(--card); }
        ul.children li { display: flex; justify-content: space-between; gap: 12px; padding: 8px 12px;
                         border-bottom: 1px solid var(--line); }
        ul.children li:last-child { border-bottom: 0; }
        form { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; padding: 12px; margin: 0 0 8px;
               border: 1px solid var(--line); background: var(--card); }
        input[type=text] { padding: 6px 8px; border: 1px solid var(--line); background: var(--bg); color: var(--fg);
                           min-width: 200px; }
        button { padding: 6px 14px; border: 1px solid var(--accent); background: var(--accent); color: #fff;
                 cursor: pointer; border-radius: 4px; }
        button.danger { background: transparent; color: #b3261e; border-color: #b3261e; padding: 2px 10px; }
        .empty { color: var(--muted); padding: 12px; }
        """;

    private static final String SCRIPT = """
        document.querySelectorAll('button[data-delete]').forEach(function (button) {
          button.addEventListener('click', function () {
            var target = button.getAttribute('data-delete');
            if (!window.confirm(button.getAttribute('data-confirm'))) { return; }
            fetch(target, { method: 'DELETE' }).then(function (response) {
              if (response.ok) { window.location.reload(); }
              else { response.text().then(function (text) { window.alert(response.status + ': ' + text); }); }
            });
          });
        });
        """;

    private final Messages messages;

    /**
     * Creates the view.
     *
     * @param messages the texts in the request's locale
     */
    public HtmlView(final Messages messages) {
        this.messages = messages;
    }

    /**
     * Renders the page in the request's locale.
     *
     * @param target the displayed target
     * @param model  its rendered representation
     * @param base   the server base of the request
     * @return the HTML document
     */
    public String render(final ResolvedTarget target, final Model model, final String base) {
        return new Page(messages.descriptor()).render(target, model, base);
    }

    /**
     * The document language of the pages rendered for the current request.
     *
     * @return for example {@code de}
     */
    public String language() {
        return messages.descriptor().tag();
    }

    /** One rendering, in one locale. */
    private final class Page {

        private final LocaleDescriptor locale;

        Page(final LocaleDescriptor locale) {
            this.locale = locale;
        }

        private String t(final String code, final Object... arguments) {
            return escape(messages.get(locale.locale(), code, arguments));
        }

        String render(final ResolvedTarget target, final Model model, final String base) {
            final LdpResource resource = target.resource();
            final String iri = Iris.resolve(base, target.relativeIri());
            final String subjectIri = Iris.resolve(base, resource.getRelativeIri());
            final StringBuilder html = new StringBuilder(8192);
            html.append("<!doctype html><html lang=\"").append(escape(locale.tag())).append("\" dir=\"")
                .append(escape(locale.direction())).append("\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>").append(t("html.title", title(model, subjectIri, resource))).append("</title>")
                .append("<style>").append(STYLE).append("</style></head><body><main>");

            breadcrumbs(html, base, resource, target.description());
            html.append("<h1>").append(escape(title(model, subjectIri, resource))).append("</h1>")
                .append("<div class=\"iri\">").append(escape(iri)).append("</div>");

            html.append("<div class=\"types\">");
            for (final String type : resource.getInteractionModel().getTypeIris()) {
                html.append("<span class=\"badge\">").append(escape(model.shortForm(type))).append("</span>");
            }
            if (target.description()) {
                html.append("<span class=\"badge\">").append(t("html.description")).append("</span>");
            }
            html.append("</div>");

            if (resource.isNonRdfSource()) {
                binarySection(html, resource, subjectIri);
            }
            if (resource.isContainer() && !target.description()) {
                containerSection(html, model, subjectIri, iri);
            }
            triplesSection(html, model, subjectIri);

            html.append("<p class=\"meta\">").append(t("html.rdf-hint", "%1", "%2")
                    .replace("%1", "<code>Accept: text/turtle</code>")
                    .replace("%2", "<code>application/ld+json</code>"))
                .append(" <a href=\"").append(escape(Iris.normalizeBase(base) + ConstraintsController.PATH))
                .append("\">").append(t("html.constraints")).append("</a></p>");
            html.append("</main><script>").append(SCRIPT).append("</script></body></html>");
            return html.toString();
        }

        private void breadcrumbs(final StringBuilder html, final String base, final LdpResource resource,
                final boolean description) {
            html.append("<nav aria-label=\"").append(t("html.breadcrumb")).append("\"><a href=\"")
                .append(escape(Iris.resolve(base, "/"))).append("\">").append(t("html.root")).append("</a>");
            String path = "";
            if (!resource.getPath().isEmpty()) {
                for (final String segment : resource.getPath().split("/")) {
                    path = path.isEmpty() ? segment : path + "/" + segment;
                    html.append(" / <a href=\"").append(escape(Iris.resolve(base, "/" + path))).append("\">")
                        .append(escape(segment)).append("</a>");
                }
            }
            if (description) {
                html.append(" / ").append(LdpService.DESCRIPTION_SEGMENT);
            }
            html.append("</nav>");
        }

        private void binarySection(final StringBuilder html, final LdpResource resource, final String binaryIri) {
            final BinaryContent binary = resource.getBinary();
            html.append("<h2>").append(t("html.file")).append("</h2><p><a href=\"").append(escape(binaryIri))
                .append("\" download>").append(t("html.download")).append("</a>");
            if (binary != null) {
                html.append(" <span class=\"meta\">")
                    .append(escape(binary.getFilename() == null ? "" : binary.getFilename() + " · "))
                    .append(escape(binary.getContentType() == null ? "application/octet-stream" : binary.getContentType()))
                    .append(" · ").append(escape(humanSize(binary.getSize() == null ? 0 : binary.getSize(),
                        locale.locale())))
                    .append("</span>");
            }
            html.append(" · <a href=\"").append(escape(binaryIri + "/" + LdpService.DESCRIPTION_SEGMENT))
                .append("\">").append(t("html.description")).append("</a></p>");
        }

        private void containerSection(final StringBuilder html, final Model model, final String containerIri,
                final String formAction) {
            final List<String> children = new ArrayList<>();
            model.listObjectsOfProperty(model.createResource(containerIri), model.createProperty(Ldp.CONTAINS))
                .forEachRemaining(node -> {
                    if (node.isURIResource()) {
                        children.add(node.asResource().getURI());
                    }
                });
            children.sort(Comparator.naturalOrder());

            html.append("<h2>").append(t("html.contains", children.size())).append("</h2>");
            if (children.isEmpty()) {
                html.append("<div class=\"empty\">").append(t("html.empty-container")).append("</div>");
            } else {
                html.append("<ul class=\"children\">");
                for (final String child : children) {
                    html.append("<li><a href=\"").append(escape(child)).append("\">").append(escape(lastSegment(child)))
                        .append("</a><button class=\"danger\" type=\"button\" data-delete=\"").append(escape(child))
                        .append("\" data-confirm=\"").append(t("html.delete-confirm", child)).append("\">")
                        .append(t("html.delete")).append("</button></li>");
                }
                html.append("</ul>");
            }

            html.append("<h2>").append(t("html.upload-title")).append("</h2>")
                .append("<form method=\"post\" enctype=\"multipart/form-data\" action=\"").append(escape(formAction)).append("\">")
                .append("<input type=\"hidden\" name=\"").append(KIND_FIELD).append("\" value=\"").append(KIND_FILE).append("\">")
                .append("<input type=\"file\" name=\"").append(FILE_FIELD).append("\" required aria-label=\"")
                .append(t("html.file")).append("\">")
                .append("<input type=\"text\" name=\"").append(SLUG_FIELD).append("\" placeholder=\"")
                .append(t("html.name-optional")).append("\" aria-label=\"").append(t("html.name-optional")).append("\">")
                .append("<button type=\"submit\">").append(t("html.upload")).append("</button></form>");

            html.append("<h2>").append(t("html.create-title")).append("</h2>")
                .append("<form method=\"post\" enctype=\"multipart/form-data\" action=\"").append(escape(formAction)).append("\">")
                .append("<input type=\"hidden\" name=\"").append(KIND_FIELD).append("\" value=\"").append(KIND_CONTAINER).append("\">")
                .append("<input type=\"text\" name=\"").append(SLUG_FIELD).append("\" placeholder=\"")
                .append(t("html.name-optional")).append("\" aria-label=\"").append(t("html.name-optional")).append("\">")
                .append("<button type=\"submit\">").append(t("html.create")).append("</button></form>");
        }

        private void triplesSection(final StringBuilder html, final Model model, final String subjectIri) {
            final List<Statement> statements = model.listStatements().toList();
            statements.removeIf(statement -> Ldp.CONTAINS.equals(statement.getPredicate().getURI()));
            statements.sort(Comparator
                .comparing((Statement s) -> s.getSubject().isURIResource() && subjectIri.equals(s.getSubject().getURI()) ? 0 : 1)
                .thenComparing(s -> s.getSubject().toString())
                .thenComparing(s -> Rdf.TYPE.equals(s.getPredicate().getURI()) ? "" : s.getPredicate().getURI())
                .thenComparing(s -> s.getObject().toString()));

            html.append("<h2>").append(t("html.triples", statements.size())).append("</h2>");
            if (statements.isEmpty()) {
                html.append("<div class=\"empty\">").append(t("html.no-triples")).append("</div>");
                return;
            }
            html.append("<table><thead><tr><th scope=\"col\">").append(t("html.subject"))
                .append("</th><th scope=\"col\">").append(t("html.predicate"))
                .append("</th><th scope=\"col\">").append(t("html.object")).append("</th></tr></thead><tbody>");
            for (final Statement statement : statements) {
                html.append("<tr><td class=\"mono\">").append(node(model, statement.getSubject(), subjectIri)).append("</td>")
                    .append("<td class=\"mono\">").append(node(model, statement.getPredicate(), subjectIri)).append("</td>")
                    .append("<td>").append(node(model, statement.getObject(), subjectIri)).append("</td></tr>");
            }
            html.append("</tbody></table>");
        }

        private String node(final Model model, final RDFNode node, final String subjectIri) {
            if (node.isURIResource()) {
                final String uri = node.asResource().getURI();
                final String label = uri.equals(subjectIri) ? t("html.this") : escape(model.shortForm(uri));
                return "<a href=\"" + escape(uri) + "\">" + label + "</a>";
            }
            if (node.isAnon()) {
                return escape("_:" + node.asResource().getId().getLabelString());
            }
            final Literal literal = node.asLiteral();
            final boolean tagged = literal.getLanguage() != null && !literal.getLanguage().isEmpty();
            // a language-tagged text keeps its language for browsers and screen readers
            final StringBuilder out = new StringBuilder(tagged
                ? "<span lang=\"" + escape(literal.getLanguage()) + "\">" + escape(literal.getLexicalForm()) + "</span>"
                : escape(literal.getLexicalForm()));
            if (tagged) {
                out.append(" <span class=\"meta\">@").append(escape(literal.getLanguage())).append("</span>");
            } else if (literal.getDatatypeURI() != null && !Rdf.XSD_STRING.equals(literal.getDatatypeURI())) {
                out.append(" <span class=\"meta\">").append(escape(model.shortForm(literal.getDatatypeURI()))).append("</span>");
            }
            return out.toString();
        }

        /**
         * Picks the literal in the reader's language: tagged with the locale's language, then
         * untagged, then the first one.
         *
         * @param statements the statements of one property
         * @return the literal, {@code null} when none is a literal
         */
        private Literal preferred(final List<Statement> statements) {
            final String language = locale.locale().getLanguage();
            Literal untagged = null;
            Literal first = null;
            for (final Statement statement : statements) {
                if (!statement.getObject().isLiteral()) {
                    continue;
                }
                final Literal literal = statement.getObject().asLiteral();
                final String tag = literal.getLanguage() == null ? "" : literal.getLanguage();
                if (!tag.isEmpty() && Locale.forLanguageTag(tag).getLanguage().equals(language)) {
                    return literal;
                }
                if (tag.isEmpty() && untagged == null) {
                    untagged = literal;
                }
                if (first == null) {
                    first = literal;
                }
            }
            return untagged != null ? untagged : first;
        }

        private String title(final Model model, final String subjectIri, final LdpResource resource) {
            for (final String predicate : List.of(Rdf.RDFS_LABEL, "http://purl.org/dc/terms/title",
                    "http://purl.org/dc/elements/1.1/title")) {
                final Literal literal = preferred(model.getResource(subjectIri)
                    .listProperties(model.createProperty(predicate)).toList());
                if (literal != null) {
                    return literal.getLexicalForm();
                }
            }
            if (resource.getBinary() != null && resource.getBinary().getFilename() != null) {
                return resource.getBinary().getFilename();
            }
            return resource.isRoot() ? messages.get(locale.locale(), "html.root") : lastSegment(resource.getPath());
        }

    }

    private static String lastSegment(final String value) {
        final String trimmed = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        final int index = trimmed.lastIndexOf('/');
        return index < 0 ? trimmed : trimmed.substring(index + 1);
    }

    /**
     * Formats a byte count for humans in a locale's number format.
     *
     * @param bytes  the size in bytes
     * @param locale the locale
     * @return for example {@code 1.5 MB}, or {@code 1,5 MB} in German
     */
    static String humanSize(final long bytes, final Locale locale) {
        final NumberFormat format = NumberFormat.getNumberInstance(locale);
        if (bytes < 1024) {
            return format.format(bytes) + " B";
        }
        final String[] units = { "KB", "MB", "GB", "TB" };
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        format.setMinimumFractionDigits(1);
        format.setMaximumFractionDigits(1);
        return format.format(value) + " " + units[unit];
    }

    private static String escape(final String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value, "UTF-8");
    }

}
