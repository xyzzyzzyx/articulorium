package com.articulorium.platform.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import com.articulorium.domain.InteractionModel;
import com.articulorium.domain.LdpResource;
import com.articulorium.domain.Triple;
import com.articulorium.domain.rdf.ResourcePaths;
import com.articulorium.domain.vocabulary.Articulorium;
import com.articulorium.domain.vocabulary.Rdf;
import com.articulorium.platform.namespace.NamespacePrefix;
import com.articulorium.platform.namespace.NamespacePrefixRepository;
import com.articulorium.platform.namespace.NamespaceRegistry;
import com.articulorium.platform.rdf.TripleMapper;
import com.articulorium.platform.repository.LdpResourceRepository;

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
 *   <li>Creates the container of the community structure
 *       ({@link LdpProperties#getCommunitiesPath()}, typed {@code art:CommunityRoot}) when it does
 *       not exist.</li>
 *   <li>Creates the container of the academic profiles
 *       ({@link LdpProperties#getProfilesPath()}, typed {@code art:ProfileRoot}) when it does not
 *       exist.</li>
 * </ol>
 *
 * <p>Every step is idempotent and tolerate concurrent start-up of several instances.</p>
 */
@Slf4j
@Component
public class PlatformInitializer implements ApplicationRunner {

    /** Base IRI used to parse {@code root.ttl}; {@code <>} in the file resolves to the root. */
    static final String SEED_BASE = "http://articulorium.invalid";

    /** Agent recorded as creator of the root container. */
    static final String SYSTEM_AGENT = "articulorium";

    private final Resource namespaces;

    private final Resource root;

    private final NamespacePrefixRepository prefixRepository;

    private final LdpResourceRepository resourceRepository;

    private final NamespaceRegistry registry;

    private final TransactionTemplate transactions;

    private final LdpProperties properties;

    /**
     * Creates the initializer.
     *
     * @param namespaces         the namespace YAML resource
     * @param root               the root container Turtle resource
     * @param prefixRepository   the namespace prefix repository
     * @param resourceRepository the resource repository
     * @param registry           the in-memory prefix registry
     * @param transactions       transaction template
     * @param properties         LDP settings
     */
    public PlatformInitializer(@Value("classpath:namespaces.yml") final Resource namespaces,
            @Value("classpath:root.ttl") final Resource root,
            final NamespacePrefixRepository prefixRepository,
            final LdpResourceRepository resourceRepository,
            final NamespaceRegistry registry,
            final TransactionTemplate transactions,
            final LdpProperties properties) {
        this.namespaces = namespaces;
        this.root = root;
        this.prefixRepository = prefixRepository;
        this.resourceRepository = resourceRepository;
        this.registry = registry;
        this.transactions = transactions;
        this.properties = properties;
    }

    @Override
    public void run(final ApplicationArguments args) throws IOException {
        seedNamespaces();
        createRoot();
        createCommunityRoot();
        createProfileRoot();
    }

    /**
     * Creates the container of the community structure when it is configured and missing.
     */
    void createCommunityRoot() {
        createTopLevelContainer(properties.getCommunitiesPath(), Articulorium.COMMUNITY_ROOT, "Communities");
    }

    /**
     * Creates the container of the academic profiles when it is configured and missing.
     */
    void createProfileRoot() {
        createTopLevelContainer(properties.getProfilesPath(), Articulorium.PROFILE_ROOT, "Profiles");
    }

    /**
     * Creates a typed, titled basic container directly below the root when it is configured and
     * missing. Paths with several segments are ignored: their parents may not exist.
     *
     * @param configuredPath the configured path, may be {@code null} or empty to create none
     * @param type           the {@code rdf:type} of the container
     * @param title          its {@code dcterms:title}
     */
    void createTopLevelContainer(final String configuredPath, final String type, final String title) {
        final String path = ResourcePaths.normalize(configuredPath == null ? "" : configuredPath);
        if (path.isEmpty() || path.contains("/") || resourceRepository.existsByPath(path)) {
            return;
        }
        try {
            transactions.executeWithoutResult(status -> {
                final LdpResource container = new LdpResource(path, InteractionModel.BASIC_CONTAINER)
                    .createdBy(SYSTEM_AGENT);
                final String subject = container.getRelativeIri();
                container.setTriples(List.of(
                    Triple.iri(subject, Rdf.TYPE, type),
                    Triple.literal(subject, DCTERMS_TITLE, title)));
                resourceRepository.save(container);
                resourceRepository.findByPath("").ifPresent(root -> {
                    root.touch(SYSTEM_AGENT);
                    resourceRepository.save(root);
                });
            });
            log.info("Created the {} container /{}", title, path);
        } catch (DataIntegrityViolationException e) {
            log.debug("{} container was created concurrently", title);
        }
    }

    /** {@code dcterms:title}. */
    static final String DCTERMS_TITLE = "http://purl.org/dc/terms/title";

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
