package com.articulorium.harvest.core;

import static com.articulorium.harvest.core.ScriptedSource.record;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the common harvest workflow against scripted sources.
 */
class HarvesterTest {

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");

    private static final SourceCapabilities INCREMENTAL = new SourceCapabilities(true, true);

    private final List<Duration> sleeps = new ArrayList<>();

    private final InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();

    private RecordingIngestor ingestor;

    private Harvester harvester;

    @BeforeEach
    void setUp() {
        ingestor = new RecordingIngestor();
        harvester = new Harvester(new HarvestPolicy(3, Duration.ofSeconds(2), Duration.ofSeconds(30), 3, 2,
            Duration.ofMinutes(5)), Clock.fixed(NOW, ZoneOffset.UTC), sleeps::add);
    }

    @Test
    void pagesThroughTheSourceAndMovesTheCheckpoint() {
        final ScriptedSource source = new ScriptedSource("s", INCREMENTAL)
            .then(HarvestPage.of(List.of(record("a"), record("b")), "page-2"))
            .then(HarvestPage.of(List.of(record("c"), HarvestedRecord.deleted("d", "https://source.test/d", null)),
                null));
        ingestor.existing.put("b", true);
        ingestor.existing.put("d", true);

        final HarvestReport report = harvester.run(source, ingestor, checkpoints, HarvestMode.INCREMENTAL);

        assertThat(report.outcome()).isEqualTo(HarvestOutcome.COMPLETED);
        assertThat(source.cursors()).containsExactly(null, "page-2");
        assertThat(ingestor.ingested).containsExactly("a", "b", "c", "d");
        assertThat(ingestor.begun).isEqualTo(1);
        assertThat(report.pages()).isEqualTo(2);
        assertThat(report.harvested()).isEqualTo(4);
        assertThat(report.created()).isEqualTo(2);
        assertThat(report.updated()).isEqualTo(1);
        assertThat(report.deleted()).isEqualTo(1);
        assertThat(report.from()).isNull();
        assertThat(report.checkpoint()).isEqualTo(NOW.minus(Duration.ofMinutes(5)));
        assertThat(checkpoints.load("s")).contains(NOW.minus(Duration.ofMinutes(5)));
    }

    @Test
    void incrementalHarvestsStartAtTheCheckpointAndPreferTheSourcesClock() {
        final Instant checkpoint = Instant.parse("2026-11-01T00:00:00Z");
        final Instant sourceTime = Instant.parse("2026-11-06T11:58:00Z");
        checkpoints.save("s", checkpoint);
        final ScriptedSource source = new ScriptedSource("s", INCREMENTAL)
            .then(new HarvestPage(List.of(record("a")), List.of(), null, sourceTime));

        final HarvestReport report = harvester.run(source, ingestor, checkpoints, HarvestMode.INCREMENTAL);

        assertThat(source.windows()).containsExactly(new HarvestWindow(checkpoint));
        assertThat(report.from()).isEqualTo(checkpoint);
        assertThat(checkpoints.load("s")).contains(sourceTime.minus(Duration.ofMinutes(5)));
    }

    @Test
    void fullHarvestsIgnoreTheCheckpoint() {
        checkpoints.save("s", Instant.parse("2026-11-01T00:00:00Z"));
        final ScriptedSource source = new ScriptedSource("s", INCREMENTAL).then(HarvestPage.empty());

        final HarvestReport report = harvester.run(source, ingestor, checkpoints, HarvestMode.FULL);

        assertThat(source.windows()).containsExactly(HarvestWindow.ALL);
        assertThat(report.outcome()).isEqualTo(HarvestOutcome.COMPLETED);
        assertThat(checkpoints.load("s")).contains(NOW.minus(Duration.ofMinutes(5)));
    }

    @Test
    void sourcesThatCannotListChangesAreAlwaysHarvestedInFullAndKeepNoCheckpoint() {
        checkpoints.save("s", Instant.parse("2026-11-01T00:00:00Z"));
        final ScriptedSource source = new ScriptedSource("s", new SourceCapabilities(false, false))
            .then(HarvestPage.of(List.of(record("a")), null));

        final HarvestReport report = harvester.run(source, ingestor, checkpoints, HarvestMode.INCREMENTAL);

        assertThat(source.windows()).containsExactly(HarvestWindow.ALL);
        assertThat(report.outcome()).isEqualTo(HarvestOutcome.COMPLETED);
        assertThat(report.checkpoint()).isNull();
        assertThat(checkpoints.load("s")).contains(Instant.parse("2026-11-01T00:00:00Z"));
    }

    @Test
    void retriesRecoverablePageFailuresWithBackoffAndRetryAfter() {
        final ScriptedSource source = new ScriptedSource("s", INCREMENTAL)
            .thenFail(HarvestException.recoverable("connection reset", null))
            .thenFail(new HarvestException("503", true, Duration.ofSeconds(20), null))
            .then(HarvestPage.of(List.of(record("a")), null));

        final HarvestReport report = harvester.run(source, ingestor, checkpoints, HarvestMode.INCREMENTAL);

        assertThat(report.outcome()).isEqualTo(HarvestOutcome.COMPLETED);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(20));
        assertThat(source.cursors()).containsExactly(null, null, null);
    }

    @Test
    void failsWithoutMovingTheCheckpointWhenRetriesRunOut() {
        final ScriptedSource source = new ScriptedSource("s", INCREMENTAL)
            .then(HarvestPage.of(List.of(record("a")), "2"))
            .thenFail(HarvestException.recoverable("timeout", null))
            .thenFail(HarvestException.recoverable("timeout", null))
            .thenFail(HarvestException.recoverable("timeout", null));

        final HarvestReport report = harvester.run(source, ingestor, checkpoints, HarvestMode.INCREMENTAL);

        assertThat(report.outcome()).isEqualTo(HarvestOutcome.FAILED);
        assertThat(report.message()).isEqualTo("timeout");
        assertThat(report.created()).isEqualTo(1);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4));
        assertThat(checkpoints.load("s")).isEmpty();
    }

    @Test
    void doesNotRetryFatalSourceFailures() {
        final ScriptedSource source = new ScriptedSource("s", INCREMENTAL)
            .thenFail(HarvestException.fatal("401 Unauthorized", null));

        final HarvestReport report = harvester.run(source, ingestor, checkpoints, HarvestMode.INCREMENTAL);

        assertThat(report.outcome()).isEqualTo(HarvestOutcome.FAILED);
        assertThat(report.message()).isEqualTo("401 Unauthorized");
        assertThat(sleeps).isEmpty();
    }

    @Test
    void reportsRecordFailuresAndKeepsTheCheckpointSoTheyAreRetried() {
        checkpoints.save("s", Instant.parse("2026-11-01T00:00:00Z"));
        ingestor.refuse.put("b", HarvestException.fatal("400 invalid RDF", null));
        final ScriptedSource source = new ScriptedSource("s", INCREMENTAL)
            .then(new HarvestPage(List.of(record("a"), record("b"), record("c")),
                List.of(new RecordFailure("x", "unreadable metadata")), null, null));

        final HarvestReport report = harvester.run(source, ingestor, checkpoints, HarvestMode.INCREMENTAL);

        assertThat(report.outcome()).isEqualTo(HarvestOutcome.COMPLETED_WITH_ERRORS);
        assertThat(report.harvested()).isEqualTo(4);
        assertThat(report.created()).isEqualTo(2);
        assertThat(report.failed()).isEqualTo(2);
        assertThat(report.failures()).containsExactly(new RecordFailure("x", "unreadable metadata"),
            new RecordFailure("b", "400 invalid RDF"));
        assertThat(report.checkpoint()).isNull();
        assertThat(checkpoints.load("s")).contains(Instant.parse("2026-11-01T00:00:00Z"));
    }

    @Test
    void stopsAfterTooManyFailuresAndListsOnlyTheFirstOnes() {
        final ScriptedSource source = new ScriptedSource("s", INCREMENTAL)
            .then(HarvestPage.of(List.of(record("a"), record("b"), record("c"), record("d")), null));
        List.of("a", "b", "c", "d").forEach(id -> ingestor.refuse.put(id, HarvestException.fatal("403", null)));

        final HarvestReport report = harvester.run(source, ingestor, checkpoints, HarvestMode.INCREMENTAL);

        assertThat(report.outcome()).isEqualTo(HarvestOutcome.FAILED);
        assertThat(report.message()).isEqualTo("Stopped after 3 failed records");
        assertThat(report.failed()).isEqualTo(3);
        assertThat(report.failures()).hasSize(2);
        assertThat(ingestor.attempts).doesNotContainKey("d");
    }

    @Test
    void abortsWhenTheTargetStaysUnavailable() {
        ingestor.refuse.put("a", HarvestException.recoverable("platform unreachable", null));
        final ScriptedSource source = new ScriptedSource("s", INCREMENTAL)
            .then(HarvestPage.of(List.of(record("a"), record("b")), null));

        final HarvestReport report = harvester.run(source, ingestor, checkpoints, HarvestMode.INCREMENTAL);

        assertThat(report.outcome()).isEqualTo(HarvestOutcome.FAILED);
        assertThat(report.message()).isEqualTo("platform unreachable");
        assertThat(ingestor.attempts.get("a")).hasValue(3);
        assertThat(ingestor.attempts).doesNotContainKey("b");
        assertThat(checkpoints.load("s")).isEmpty();
    }

    @Test
    void retriesARecordWhoseIngestFailsOnce() {
        ingestor.refuseOnce.put("a", HarvestException.recoverable("502", null));
        final ScriptedSource source = new ScriptedSource("s", INCREMENTAL)
            .then(HarvestPage.of(List.of(record("a")), null));

        final HarvestReport report = harvester.run(source, ingestor, checkpoints, HarvestMode.INCREMENTAL);

        assertThat(report.outcome()).isEqualTo(HarvestOutcome.COMPLETED);
        assertThat(report.created()).isEqualTo(1);
        assertThat(ingestor.attempts.get("a")).hasValue(2);
    }

    @Test
    void reportsUnexpectedIngestErrorsAsRecordFailures() {
        ingestor.refuse.put("a", new IllegalStateException("bug"));
        final ScriptedSource source = new ScriptedSource("s", INCREMENTAL)
            .then(HarvestPage.of(List.of(record("a"), record("b")), null));

        final HarvestReport report = harvester.run(source, ingestor, checkpoints, HarvestMode.INCREMENTAL);

        assertThat(report.outcome()).isEqualTo(HarvestOutcome.COMPLETED_WITH_ERRORS);
        assertThat(report.failures()).singleElement().satisfies(failure -> assertThat(failure.reason()).contains("bug"));
        assertThat(report.created()).isEqualTo(1);
    }

    @Test
    void failsWhenTheTargetCannotBePrepared() {
        ingestor.beginFailure = HarvestException.fatal("403 on /harvested", null);
        final ScriptedSource source = new ScriptedSource("s", INCREMENTAL);

        final HarvestReport report = harvester.run(source, ingestor, checkpoints, HarvestMode.INCREMENTAL);

        assertThat(report.outcome()).isEqualTo(HarvestOutcome.FAILED);
        assertThat(source.cursors()).isEmpty();
    }

    @Test
    void retriesPreparingTheTargetAndSavingTheCheckpoint() {
        ingestor.beginFailures.add(HarvestException.recoverable("platform restarting", null));
        final AtomicInteger saves = new AtomicInteger();
        final CheckpointStore flaky = new CheckpointStore() {
            @Override
            public java.util.Optional<Instant> load(final String sourceId) {
                return checkpoints.load(sourceId);
            }

            @Override
            public void save(final String sourceId, final Instant checkpoint) {
                if (saves.incrementAndGet() == 1) {
                    throw HarvestException.recoverable("502", null);
                }
                checkpoints.save(sourceId, checkpoint);
            }
        };
        final ScriptedSource source = new ScriptedSource("s", INCREMENTAL).then(HarvestPage.of(List.of(record("a")), null));

        final HarvestReport report = harvester.run(source, ingestor, flaky, HarvestMode.INCREMENTAL);

        assertThat(report.outcome()).isEqualTo(HarvestOutcome.COMPLETED);
        assertThat(ingestor.begun).isEqualTo(2);
        assertThat(saves).hasValue(2);
        assertThat(checkpoints.load("s")).isPresent();
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void stopsWhenTheSourceRepeatsItsCursor() {
        final ScriptedSource source = new ScriptedSource("s", INCREMENTAL)
            .then(HarvestPage.of(List.of(), "same"))
            .then(HarvestPage.of(List.of(), "same"));

        final HarvestReport report = harvester.run(source, ingestor, checkpoints, HarvestMode.INCREMENTAL);

        assertThat(report.outcome()).isEqualTo(HarvestOutcome.FAILED);
        assertThat(report.message()).contains("same cursor");
    }

    @Test
    void stopsWhenCancelled() {
        final AtomicInteger checks = new AtomicInteger();
        final ScriptedSource source = new ScriptedSource("s", INCREMENTAL)
            .then(HarvestPage.of(List.of(record("a"), record("b")), "2"));

        final HarvestReport report = harvester.run(source, ingestor, checkpoints, HarvestMode.INCREMENTAL,
            HarvestListener.NONE, () -> checks.incrementAndGet() > 2);

        assertThat(report.outcome()).isEqualTo(HarvestOutcome.CANCELLED);
        assertThat(ingestor.ingested).containsExactly("a");
        assertThat(checkpoints.load("s")).isEmpty();
    }

    @Test
    void tellsTheListenerAboutEveryRecord() {
        final List<String> events = new ArrayList<>();
        ingestor.refuse.put("b", HarvestException.fatal("no", null));
        final ScriptedSource source = new ScriptedSource("s", INCREMENTAL)
            .then(HarvestPage.of(List.of(record("a"), record("b")), null));

        harvester.run(source, ingestor, checkpoints, HarvestMode.INCREMENTAL, new HarvestListener() {
            @Override
            public void pageFetched(final HarvestPage page) {
                events.add("page " + page.records().size());
            }

            @Override
            public void recordIngested(final HarvestedRecord record, final IngestAction action) {
                events.add(record.identifier() + " " + action);
            }

            @Override
            public void recordFailed(final RecordFailure failure) {
                events.add(failure.identifier() + " failed");
            }
        }, () -> false);

        assertThat(events).containsExactly("page 2", "a CREATED", "b failed");
    }

    @Test
    void retryDelaysDoubleUpToTheLimit() {
        final HarvestPolicy policy = new HarvestPolicy(5, Duration.ofSeconds(2), Duration.ofSeconds(10), 0, 0,
            Duration.ZERO);

        assertThat(policy.delay(1, null)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delay(2, null)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.delay(3, null)).isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.delay(4, null)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.delay(1, Duration.ofMinutes(5))).isEqualTo(Duration.ofSeconds(10));
    }

    /** Ingests into a map, refusing scripted records. */
    private static final class RecordingIngestor implements RecordIngestor {

        private final List<String> ingested = new ArrayList<>();

        private final Map<String, Boolean> existing = new HashMap<>();

        private final Map<String, RuntimeException> refuse = new HashMap<>();

        private final Map<String, RuntimeException> refuseOnce = new HashMap<>();

        private final Map<String, AtomicInteger> attempts = new HashMap<>();

        private int begun;

        private RuntimeException beginFailure;

        private final List<RuntimeException> beginFailures = new ArrayList<>();

        @Override
        public void begin(final String sourceId) {
            begun++;
            if (beginFailure != null) {
                throw beginFailure;
            }
            if (!beginFailures.isEmpty()) {
                throw beginFailures.remove(0);
            }
        }

        @Override
        public IngestAction ingest(final String sourceId, final HarvestedRecord record) {
            attempts.computeIfAbsent(record.identifier(), id -> new AtomicInteger()).incrementAndGet();
            if (refuse.containsKey(record.identifier())) {
                throw refuse.get(record.identifier());
            }
            final RuntimeException once = refuseOnce.remove(record.identifier());
            if (once != null) {
                throw once;
            }
            ingested.add(record.identifier());
            final boolean existed = existing.containsKey(record.identifier());
            if (record.deleted()) {
                return existed ? IngestAction.DELETED : IngestAction.SKIPPED;
            }
            existing.put(record.identifier(), true);
            return existed ? IngestAction.UPDATED : IngestAction.CREATED;
        }

    }

}
