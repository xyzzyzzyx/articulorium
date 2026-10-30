package com.articulorum.platform.namespace;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


import org.apache.jena.graph.Node;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * In-memory view of the known namespace prefixes.
 *
 * <p>The registry is loaded from the database at start-up and used to declare only the prefixes
 * a rendered model actually uses, which keeps Turtle and JSON-LD output compact.</p>
 */
@Component
public class NamespaceRegistry {

    /**
     * A prefix definition.
     *
     * @param prefix      the prefix, for example {@code dcterms}
     * @param namespace   the namespace IRI
     * @param description a human readable description, may be {@code null}
     */
    public record Definition(String prefix, String namespace, String description) {
    }

    /** namespace IRI to prefix */
    private final Map<String, String> prefixes = new ConcurrentHashMap<>();

    /**
     * Registers a prefix.
     *
     * @param prefix    the prefix
     * @param namespace the namespace IRI
     */
    public void register(final String prefix, final String namespace) {
        prefixes.put(namespace, prefix);
    }

    /**
     * Returns the registered namespace to prefix mappings.
     *
     * @return an unmodifiable snapshot
     */
    public Map<String, String> snapshot() {
        return Map.copyOf(prefixes);
    }

    /**
     * Declares on a model every registered prefix whose namespace is used by one of its IRIs.
     *
     * @param model the model to decorate
     */
    public void applyTo(final Model model) {
        final StmtIterator statements = model.listStatements();
        try {
            while (statements.hasNext()) {
                final Statement statement = statements.next();
                declare(model, statement.getSubject().asNode());
                declare(model, statement.getPredicate().asNode());
                declare(model, statement.getObject().asNode());
                if (statement.getObject().isLiteral()) {
                    final String datatype = statement.getObject().asLiteral().getDatatypeURI();
                    if (datatype != null) {
                        declare(model, datatype);
                    }
                }
            }
        } finally {
            statements.close();
        }
    }

    private void declare(final Model model, final Node node) {
        if (node.isURI()) {
            declare(model, node.getURI());
        }
    }

    private void declare(final Model model, final String iri) {
        for (final Map.Entry<String, String> entry : prefixes.entrySet()) {
            if (iri.startsWith(entry.getKey()) && iri.length() > entry.getKey().length()) {
                model.setNsPrefix(entry.getValue(), entry.getKey());
            }
        }
    }

    /**
     * Reads prefix definitions from a YAML document mapping prefixes to namespace IRIs, the format
     * of Fedora's {@code namespaces.yml}:
     *
     * <pre>
     * dcterms: http://purl.org/dc/terms/
     * fedora: http://fedora.info/definitions/v4/repository#
     * </pre>
     *
     * @param yaml the YAML stream; it is not closed
     * @return the definitions in document order
     * @throws IOException when the document cannot be read or is not a prefix map
     */
    public static List<Definition> readYaml(final InputStream yaml) throws IOException {
        final Object document;
        try {
            document = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        } catch (RuntimeException e) {
            throw new IOException("Invalid namespace document: " + e.getMessage(), e);
        }
        if (document == null) {
            return List.of();
        }
        if (!(document instanceof Map<?, ?> map)) {
            throw new IOException("A namespace document must map prefixes to namespace IRIs");
        }
        final List<Definition> definitions = new ArrayList<>();
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IOException("Incomplete namespace definition: " + entry);
            }
            definitions.add(new Definition(entry.getKey().toString().trim(), entry.getValue().toString().trim(), null));
        }
        return definitions;
    }

}
