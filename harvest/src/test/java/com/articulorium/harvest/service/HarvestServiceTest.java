package com.articulorium.harvest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.articulorium.harvest.config.HarvestProperties;
import com.articulorium.harvest.core.HarvestMode;
import com.articulorium.harvest.core.HarvestOutcome;
import com.articulorium.harvest.core.HarvestPage;
import com.articulorium.harvest.core.HarvestPolicy;
import com.articulorium.harvest.core.HarvestSource;
import com.articulorium.harvest.core.HarvestSourceFactory;
import com.articulorium.harvest.core.HarvestWindow;
import com.articulorium.harvest.core.HarvestedRecord;
import com.articulorium.harvest.core.Harvester;
import com.articulorium.harvest.core.IngestAction;
import com.articulorium.harvest.core.InMemoryCheckpointStore;
import com.articulorium.harvest.core.RecordFailure;
import com.articulorium.harvest.core.SourceCapabilities;
import com.articulorium.harvest.core.SourceSettings;
import com.articulorium.harvest.service.HarvestRequestException.Reason;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/**
 * Tests running configured sources: single flight, cancellation, history, metrics, schedules and
 * configuration errors.
 */
class HarvestServiceTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final TaskScheduler scheduler = mock(TaskScheduler.class);

    private final CountDownLatch release = new CountDownLatch(1);

    private final CountDownLatch fetching = new CountDownLatch(1);

    private final InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();

    /** The window of the latest fetch. */
    private volatile HarvestWindow fetched;

    private HarvestService service;

    @BeforeEach
    void setUp() {
        service = service(Map.of("blocking", source("gated", true, "0 0 3 * * *"),
            "off", source("gated", false, null)));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        release.countDown();
        service.shutdown();
    }

    private static HarvestProperties.Source source(final String type, final boolean enabled, final String schedule) {
        final HarvestProperties.Source source = new HarvestProperties.Source();
        source.setType(type);
        source.setUrl("https://source.test/");
        source.setEnabled(enabled);
        source.setSchedule(schedule);
        return source;
    }

    private HarvestService service(final Map<String, HarvestProperties.Source> sources) {
        final HarvestProperties properties = new HarvestProperties();
        properties.getSources().putAll(new java.util.TreeMap<>(sources));
        properties.setHistory(2);
        final Harvester harvester = new Harvester(HarvestPolicy.DEFAULT, Clock.systemUTC(), duration -> { });
        return new HarvestService(properties, List.of(new GatedFactory()), harvester,
            (sourceId, record) -> record.identifier().startsWith("bad") ? fail(record) : IngestAction.CREATED,
            checkpoints, scheduler, meters, Clock.systemUTC());
    }

    private static IngestAction fail(final HarvestedRecord record) {
        throw com.articulorium.harvest.core.HarvestException.fatal("refused " + record.identifier(), null);
    }

    private SourceStatus awaitIdle(final String id) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            final SourceStatus status = service.status(id).orElseThrow();
            if (status.running() == null) {
                return status;
            }
            Thread.sleep(25);
        }
        throw new AssertionError(id + " is still running");
    }

    @Test
    void runsOneHarvestOfASourceAtATimeAndKeepsItsReports() throws InterruptedException {
        final SourceStatus started = service.start("blocking", HarvestMode.FULL);
        assertThat(started.running()).isNotNull();
        assertThat(started.running().mode()).isEqualTo(HarvestMode.FULL);
        assertThat(fetching.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.start("blocking", HarvestMode.INCREMENTAL))
            .isInstanceOfSatisfying(HarvestRequestException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.RUNNING));

        release.countDown();
        final SourceStatus done = awaitIdle("blocking");

        assertThat(done.reports()).singleElement().satisfies(report -> {
            assertThat(report.outcome()).isEqualTo(HarvestOutcome.COMPLETED_WITH_ERRORS);
            assertThat(report.created()).isEqualTo(1);
            assertThat(report.failures()).extracting(RecordFailure::identifier).containsExactly("bad-1");
        });
        assertThat(meters.get("harvest.runs").tag("source", "blocking").tag("outcome", "COMPLETED_WITH_ERRORS")
            .counter().count()).isEqualTo(1);
        assertThat(meters.get("harvest.records").tag("action", "CREATED").counter().count()).isEqualTo(1);
        assertThat(meters.get("harvest.records").tag("action", "FAILED").counter().count()).isEqualTo(1);
        assertThat(meters.get("harvest.duration").timer().count()).isEqualTo(1);

        // only the latest two reports are kept
        service.start("blocking", HarvestMode.INCREMENTAL);
        awaitIdle("blocking");
        service.start("blocking", HarvestMode.INCREMENTAL);
        assertThat(awaitIdle("blocking").reports()).hasSize(2)
            .allSatisfy(report -> assertThat(report.mode()).isEqualTo(HarvestMode.INCREMENTAL));
    }

    @Test
    void cancelsARunningHarvest() throws InterruptedException {
        service.start("blocking", HarvestMode.INCREMENTAL);
        assertThat(fetching.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(service.cancel("blocking").running().cancelled()).isTrue();
        release.countDown();

        assertThat(awaitIdle("blocking").reports().get(0).outcome()).isEqualTo(HarvestOutcome.CANCELLED);
        assertThatThrownBy(() -> service.cancel("blocking"))
            .isInstanceOfSatisfying(HarvestRequestException.class,
                e -> assertThat(e.getReason()).isEqualTo(Reason.NOT_RUNNING));
    }

    @Test
    void refusesUnknownAndDisabledSources() {
        assertThatThrownBy(() -> service.start("nope", HarvestMode.FULL))
            .isInstanceOfSatisfying(HarvestRequestException.class,
                e -> assertThat(e.getReason()).isEqualTo(Reason.UNKNOWN_SOURCE));
        assertThatThrownBy(() -> service.start("off", HarvestMode.FULL))
            .isInstanceOfSatisfying(HarvestRequestException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.DISABLED));
        assertThat(service.status("nope")).isEmpty();
        assertThat(service.statuses()).extracting(SourceStatus::id).containsExactly("blocking", "off");
        assertThat(service.status("off").orElseThrow().url()).isEqualTo("https://source.test/");
    }

    @Test
    void schedulesEnabledSourcesWithASchedule() {
        service.schedule();

        verify(scheduler, times(1)).schedule(any(Runnable.class),
            argThat((CronTrigger trigger) -> "0 0 3 * * *".equals(trigger.getExpression())));
    }

    @Test
    void harvestsEnabledSourcesMarkedForStartupOnceReady() throws InterruptedException {
        service.shutdown();
        final HarvestProperties.Source onStartup = source("gated", true, null);
        onStartup.setHarvestOnStartup(true);
        final HarvestProperties.Source disabled = source("gated", false, null);
        disabled.setHarvestOnStartup(true);
        service = service(Map.of("eager", onStartup, "off", disabled, "lazy", source("gated", true, null)));

        service.harvestOnStartup();

        assertThat(service.status("eager").orElseThrow().running()).isNotNull()
            .satisfies(running -> assertThat(running.mode()).isEqualTo(HarvestMode.INCREMENTAL));
        assertThat(service.status("lazy").orElseThrow().running()).isNull();
        assertThat(service.status("off").orElseThrow().running()).isNull();
        release.countDown();
        assertThat(awaitIdle("eager").reports()).hasSize(1);
    }

    @Test
    void startsTheFirstIncrementalHarvestWithinTheInitialWindow() throws InterruptedException {
        service.shutdown();
        release.countDown();
        final HarvestProperties.Source windowed = source("gated", true, null);
        windowed.setInitialWindow(Duration.ofDays(7));
        service = service(Map.of("windowed", windowed));

        final Instant before = Instant.now();
        service.start("windowed", HarvestMode.INCREMENTAL);
        final SourceStatus first = awaitIdle("windowed");
        assertThat(fetched.from()).isBetween(before.minus(Duration.ofDays(7)), Instant.now().minus(Duration.ofDays(7)));
        assertThat(first.reports().get(0).from()).isEqualTo(fetched.from());

        // a checkpoint takes precedence over the initial window
        final Instant checkpoint = Instant.parse("2026-11-01T00:00:00Z");
        checkpoints.save("windowed", checkpoint);
        service.start("windowed", HarvestMode.INCREMENTAL);
        awaitIdle("windowed");
        assertThat(fetched.from()).isEqualTo(checkpoint);

        // full harvests are not limited
        service.start("windowed", HarvestMode.FULL);
        awaitIdle("windowed");
        assertThat(fetched).isEqualTo(HarvestWindow.ALL);
    }

    @Test
    void harvestsEveryRecordTheFirstTimeWithoutAnInitialWindow() throws InterruptedException {
        release.countDown();
        service.start("blocking", HarvestMode.INCREMENTAL);
        awaitIdle("blocking");

        assertThat(fetched).isEqualTo(HarvestWindow.ALL);
    }

    @Test
    void failsTheStartupOnMisconfiguredSources() {
        final HarvestProperties.Source unknown = source("gopher", true, null);
        final HarvestProperties.Source noUrl = source("gated", true, null);
        noUrl.setUrl(" ");
        final HarvestProperties.Source badCron = source("gated", true, "every night");
        final HarvestProperties.Source credentialsInUrl = source("gated", true, null);
        credentialsInUrl.setUrl("https://user:pass@source.test/");

        assertThatThrownBy(() -> service(Map.of("x", unknown)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("x").hasMessageContaining("gopher");
        assertThatThrownBy(() -> service(Map.of("x", noUrl))).hasMessageContaining("url");
        assertThatThrownBy(() -> service(Map.of("x", badCron))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service(Map.of("x", credentialsInUrl)))
            .hasMessageContaining("credentials").hasMessageNotContaining("pass@");
        assertThatThrownBy(() -> service(Map.of("bad id", source("gated", true, null))))
            .isInstanceOf(IllegalStateException.class);
        final HarvestProperties.Source negativeWindow = source("gated", true, null);
        negativeWindow.setInitialWindow(Duration.ofDays(-1));
        assertThatThrownBy(() -> service(Map.of("x", negativeWindow))).hasMessageContaining("initial-window");
    }

    /** Creates sources whose first fetch waits for the test. */
    private final class GatedFactory implements HarvestSourceFactory {

        @Override
        public String type() {
            return "gated";
        }

        @Override
        public HarvestSource create(final SourceSettings settings) {
            return new HarvestSource() {
                @Override
                public String id() {
                    return settings.id();
                }

                @Override
                public String type() {
                    return "gated";
                }

                @Override
                public SourceCapabilities capabilities() {
                    return new SourceCapabilities(true, false);
                }

                @Override
                public HarvestPage fetch(final HarvestWindow window, final String cursor) {
                    fetched = window;
                    fetching.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("not released");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return HarvestPage.of(List.of(HarvestedRecord.of("ok-1", "https://source.test/1", null, null),
                        HarvestedRecord.of("bad-1", "https://source.test/2", null, null)), null);
                }
            };
        }

    }

    @Test
    void theDefaultPolicyIsSane() {
        assertThat(HarvestPolicy.DEFAULT.delay(10, null)).isEqualTo(Duration.ofMinutes(1));
    }

}
