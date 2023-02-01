package com.articulorium.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

/**
 * Keeps the test configuration in step with the application's: the test {@code application.yml}
 * replaces the main one on the class path, so a model kind the application turns off must be
 * turned off in tests too, or a provider starter (OpenAI) builds clients that need an API key and
 * the application contexts of the tests do not start.
 */
class ModelSettingsTest {

    @Test
    void testsTurnOffTheSameModelKindsAsTheApplication() throws IOException {
        final Map<String, Object> main = models(Files.newInputStream(Path.of("src/main/resources/application.yml")));
        final Map<String, Object> test = models(new ClassPathResource("application.yml").getInputStream());
        main.remove("chat");
        assertThat(main).isNotEmpty();
        main.forEach((kind, value) -> assertThat(test).as("spring.ai.model.%s in the test configuration", kind)
            .containsEntry(kind, value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> models(final InputStream input) throws IOException {
        try (InputStream in = input) {
            final Map<String, Object> root = new Yaml().load(in);
            final Map<String, Object> spring = (Map<String, Object>) root.get("spring");
            final Map<String, Object> ai = (Map<String, Object>) spring.get("ai");
            return new LinkedHashMap<>((Map<String, Object>) ai.get("model"));
        }
    }

}
