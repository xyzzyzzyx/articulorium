package com.articulorium.harvest.service;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import com.articulorium.harvest.config.HarvestConfiguration;
import com.articulorium.harvest.config.HarvestProperties;
import com.articulorium.harvest.core.CheckpointStore;
import com.articulorium.harvest.core.HarvestListener;
import com.articulorium.harvest.core.HarvestMode;
import com.articulorium.harvest.core.HarvestOutcome;
import com.articulorium.harvest.core.HarvestPage;
import com.articulorium.harvest.core.HarvestReport;
import com.articulorium.harvest.core.HarvestSource;
import com.articulorium.harvest.core.HarvestSourceFactory;
import com.articulorium.harvest.core.HarvestedRecord;
import com.articulorium.harvest.core.Harvester;
import com.articulorium.harvest.core.IngestAction;
import com.articulorium.harvest.core.RecordFailure;
import com.articulorium.harvest.core.RecordIngestor;
import com.articulorium.harvest.core.SourceCredentials;
import com.articulorium.harvest.core.SourceSettings;
import com.articulorium.harvest.service.HarvestRequestException.Reason;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Runs the configured sources: builds them from {@code articulorium.harvest.sources} at startup
 * (an invalid source fails the startup), starts harvests on request and on their schedules, never
 * two of one source at a time and at most {@code concurrency} at once, keeps the latest reports and
 * publishes metrics ({@code harvest.runs}, {@code harvest.records}, {@code harvest.duration}).
 *
 * <p>Several instances of the service would each run the schedules; run one instance, or schedule
 * harvests on one instance only (the harvests themselves are idempotent).</p>
 */
@Service
public class HarvestService {

    private static final Logger LOG = LoggerFactory.getLogger(HarvestService.class);

    private final Map<String, Configured> sources = new LinkedHashMap<>();

    private final Harvester harvester;

    private final RecordIngestor ingestor;

    private final CheckpointStore checkpoints;

    private final TaskScheduler scheduler;

    private final MeterRegistry meters;

    private final Clock clock;

    private final int history;

    private final ExecutorService executor;

    /**
     * Creates the service and its sources.
     *
     * @param properties  the harvest settings
     * @param factories   the source factories
     * @param harvester   the workflow
     * @param ingestor    where records go
     * @param checkpoints the checkpoint store
     * @param scheduler   starts scheduled harvests (the service's own: Spring Cloud Consul adds others)
     * @param meters      the metrics registry
     * @param clock       the clock
     * @throws IllegalStateException when a source is misconfigured
     */
    public HarvestService(final HarvestProperties properties, final List<HarvestSourceFactory> factories,
            final Harvester harvester, final RecordIngestor ingestor, final CheckpointStore checkpoints,
            @Qualifier(HarvestConfiguration.SCHEDULER) final TaskScheduler scheduler, final MeterRegistry meters,
            final Clock clock) {
        this.harvester = harvester;
        this.ingestor = ingestor;
        this.checkpoints = checkpoints;
        this.scheduler = scheduler;
        this.meters = meters;
        this.clock = clock;
        this.history = Math.max(1, properties.getHistory());
        final Map<String, HarvestSourceFactory> byType = new HashMap<>();
        for (final HarvestSourceFactory factory : factories) {
            if (byType.put(factory.type(), factory) != null) {
                throw new IllegalStateException("Two harvest source factories for type " + factory.type());
            }
        }
        properties.getSources().forEach((id, source) -> sources.put(id, configure(id, source, byType)));
        this.executor = Executors.newFixedThreadPool(Math.max(1, properties.getConcurrency()), threads());
        LOG.info("Harvest sources: {}", sources.isEmpty() ? "none" : sources.values().stream()
            .map(source -> source.id + " (" + source.source.type() + (source.enabled ? "" : ", disabled") + ")").toList());
    }

    private static Configured configure(final String id, final HarvestProperties.Source source,
            final Map<String, HarvestSourceFactory> factories) {
        try {
            if (!StringUtils.hasText(source.getType())) {
                throw new IllegalArgumentException("type is missing");
            }
            final HarvestSourceFactory factory = factories.get(source.getType());
            if (factory == null) {
                throw new IllegalArgumentException("unknown type " + source.getType() + "; known types are "
                    + factories.keySet().stream().sorted().toList());
            }
            if (!StringUtils.hasText(source.getUrl())) {
                throw new IllegalArgumentException("url is missing");
            }
            final URI url;
            try {
                url = URI.create(source.getUrl().trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("url is not a URL", e);
            }
            if (StringUtils.hasText(source.getSchedule())) {
                CronExpression.parse(source.getSchedule());
            }
            final Duration initialWindow = source.getInitialWindow();
            if (initialWindow != null && (initialWindow.isNegative() || initialWindow.isZero())) {
                throw new IllegalArgumentException("initial-window must be positive");
            }
            final SourceSettings settings = new SourceSettings(id, source.getType(), url, source.getPageSize(),
                new SourceCredentials(source.getUsername(), source.getPassword(), source.getToken()),
                source.getOptions());
            return new Configured(id, settings, factory.create(settings), source.isEnabled(),
                StringUtils.hasText(source.getSchedule()) ? source.getSchedule().trim() : null,
                source.isHarvestOnStartup(), initialWindow);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid harvest source " + id + ": " + e.getMessage(), e);
        }
    }

    /**
     * Registers the schedules of the enabled sources once the application is ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void schedule() {
        for (final Configured source : sources.values()) {
            if (source.enabled && source.schedule != null) {
                scheduler.schedule(() -> scheduled(source.id), new CronTrigger(source.schedule));
                LOG.info("Harvesting {} on schedule {}", source.id, source.schedule);
            }
        }
    }

    /**
     * Starts an incremental harvest of every enabled source marked {@code harvest-on-startup} once
     * the application is ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void harvestOnStartup() {
        for (final Configured source : sources.values()) {
            if (source.enabled && source.harvestOnStartup) {
                LOG.info("Harvesting {} on startup", source.id);
                scheduled(source.id);
            }
        }
    }

    private void scheduled(final String id) {
        try {
            start(id, HarvestMode.INCREMENTAL);
        } catch (HarvestRequestException e) {
            LOG.info("Scheduled harvest of {} skipped: {}", id, e.getMessage());
        }
    }

    /**
     * Returns the state of every source.
     *
     * @return the states, in configuration order
     */
    public List<SourceStatus> statuses() {
        return sources.values().stream().map(Configured::status).toList();
    }

    /**
     * Returns the state of a source.
     *
     * @param id the identifier
     * @return the state, or empty for an unknown source
     */
    public Optional<SourceStatus> status(final String id) {
        return Optional.ofNullable(sources.get(id)).map(Configured::status);
    }

    /**
     * Starts harvesting a source in the background.
     *
     * @param id   the identifier
     * @param mode incremental or full
     * @return the state of the source, with the harvest running (or queued)
     * @throws HarvestRequestException when the source is unknown, disabled or already running
     */
    public SourceStatus start(final String id, final HarvestMode mode) {
        final Configured source = sources.get(id);
        if (source == null) {
            throw new HarvestRequestException(Reason.UNKNOWN_SOURCE, "No harvest source " + id);
        }
        if (!source.enabled) {
            throw new HarvestRequestException(Reason.DISABLED, "Harvest source " + id + " is disabled");
        }
        final Run run;
        synchronized (source) {
            if (source.running != null) {
                throw new HarvestRequestException(Reason.RUNNING, "Harvest source " + id + " is being harvested");
            }
            run = new Run(mode, clock.instant());
            source.running = run;
        }
        try {
            executor.execute(() -> harvest(source, run));
        } catch (RuntimeException e) {
            synchronized (source) {
                source.running = null;
            }
            throw e;
        }
        return source.status();
    }

    /**
     * Asks the running harvest of a source to stop (after the record in progress).
     *
     * @param id the identifier
     * @return the state of the source
     * @throws HarvestRequestException when the source is unknown or not running
     */
    public SourceStatus cancel(final String id) {
        final Configured source = sources.get(id);
        if (source == null) {
            throw new HarvestRequestException(Reason.UNKNOWN_SOURCE, "No harvest source " + id);
        }
        synchronized (source) {
            if (source.running == null) {
                throw new HarvestRequestException(Reason.NOT_RUNNING, "Harvest source " + id + " is not being harvested");
            }
            source.running.cancelled.set(true);
        }
        return source.status();
    }

    private void harvest(final Configured source, final Run run) {
        final String id = source.id;
        HarvestReport report;
        try {
            report = harvester.run(source.source, ingestor, checkpoints(source, run), run.mode, listener(id, run),
                run.cancelled::get);
        } catch (RuntimeException e) {
            // the harvester reports its failures; this only guards the bookkeeping below
            LOG.error("Harvest of {} failed unexpectedly", id, e);
            report = new HarvestReport(id, source.source.type(), run.mode, HarvestOutcome.FAILED, run.startedAt,
                clock.instant(), null, null, 0, run.harvested.get(), 0, 0, 0, 0, run.failed.get(), List.of(),
                "Unexpected failure: " + e);
        }
        Counter.builder("harvest.runs").description("Harvests by source and outcome")
            .tag("source", id).tag("outcome", report.outcome().name()).register(meters).increment();
        Timer.builder("harvest.duration").description("How long harvests take")
            .tag("source", id).tag("outcome", report.outcome().name()).register(meters).record(report.duration());
        synchronized (source) {
            source.reports.addFirst(report);
            while (source.reports.size() > history) {
                source.reports.removeLast();
            }
            source.running = null;
        }
    }

    /**
     * The checkpoint store of one harvest: a source with an initial window and no checkpoint yet
     * starts that far before the harvest instead of at its first record.
     */
    private CheckpointStore checkpoints(final Configured source, final Run run) {
        if (source.initialWindow == null) {
            return checkpoints;
        }
        final Instant initial = run.startedAt.minus(source.initialWindow);
        return new CheckpointStore() {
            @Override
            public Optional<Instant> load(final String sourceId) {
                return checkpoints.load(sourceId).or(() -> Optional.of(initial));
            }

            @Override
            public void save(final String sourceId, final Instant checkpoint) {
                checkpoints.save(sourceId, checkpoint);
            }
        };
    }

    private HarvestListener listener(final String id, final Run run) {
        final Map<String, Counter> counters = new HashMap<>();
        final Function<String, Counter> counter = action -> counters.computeIfAbsent(action, key ->
            Counter.builder("harvest.records").description("Harvested records by source and what ingest did")
                .tag("source", id).tag("action", key).register(meters));
        return new HarvestListener() {
            @Override
            public void pageFetched(final HarvestPage page) {
                run.harvested.addAndGet(page.records().size() + page.failures().size());
            }

            @Override
            public void recordIngested(final HarvestedRecord record, final IngestAction action) {
                counter.apply(action.name()).increment();
            }

            @Override
            public void recordFailed(final RecordFailure failure) {
                run.failed.incrementAndGet();
                counter.apply("FAILED").increment();
            }
        };
    }

    /**
     * Stops running harvests when the application stops (they end as cancelled; their checkpoints
     * do not move).
     *
     * @throws InterruptedException when interrupted while waiting
     */
    @PreDestroy
    public void shutdown() throws InterruptedException {
        sources.values().forEach(source -> {
            synchronized (source) {
                if (source.running != null) {
                    source.running.cancelled.set(true);
                }
            }
        });
        executor.shutdown();
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    }

    private static ThreadFactory threads() {
        final AtomicInteger count = new AtomicInteger();
        return runnable -> {
            final Thread thread = new Thread(runnable, "harvest-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** A harvest in progress. */
    private static final class Run {

        private final HarvestMode mode;

        private final Instant startedAt;

        private final AtomicBoolean cancelled = new AtomicBoolean();

        private final AtomicLong harvested = new AtomicLong();

        private final AtomicLong failed = new AtomicLong();

        Run(final HarvestMode mode, final Instant startedAt) {
            this.mode = mode;
            this.startedAt = startedAt;
        }

    }

    /** A configured source and its state. */
    private static final class Configured {

        private final String id;

        private final SourceSettings settings;

        private final HarvestSource source;

        private final boolean enabled;

        private final String schedule;

        private final boolean harvestOnStartup;

        private final Duration initialWindow;

        private final Deque<HarvestReport> reports = new ArrayDeque<>();

        private Run running;

        Configured(final String id, final SourceSettings settings, final HarvestSource source, final boolean enabled,
                final String schedule, final boolean harvestOnStartup, final Duration initialWindow) {
            this.id = id;
            this.settings = settings;
            this.source = source;
            this.enabled = enabled;
            this.schedule = schedule;
            this.harvestOnStartup = harvestOnStartup;
            this.initialWindow = initialWindow;
        }

        synchronized SourceStatus status() {
            final SourceStatus.Running progress = running == null ? null : new SourceStatus.Running(running.mode,
                running.startedAt, running.harvested.get(), running.failed.get(), running.cancelled.get());
            final Collection<HarvestReport> latest = new ArrayList<>(reports);
            return new SourceStatus(id, source.type(), settings.url().toString(), enabled, schedule,
                source.capabilities(), progress, List.copyOf(latest));
        }

    }

}
