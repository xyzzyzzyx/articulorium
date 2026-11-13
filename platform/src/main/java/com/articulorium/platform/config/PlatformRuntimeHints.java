package com.articulorium.platform.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

import com.articulorium.platform.search.SearchService;
import com.articulorium.platform.service.HistoryService;
import com.articulorium.platform.web.ExtensionHandler;

import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

import io.ocfl.api.model.DigestAlgorithm;
import io.ocfl.api.model.InventoryType;
import io.ocfl.api.model.VersionNum;
import io.ocfl.core.extension.storage.layout.FlatLayoutExtension;
import io.ocfl.core.extension.storage.layout.FlatOmitPrefixLayoutExtension;
import io.ocfl.core.extension.storage.layout.HashedNTupleIdEncapsulationLayoutExtension;
import io.ocfl.core.extension.storage.layout.HashedNTupleLayoutExtension;
import io.ocfl.core.extension.storage.layout.NTupleOmitPrefixStorageLayoutExtension;
import io.ocfl.core.extension.storage.layout.OcflLayout;
import io.ocfl.core.extension.storage.layout.config.FlatLayoutConfig;
import io.ocfl.core.extension.storage.layout.config.FlatOmitPrefixLayoutConfig;
import io.ocfl.core.extension.storage.layout.config.HashedNTupleIdEncapsulationLayoutConfig;
import io.ocfl.core.extension.storage.layout.config.HashedNTupleLayoutConfig;
import io.ocfl.core.extension.storage.layout.config.NTupleOmitPrefixStorageLayoutConfig;
import io.ocfl.core.model.Inventory;
import io.ocfl.core.model.User;
import io.ocfl.core.model.Version;

/**
 * GraalVM native-image hints for the platform.
 *
 * <p>Apache Jena initialises itself through {@link java.util.ServiceLoader} and reads several
 * property and resource files from its jars. This registrar, which runs at build time during
 * Spring AOT processing, includes those resources in the image and registers every class listed
 * in Jena's service files for reflective instantiation. The platform's own class path resources
 * ({@link #PLATFORM_RESOURCES}) are included as well: a native image holds only the resources it
 * is told about, so one that is missing here cannot be opened at run time.</p>
 *
 * <p>ocfl-java instantiates its storage layout extensions and their configuration through
 * {@code getDeclaredConstructor().newInstance()}, reads and writes {@code ocfl_layout.json}, the
 * extension configuration and every object inventory with Jackson, and copies the OCFL
 * specifications and its lock table DDL from its jar. Those types ({@link #OCFL_TYPES}) and
 * resources ({@link #OCFL_RESOURCES}) are registered here too.</p>
 *
 * <p>Jena does not officially support native images. If a native build reports missing
 * reflection or resource metadata at run time, run the application once on the JVM with the
 * GraalVM tracing agent ({@code -agentlib:native-image-agent=config-output-dir=...}) and add the
 * generated files under {@code src/main/resources/META-INF/native-image}.</p>
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(PlatformRuntimeHints.Registrar.class)
public class PlatformRuntimeHints {

    /** Service files whose implementations Jena loads reflectively. */
    static final String[] JENA_SERVICE_FILES = {
        "META-INF/services/org.apache.jena.sys.JenaSubsystemLifecycle",
        "META-INF/services/org.apache.jena.riot.system.stream.LocatorFactory",
    };

    /**
     * Class path resources the platform opens at run time: the namespace prefixes and the root
     * container's description (start-up), the LDP constraints document, and the root access control
     * lists selectable with {@code articulorium.webac.root-acl}.
     */
    static final String[] PLATFORM_RESOURCES = {
        "namespaces.yml",
        "root.ttl",
        "ldp-constraints.txt",
        "webac/*.ttl",
    };

    /**
     * ocfl-java types reached reflectively: the layout extensions in its registry (instantiated
     * by name of the storage root's layout), their configuration, and the Jackson bound model of
     * {@code ocfl_layout.json} and {@code inventory.json}.
     */
    static final Class<?>[] OCFL_TYPES = {
        HashedNTupleLayoutExtension.class,
        HashedNTupleIdEncapsulationLayoutExtension.class,
        FlatLayoutExtension.class,
        FlatOmitPrefixLayoutExtension.class,
        NTupleOmitPrefixStorageLayoutExtension.class,
        HashedNTupleLayoutConfig.class,
        HashedNTupleIdEncapsulationLayoutConfig.class,
        FlatLayoutConfig.class,
        FlatOmitPrefixLayoutConfig.class,
        NTupleOmitPrefixStorageLayoutConfig.class,
        OcflLayout.class,
        Inventory.class,
        Inventory.JacksonBuilder.class,
        Version.class,
        User.class,
        VersionNum.class,
        InventoryType.class,
        DigestAlgorithm.class,
    };

    /**
     * Resources ocfl-java reads from its own jar: the specifications it copies into a new storage
     * root and the DDL of the database object lock (and object details) tables.
     */
    static final String[] OCFL_RESOURCES = {
        "ocfl-specs/*",
        "db/*/*.ddl.tmpl",
    };

    /**
     * Records the extension endpoints ({@code fcr:search}, {@code fcr:fixity}, {@code fcr:versions},
     * the inbox and outbox) answer as JSON. Their handlers return {@code ResponseEntity<?>}, so
     * Spring AOT cannot tell the body types from the controller: without a hint Jackson finds no
     * serializer for them in a native image and the request fails with 500.
     */
    static final Class<?>[] JSON_BODIES = {
        SearchService.SearchResult.class,
        SearchService.Pagination.class,
        HistoryService.FixityReport.class,
        ExtensionHandler.VersionList.class,
        ExtensionHandler.VersionEntry.class,
        ExtensionHandler.NotificationList.class,
        ExtensionHandler.NotificationSummary.class,
    };

    /** Registers the hints. */
    static class Registrar implements RuntimeHintsRegistrar {

        private final BindingReflectionHintsRegistrar bindingRegistrar = new BindingReflectionHintsRegistrar();

        @Override
        public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
            for (final String resource : PLATFORM_RESOURCES) {
                hints.resources().registerPattern(resource);
            }
            hints.resources().registerPattern("META-INF/services/org.apache.jena.*");
            hints.resources().registerPattern("org/apache/jena/**");
            for (final String serviceFile : JENA_SERVICE_FILES) {
                registerServiceImplementations(hints, classLoader, serviceFile);
            }
            for (final String resource : OCFL_RESOURCES) {
                hints.resources().registerPattern(resource);
            }
            bindingRegistrar.registerReflectionHints(hints.reflection(), JSON_BODIES);
            for (final Class<?> type : OCFL_TYPES) {
                hints.reflection().registerType(type, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.ACCESS_DECLARED_FIELDS);
            }
        }

        private void registerServiceImplementations(final RuntimeHints hints, final ClassLoader classLoader,
                final String serviceFile) {
            final ClassLoader loader = classLoader != null ? classLoader : getClass().getClassLoader();
            try {
                final Enumeration<URL> urls = loader.getResources(serviceFile);
                while (urls.hasMoreElements()) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(urls.nextElement().openStream(), StandardCharsets.UTF_8))) {
                        reader.lines()
                            .map(line -> line.replaceAll("#.*", "").trim())
                            .filter(line -> !line.isEmpty())
                            .forEach(type -> hints.reflection().registerType(TypeReference.of(type),
                                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS));
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read " + serviceFile, e);
            }
        }

    }

}
