package com.articulorium.harvest.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.articulorium.harvest.classify.SourcePolicy;
import com.articulorium.harvest.ingest.FileCopier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.scheduling.support.CronExpression;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks the harvest sources of the compose stack ({@code docker-compose.yml}, with the defaults of
 * its variables) as the service binds them: arXiv is harvested on a schedule and on startup, and
 * its classified records are accepted automatically, while the other sources follow the
 * classification settings.
 */
class ComposeSourcesTest {

    private static final Path COMPOSE = Path.of("..", "docker-compose.yml");

    private static final Pattern JSON = Pattern.compile("SPRING_APPLICATION_JSON: >-\\R((?: {8,}.*\\R)+)");

    /** {@code ${NAME:-default}} and {@code ${NAME-default}}, innermost first. */
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{\\w+:?-([^${}]*)}");

    private static HarvestProperties compose(final Map<String, String> environment) throws IOException {
        assumeTrue(Files.exists(COMPOSE), "built without the compose file");
        final String compose = Files.readString(COMPOSE, StandardCharsets.UTF_8);
        final Matcher harvest = JSON.matcher(compose.substring(compose.indexOf("\n  harvest:")));
        assertThat(harvest.find()).as("the harvest service's SPRING_APPLICATION_JSON").isTrue();
        String json = harvest.group(1);
        for (final Map.Entry<String, String> variable : environment.entrySet()) {
            json = json.replaceAll("\\$\\{" + variable.getKey() + ":?-[^${}]*(\\$\\{[^${}]*})?[^${}]*}",
                Matcher.quoteReplacement(variable.getValue()));
        }
        for (String previous = null; !json.equals(previous);) {
            previous = json;
            json = VARIABLE.matcher(json).replaceAll(match -> Matcher.quoteReplacement(match.group(1)));
        }
        final Map<String, Object> flat = new LinkedHashMap<>();
        flatten("", JsonMapper.builder().build().readTree(json), flat);
        return new Binder(new MapConfigurationPropertySource(flat))
            .bind("articulorium.harvest", HarvestProperties.class).get();
    }

    private static void flatten(final String prefix, final JsonNode node, final Map<String, Object> flat) {
        if (node.isObject()) {
            node.properties().forEach(entry -> flatten(prefix.isEmpty() ? entry.getKey()
                : prefix + "." + entry.getKey(), entry.getValue(), flat));
        } else {
            flat.put(prefix, node.asString());
        }
    }

    @Test
    void harvestsArxivOnAScheduleAndAcceptsItsRecordsAutomatically() throws IOException {
        final HarvestProperties properties = compose(Map.of());
        // what the compose file passes as ARTICULORIUM_HARVEST_CLASSIFICATION_AUTO_ACCEPT by default
        properties.getClassification().setAutoAccept(false);

        final HarvestProperties.Source arxiv = properties.getSources().get("arxiv");
        assertThat(arxiv.getType()).isEqualTo("oai-pmh");
        assertThat(CronExpression.isValidExpression(arxiv.getSchedule())).as(arxiv.getSchedule()).isTrue();
        assertThat(arxiv.isHarvestOnStartup()).isTrue();
        assertThat(arxiv.getInitialWindow()).isEqualTo(Duration.ofDays(7));
        assertThat(arxiv.getAutoAccept()).isTrue();
        // its PDFs are copied into the placed items, from arXiv's host for programmatic access
        assertThat(arxiv.isCopyFiles()).isTrue();
        assertThat(FileCopier.hostsOf(properties, "arxiv")).isEqualTo(Set.of("oaipmh.arxiv.org", "export.arxiv.org"));
        assertThat(arxiv.getOptions().get("file-media-type")).isEqualTo("application/pdf");
        assertThat(java.util.regex.Pattern.matches(arxiv.getOptions().get("file-pattern"),
            "http://arxiv.org/abs/2611.17233")).isTrue();
        assertThat(properties.getSources().get("self-oai").isCopyFiles()).isFalse();
        final SourcePolicy policy = SourcePolicy.of(properties, "arxiv");
        assertThat(policy.classify()).isTrue();
        assertThat(policy.autoAccept()).isTrue();
        assertThat(policy.createCommunities() && policy.createCollections() && policy.createTopologies()).isTrue();
        // the demo sources harvest the repository itself: on demand, never classified
        for (final String demo : new String[] {"self-oai", "self-ldp"}) {
            assertThat(properties.getSources().get(demo).getSchedule()).isNull();
            assertThat(SourcePolicy.of(properties, demo).classify()).isFalse();
        }
    }

    @Test
    void theEnvironmentTurnsScheduleAndAutomaticAcceptanceOff() throws IOException {
        final HarvestProperties.Source reviewed = compose(Map.of("ARTICULORIUM_HARVEST_AUTO_ACCEPT", "false"))
            .getSources().get("arxiv");
        assertThat(reviewed.getAutoAccept()).as("ARTICULORIUM_HARVEST_AUTO_ACCEPT=false reviews arXiv too").isFalse();

        final HarvestProperties.Source manual = compose(Map.of("ARXIV_SCHEDULE", "", "ARXIV_HARVEST_ON_STARTUP",
            "false", "ARXIV_AUTO_ACCEPT", "false", "ARXIV_COPY_FILES", "false")).getSources().get("arxiv");
        assertThat(manual.isCopyFiles()).isFalse();
        assertThat(manual.getSchedule()).as("an empty ARXIV_SCHEDULE harvests on demand only").isNullOrEmpty();
        assertThat(manual.isHarvestOnStartup()).isFalse();
        assertThat(manual.getAutoAccept()).isFalse();
    }

}
