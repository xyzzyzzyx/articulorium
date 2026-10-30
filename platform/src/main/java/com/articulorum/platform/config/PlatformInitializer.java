package com.articulorum.platform.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import com.articulorum.domain.InteractionModel;
import com.articulorum.domain.LdpResource;
import com.articulorum.domain.Triple;
import com.articulorum.platform.namespace.NamespacePrefix;
import com.articulorum.platform.namespace.NamespacePrefixRepository;
import com.articulorum.platform.namespace.NamespaceRegistry;
import com.articulorum.platform.rdf.TripleMapper;
import com.articulorum.platform.repository.LdpResourceRepository;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;

/**
 * Prepares the repository on start-up.
 *
 * <ol>
 *   <li>Registers the namespace prefixes listed in {@code namespaces.yml} (Fedora's default
 *       registry plus common vocabularies) that are not yet known
 *       and loads all known prefixes into the {@link NamespaceRegistry}.</li>
 *   <li>Creates the root container from {@code root.ttl} when it does not exist.</li>
 * </ol>
 *
 * <p>Both steps are idempotent and tolerate concurrent start-up of several instances.</p>
 */
@Slf4j
@Component
public class PlatformInitializer implements ApplicationRunner {

    /** Base IRI used to parse {@code root.ttl}; {@code <>} in the file resolves to the root. */
    static final String SEED_BASE = "http://articulorum.invalid";

    /** Agent recorded as creator of the root container. */
    static final String SYSTEM_AGENT = "articulorum";

    private final Resource namespaces;

    private final Resource root;

    private final NamespacePrefixRepository prefixRepository;

    private final LdpResourceRepository resourceRepository;

    private final NamespaceRegistry registry;

    private final TransactionTemplate transactions;

    /**
     * Creates the initializer.
     *
     * @param namespaces         the namespace YAML resource
     * @param root               the root container Turtle resource
     * @param prefixRepository   the namespace prefix repository
     * @param resourceRepository the resource repository
     * @param registry           the in-memory prefix registry
     * @param transactions       transaction template
     */
    public PlatformInitializer(@Value("classpath:namespaces.yml") final Resource namespaces,
            @Value("classpath:root.ttl") final Resource root,
            final NamespacePrefixRepository prefixRepository,
            final LdpResourceRepository resourceRepository,
            final NamespaceRegistry registry,
            final TransactionTemplate transactions) {
        this.namespaces = namespaces;
        this.root = root;
        this.prefixRepository = prefixRepository;
        this.resourceRepository = resourceRepository;
        this.registry = registry;
        this.transactions = transactions;
    }

    @Override
    public void run(final ApplicationArguments args) throws IOException {
        seedNamespaces();
        createRoot();
    }

    /**
     * Registers missing namespace prefixes and loads every prefix into the registry.
     *
     * @throws IOException when the namespace document cannot be read
     */
    void seedNamespaces() throws IOException {
        final List<NamespaceRegistry.Definition> definitions;
        try (InputStream in = namespaces.getInputStream()) {
            definitions = NamespaceRegistry.readYaml(in);
        }
        for (final NamespaceRegistry.Definition definition : definitions) {
            try {
                transactions.executeWithoutResult(status -> {
                    if (!prefixRepository.existsByPrefix(definition.prefix())
                            && !prefixRepository.existsByNamespace(definition.namespace())) {
                        prefixRepository.save(new NamespacePrefix(definition.prefix(), definition.namespace(),
                            definition.description()));
                    }
                });
            } catch (DataIntegrityViolationException e) {
                log.debug("Namespace prefix {} was registered concurrently", definition.prefix());
            }
        }
        prefixRepository.findAll().forEach(prefix -> registry.register(prefix.getPrefix(), prefix.getNamespace()));
        log.info("Loaded {} namespace prefixes", registry.snapshot().size());
    }

    /**
     * Creates the root container when it is missing.
     *
     * @throws IOException when {@code root.ttl} cannot be read
     */
    void createRoot() throws IOException {
        if (resourceRepository.existsByPath("")) {
            return;
        }
        final Model model = ModelFactory.createDefaultModel();
        try (InputStream in = root.getInputStream()) {
            RDFDataMgr.read(model, in, SEED_BASE + "/", Lang.TURTLE);
        }
        final List<Triple> triples = TripleMapper.toTriples(model, SEED_BASE);
        try {
            transactions.executeWithoutResult(status -> {
                final LdpResource container = new LdpResource("", InteractionModel.BASIC_CONTAINER)
                    .createdBy(SYSTEM_AGENT);
                container.setTriples(triples);
                resourceRepository.save(container);
            });
            log.info("Created the root container");
        } catch (DataIntegrityViolationException e) {
            log.debug("Root container was created concurrently");
        }
    }

}
