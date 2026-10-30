package com.articulorum.platform.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.articulorum.domain.BinaryContent;
import com.articulorum.domain.LdpResource;
import com.articulorum.domain.rdf.Iris;
import com.articulorum.domain.vocabulary.Ldp;
import com.articulorum.domain.vocabulary.Rdf;
import com.articulorum.platform.service.LdpService;
import com.articulorum.platform.service.ResolvedTarget;

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
            if (!window.confirm('Delete ' + target + ' and everything it contains?')) { return; }
            fetch(target, { method: 'DELETE' }).then(function (response) {
              if (response.ok) { window.location.reload(); }
              else { response.text().then(function (text) { window.alert(response.status + ': ' + text); }); }
            });
          });
        });
        """;

    /**
     * Renders the page.
     *
     * @param target the displayed target
     * @param model  its rendered representation
     * @param base   the server base of the request
     * @return the HTML document
     */
    public String render(final ResolvedTarget target, final Model model, final String base) {
        final LdpResource resource = target.resource();
        final String iri = Iris.resolve(base, target.relativeIri());
        final String subjectIri = Iris.resolve(base, resource.getRelativeIri());
        final StringBuilder html = new StringBuilder(8192);
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            .append("<title>").append(escape(title(model, subjectIri, resource))).append(" · Articulorum</title>")
            .append("<style>").append(STYLE).append("</style></head><body><main>");

        breadcrumbs(html, base, resource, target.description());
        html.append("<h1>").append(escape(title(model, subjectIri, resource))).append("</h1>")
            .append("<div class=\"iri\">").append(escape(iri)).append("</div>");

        html.append("<div class=\"types\">");
        for (final String type : resource.getInteractionModel().getTypeIris()) {
            html.append("<span class=\"badge\">").append(escape(model.shortForm(type))).append("</span>");
        }
        if (target.description()) {
            html.append("<span class=\"badge\">description</span>");
        }
        html.append("</div>");

        if (resource.isNonRdfSource()) {
            binarySection(html, resource, subjectIri);
        }
        if (resource.isContainer() && !target.description()) {
            containerSection(html, model, subjectIri, iri);
        }
        triplesSection(html, model, subjectIri);

        html.append("<p class=\"meta\">Request this page with <code>Accept: text/turtle</code> (or ")
            .append("<code>application/ld+json</code>) for its RDF representation. ")
            .append("<a href=\"").append(escape(Iris.normalizeBase(base) + ConstraintsController.PATH))
            .append("\">Server constraints</a>.</p>");
        html.append("</main><script>").append(SCRIPT).append("</script></body></html>");
        return html.toString();
    }

    private static void breadcrumbs(final StringBuilder html, final String base, final LdpResource resource,
            final boolean description) {
        html.append("<nav><a href=\"").append(escape(Iris.resolve(base, "/"))).append("\">root</a>");
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

    private static void binarySection(final StringBuilder html, final LdpResource resource, final String binaryIri) {
        final BinaryContent binary = resource.getBinary();
        html.append("<h2>File</h2><p><a href=\"").append(escape(binaryIri)).append("\" download>Download</a>");
        if (binary != null) {
            html.append(" <span class=\"meta\">")
                .append(escape(binary.getFilename() == null ? "" : binary.getFilename() + " · "))
                .append(escape(binary.getContentType() == null ? "application/octet-stream" : binary.getContentType()))
                .append(" · ").append(humanSize(binary.getSize() == null ? 0 : binary.getSize()))
                .append("</span>");
        }
        html.append(" · <a href=\"").append(escape(binaryIri + "/" + LdpService.DESCRIPTION_SEGMENT))
            .append("\">description</a></p>");
    }

    private static void containerSection(final StringBuilder html, final Model model, final String containerIri,
            final String formAction) {
        final List<String> children = new ArrayList<>();
        model.listObjectsOfProperty(model.createResource(containerIri), model.createProperty(Ldp.CONTAINS))
            .forEachRemaining(node -> {
                if (node.isURIResource()) {
                    children.add(node.asResource().getURI());
                }
            });
        children.sort(Comparator.naturalOrder());

        html.append("<h2>Contains (").append(children.size()).append(")</h2>");
        if (children.isEmpty()) {
            html.append("<div class=\"empty\">This container is empty.</div>");
        } else {
            html.append("<ul class=\"children\">");
            for (final String child : children) {
                html.append("<li><a href=\"").append(escape(child)).append("\">").append(escape(lastSegment(child)))
                    .append("</a><button class=\"danger\" type=\"button\" data-delete=\"").append(escape(child))
                    .append("\">Delete</button></li>");
            }
            html.append("</ul>");
        }

        html.append("<h2>Upload a file</h2>")
            .append("<form method=\"post\" enctype=\"multipart/form-data\" action=\"").append(escape(formAction)).append("\">")
            .append("<input type=\"hidden\" name=\"").append(KIND_FIELD).append("\" value=\"").append(KIND_FILE).append("\">")
            .append("<input type=\"file\" name=\"").append(FILE_FIELD).append("\" required>")
            .append("<input type=\"text\" name=\"").append(SLUG_FIELD).append("\" placeholder=\"name (optional)\">")
            .append("<button type=\"submit\">Upload</button></form>");

        html.append("<h2>Create a container</h2>")
            .append("<form method=\"post\" enctype=\"multipart/form-data\" action=\"").append(escape(formAction)).append("\">")
            .append("<input type=\"hidden\" name=\"").append(KIND_FIELD).append("\" value=\"").append(KIND_CONTAINER).append("\">")
            .append("<input type=\"text\" name=\"").append(SLUG_FIELD).append("\" placeholder=\"name (optional)\">")
            .append("<button type=\"submit\">Create</button></form>");
    }

    private static void triplesSection(final StringBuilder html, final Model model, final String subjectIri) {
        final List<Statement> statements = model.listStatements().toList();
        statements.removeIf(statement -> Ldp.CONTAINS.equals(statement.getPredicate().getURI()));
        statements.sort(Comparator
            .comparing((Statement s) -> s.getSubject().isURIResource() && subjectIri.equals(s.getSubject().getURI()) ? 0 : 1)
            .thenComparing(s -> s.getSubject().toString())
            .thenComparing(s -> Rdf.TYPE.equals(s.getPredicate().getURI()) ? "" : s.getPredicate().getURI())
            .thenComparing(s -> s.getObject().toString()));

        html.append("<h2>Triples (").append(statements.size()).append(")</h2>");
        if (statements.isEmpty()) {
            html.append("<div class=\"empty\">No triples.</div>");
            return;
        }
        html.append("<table><thead><tr><th>Subject</th><th>Predicate</th><th>Object</th></tr></thead><tbody>");
        for (final Statement statement : statements) {
            html.append("<tr><td class=\"mono\">").append(node(model, statement.getSubject(), subjectIri)).append("</td>")
                .append("<td class=\"mono\">").append(node(model, statement.getPredicate(), subjectIri)).append("</td>")
                .append("<td>").append(node(model, statement.getObject(), subjectIri)).append("</td></tr>");
        }
        html.append("</tbody></table>");
    }

    private static String node(final Model model, final RDFNode node, final String subjectIri) {
        if (node.isURIResource()) {
            final String uri = node.asResource().getURI();
            final String label = uri.equals(subjectIri) ? "&lt;this&gt;" : escape(model.shortForm(uri));
            return "<a href=\"" + escape(uri) + "\">" + label + "</a>";
        }
        if (node.isAnon()) {
            return escape("_:" + node.asResource().getId().getLabelString());
        }
        final Literal literal = node.asLiteral();
        final StringBuilder out = new StringBuilder(escape(literal.getLexicalForm()));
        if (literal.getLanguage() != null && !literal.getLanguage().isEmpty()) {
            out.append(" <span class=\"meta\">@").append(escape(literal.getLanguage())).append("</span>");
        } else if (literal.getDatatypeURI() != null && !Rdf.XSD_STRING.equals(literal.getDatatypeURI())) {
            out.append(" <span class=\"meta\">").append(escape(model.shortForm(literal.getDatatypeURI()))).append("</span>");
        }
        return out.toString();
    }

    private static String title(final Model model, final String subjectIri, final LdpResource resource) {
        for (final String predicate : List.of(Rdf.RDFS_LABEL, "http://purl.org/dc/terms/title",
                "http://purl.org/dc/elements/1.1/title")) {
            final Statement statement = model.getResource(subjectIri).getProperty(model.createProperty(predicate));
            if (statement != null && statement.getObject().isLiteral()) {
                return statement.getObject().asLiteral().getLexicalForm();
            }
        }
        if (resource.getBinary() != null && resource.getBinary().getFilename() != null) {
            return resource.getBinary().getFilename();
        }
        return resource.isRoot() ? "root" : lastSegment(resource.getPath());
    }

    private static String lastSegment(final String value) {
        final String trimmed = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        final int index = trimmed.lastIndexOf('/');
        return index < 0 ? trimmed : trimmed.substring(index + 1);
    }

    /**
     * Formats a byte count for humans.
     *
     * @param bytes the size in bytes
     * @return for example {@code 1.5 MB}
     */
    static String humanSize(final long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        final String[] units = { "KB", "MB", "GB", "TB" };
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static String escape(final String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value, "UTF-8");
    }

}
