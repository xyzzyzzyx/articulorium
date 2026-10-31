package com.articulorum.platform.namespace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.articulorum.domain.vocabulary.Fedora;
import com.articulorum.domain.vocabulary.Ldp;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Unit tests for {@link NamespaceRegistry}.
 */
class NamespaceRegistryTest {

    /** Every prefix of Fedora 6's default namespace registry. */
    private static final List<String> FEDORA_DEFAULT_PREFIXES = List.of("premis", "premis3", "hashFunc", "evType",
        "evOutcome", "prov", "test", "memento", "rdfs", "webac", "acl", "vcard", "iana", "xsi", "xmlns", "rdf",
        "fedora", "xml", "ebucore", "ldp", "dcterms", "xs", "fedoraconfig", "foaf", "dc");

    @Test
    void bundledDocumentContainsEveryFedoraDefaultNamespace() throws Exception {
        try (InputStream in = new ClassPathResource("namespaces.yml").getInputStream()) {
            final List<NamespaceRegistry.Definition> definitions = NamespaceRegistry.readYaml(in);
            assertThat(definitions).extracting(NamespaceRegistry.Definition::prefix)
                .containsAll(FEDORA_DEFAULT_PREFIXES)
                .doesNotHaveDuplicates();
            assertThat(definitions).contains(new NamespaceRegistry.Definition("ldp", Ldp.NAMESPACE, null));
            assertThat(definitions).contains(new NamespaceRegistry.Definition("fedora", Fedora.NAMESPACE, null));
            assertThat(definitions).extracting(NamespaceRegistry.Definition::namespace)
                .doesNotContain("http://www.w3.org/ns/platform#")
                .doesNotHaveDuplicates();
        }
    }

    @Test
    void readsAPrefixMap() throws Exception {
        final String yaml = "# comment\nex: http://example.org/ns#\nschema: http://schema.org/\n";
        final List<NamespaceRegistry.Definition> definitions =
            NamespaceRegistry.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        assertThat(definitions).containsExactly(
            new NamespaceRegistry.Definition("ex", "http://example.org/ns#", null),
            new NamespaceRegistry.Definition("schema", "http://schema.org/", null));
    }

    @Test
    void emptyDocumentDefinesNothing() throws Exception {
        assertThat(NamespaceRegistry.readYaml(new ByteArrayInputStream(new byte[0]))).isEmpty();
    }

    @Test
    void rejectsDocumentsThatAreNotPrefixMaps() {
        assertThatThrownBy(() -> NamespaceRegistry.readYaml(
            new ByteArrayInputStream("- a\n- b\n".getBytes(StandardCharsets.UTF_8))))
            .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> NamespaceRegistry.readYaml(
            new ByteArrayInputStream("ex:\n".getBytes(StandardCharsets.UTF_8))))
            .isInstanceOf(IOException.class);
    }

    @Test
    void declaresOnlyUsedPrefixes() {
        final NamespaceRegistry registry = new NamespaceRegistry();
        registry.register("dcterms", "http://purl.org/dc/terms/");
        registry.register("foaf", "http://xmlns.com/foaf/0.1/");
        final Model model = ModelFactory.createDefaultModel();
        model.createResource("http://localhost/a").addProperty(model.createProperty("http://purl.org/dc/terms/title"), "A");

        registry.applyTo(model);

        assertThat(model.getNsPrefixURI("dcterms")).isEqualTo("http://purl.org/dc/terms/");
        assertThat(model.getNsPrefixURI("foaf")).isNull();
        assertThat(registry.snapshot()).hasSize(2);
    }

}
