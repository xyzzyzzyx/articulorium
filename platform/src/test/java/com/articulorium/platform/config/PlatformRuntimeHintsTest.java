package com.articulorium.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import com.articulorium.platform.webac.WebAcProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.core.io.ClassPathResource;

import io.ocfl.core.extension.OcflExtensionRegistry;
import io.ocfl.core.extension.storage.layout.OcflStorageLayoutExtension;
import io.ocfl.core.extension.storage.layout.config.HashedNTupleLayoutConfig;
import io.ocfl.core.model.Inventory;

/**
 * Tests the GraalVM native-image hints of the platform: a native image holds only the class path
 * resources named by a hint, so every resource the platform opens at run time must be covered.
 */
class PlatformRuntimeHintsTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    private final RuntimeHints hints = new RuntimeHints();

    @BeforeEach
    void register() {
        new PlatformRuntimeHints.Registrar().registerHints(hints, getClass().getClassLoader());
    }

    @Test
    void includesTheDefaultRootAcl() {
        final ClassPathResource rootAcl = (ClassPathResource) new WebAcProperties().getRootAcl();

        assertThat(RuntimeHintsPredicates.resource().forResource(rootAcl.getPath())).accepts(hints);
    }

    @Test
    void includesEveryRootAclShippedWithThePlatform() {
        assertThat(List.of("webac/root-acl.ttl", "webac/root-acl-oidc.ttl", "webac/root-acl-consul.ttl"))
            .allSatisfy(acl -> {
                assertThat(new ClassPathResource(acl).exists()).as("%s exists", acl).isTrue();
                assertThat(RuntimeHintsPredicates.resource().forResource(acl)).as(acl).accepts(hints);
            });
    }

    /**
     * Every file below {@code src/main/resources} is either covered by a hint or included by
     * something else: Spring Boot includes the application configuration, the i18n starter its
     * message bundles.
     */
    @Test
    void includesEveryResourceOfThePlatform() throws IOException {
        try (Stream<Path> files = Files.walk(RESOURCES)) {
            final List<String> resources = files.filter(Files::isRegularFile)
                .map(file -> RESOURCES.relativize(file).toString().replace('\\', '/'))
                .filter(name -> !name.startsWith("application") && !name.startsWith("i18n/"))
                .toList();

            assertThat(resources).isNotEmpty().allSatisfy(resource ->
                assertThat(RuntimeHintsPredicates.resource().forResource(resource)).as(resource).accepts(hints));
        }
    }

    @Test
    void registersJenaServiceImplementationsForReflection() {
        assertThat(hints.reflection().typeHints()).isNotEmpty();
        assertThat(RuntimeHintsPredicates.resource()
            .forResource("META-INF/services/org.apache.jena.sys.JenaSubsystemLifecycle")).accepts(hints);
    }

    /**
     * ocfl-java instantiates the layout extension named by the storage root, and that extension's
     * configuration, through their no-argument constructors.
     */
    @Test
    void registersTheDefaultOcflLayoutExtensionAndItsConfigurationForInstantiation() {
        final OcflStorageLayoutExtension extension = OcflExtensionRegistry
            .<OcflStorageLayoutExtension>lookup(new HashedNTupleLayoutConfig().getExtensionName())
            .orElseThrow();

        assertThat(List.of(extension.getClass(), extension.getExtensionConfigClass())).allSatisfy(type ->
            assertThat(RuntimeHintsPredicates.reflection().onConstructorInvocation(type.getDeclaredConstructor()))
                .as(type.getName()).accepts(hints));
    }

    @Test
    void registersTheOcflInventoryModelForJackson() throws NoSuchMethodException {
        assertThat(RuntimeHintsPredicates.reflection()
            .onMethodInvocation(Inventory.JacksonBuilder.class.getMethod("withId", String.class))).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
            .onMethodInvocation(Inventory.class.getMethod("getId"))).accepts(hints);
    }

    @Test
    void includesTheResourcesOcflReadsFromItsJar() {
        assertThat(List.of("ocfl-specs/ocfl_1.1.md", "ocfl-specs/0004-hashed-n-tuple-storage-layout.md",
                "db/postgresql/ocfl_object_lock.ddl.tmpl"))
            .allSatisfy(resource -> {
                assertThat(new ClassPathResource(resource).exists()).as("%s exists", resource).isTrue();
                assertThat(RuntimeHintsPredicates.resource().forResource(resource)).as(resource).accepts(hints);
            });
    }

}
