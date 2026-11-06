package com.articulorium.harvest.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Tests the shared record representation, settings and the independence of the core package.
 */
class CoreModelTest {

    @Test
    void recordsNeedAnIdentifierAndAnAbsoluteSubject() {
        assertThatThrownBy(() -> HarvestedRecord.of(" ", "https://x.test/a", null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HarvestedRecord.of("a", "a", null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HarvestedRecord.of("a", "https://x.test/a b", null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(HarvestedRecord.of("a", "oai:arXiv.org:1234.5678", null, null).metadata().isEmpty()).isTrue();
        assertThat(HarvestedRecord.deleted("a", "https://x.test/a", null).deleted()).isTrue();
    }

    @Test
    void filesNeedAnAbsoluteLocation() {
        assertThatThrownBy(() -> new HarvestedFile(URI.create("a.pdf"), null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void settingsValidateTheIdentifierUrlAndOptions() {
        final SourceSettings settings = new SourceSettings("arxiv", "oai-pmh", URI.create("https://export.arxiv.org/oai2"),
            50, null, Map.of("set", " cs ", "blank", " ", "flag", "TRUE", "bad", "yes"));

        assertThat(settings.base()).isEqualTo("https://export.arxiv.org/oai2/");
        assertThat(settings.option("set")).contains("cs");
        assertThat(settings.option("blank")).isEmpty();
        assertThat(settings.option("missing", "x")).isEqualTo("x");
        assertThat(settings.flag("flag", false)).isTrue();
        assertThatThrownBy(() -> settings.flag("bad", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceSettings("a b", "t", URI.create("https://x.test"), 1, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceSettings("a", "t", URI.create("ftp://x.test"), 1, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceSettings("a", "t", URI.create("https://x.test"), 0, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void credentialsBuildAuthorizationHeadersAndNeverPrintSecrets() {
        assertThat(new SourceCredentials("u", "p", null).authorization()).isEqualTo("Basic dTpw");
        assertThat(new SourceCredentials("u", "p", "t").authorization()).isEqualTo("Bearer t");
        assertThat(SourceCredentials.NONE.authorization()).isNull();
        assertThat(new SourceCredentials("u", "secret", "token").toString()).doesNotContain("secret", "token=t");
    }

    @Test
    void theCoreDependsOnNoFrameworkAndNoSource() throws IOException {
        final Path core = Path.of("src/main/java/com/articulorium/harvest/core");
        final List<String> offending;
        try (Stream<Path> files = Files.list(core)) {
            offending = files.flatMap(file -> {
                try {
                    return Files.readAllLines(file).stream()
                        .filter(line -> line.startsWith("import "))
                        .filter(line -> line.contains("org.springframework") || line.contains("com.articulorium.harvest.")
                            && !line.contains("com.articulorium.harvest.core."))
                        .map(line -> file.getFileName() + ": " + line);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }
        assertThat(offending).isEmpty();
    }

}
