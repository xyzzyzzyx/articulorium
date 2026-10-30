package com.articulorum.platform.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * GraalVM native-image hints for the platform.
 *
 * <p>Apache Jena initialises itself through {@link java.util.ServiceLoader} and reads several
 * property and resource files from its jars. This registrar, which runs at build time during
 * Spring AOT processing, includes those resources in the image and registers every class listed
 * in Jena's service files for reflective instantiation. The platform's own class path resources
 * are included as well.</p>
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

    /** Registers the hints. */
    static class Registrar implements RuntimeHintsRegistrar {

        @Override
        public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
            hints.resources().registerPattern("namespaces.yml");
            hints.resources().registerPattern("root.ttl");
            hints.resources().registerPattern("ldp-constraints.txt");
            hints.resources().registerPattern("META-INF/services/org.apache.jena.*");
            hints.resources().registerPattern("org/apache/jena/**");
            for (final String serviceFile : JENA_SERVICE_FILES) {
                registerServiceImplementations(hints, classLoader, serviceFile);
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
