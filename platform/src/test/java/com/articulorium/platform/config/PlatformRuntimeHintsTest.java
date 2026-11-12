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

}
