package com.articulorium.harvest.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The harvest workflow, common to every source: pages through a {@link HarvestSource}, hands every
 * record to a {@link RecordIngestor} and moves the source's checkpoint.
 *
 * <ul>
 * <li><b>Incremental harvests</b> ask the source for the changes since its checkpoint when the
 * source {@link SourceCapabilities#incremental() can list changes}; otherwise every harvest reads
 * every record.</li>
 * <li><b>Recoverable failures</b> (network errors, 5xx, 429) of a page fetch or a record ingest are
 * retried with exponential backoff, honouring {@code Retry-After}. When the attempts run out the
 * harvest fails: the source or the target is unavailable.</li>
 * <li><b>Record failures</b> (a record the source cannot map, a copy the target refuses) are
 * counted and listed in the report; the harvest goes on, up to
 * {@link HarvestPolicy#maxFailures()}.</li>
 * <li><b>No record is lost silently</b>: the checkpoint moves only after a harvest that read the
 * source to the end without a single failure. After any failure the next incremental harvest starts
 * from the same checkpoint, so failed records are tried again (ingest is idempotent).</li>
 * <li>The new checkpoint is the source's own time when it answered the first page (when its
 * protocol says, as OAI-PMH does) or the harvest's start, minus {@link HarvestPolicy#overlap()}, so
 * records changed while harvesting are read again next time.</li>
 * </ul>
 *
 * <p>The harvester holds no state between runs and may run several sources concurrently; one source
 * must not be harvested twice at the same time (the caller ensures that).</p>
 */
public class Harvester {

    private static final Logger LOG = LoggerFactory.getLogger(Harvester.class);

    private final HarvestPolicy policy;

    private final Clock clock;

    private final Sleeper sleeper;

    /**
     * Creates a harvester.
     *
     * @param policy  the failure and checkpoint policy
     * @param clock   the clock (harvest start and end times)
     * @param sleeper waits between retries
     */
    public Harvester(final HarvestPolicy policy, final Clock clock, final Sleeper sleeper) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /**
     * Creates a harvester with the system clock that sleeps between retries.
     *
     * @param policy the policy
     */
    public Harvester(final HarvestPolicy policy) {
        this(policy, Clock.systemUTC(), Sleeper.THREAD);
    }

    /**
     * Harvests a source.
     *
     * @param source      the source
     * @param ingestor    where the records go
     * @param checkpoints the checkpoint store
     * @param mode        incremental or full
     * @return the report
     */
    public HarvestReport run(final HarvestSource source, final RecordIngestor ingestor,
            final CheckpointStore checkpoints, final HarvestMode mode) {
        return run(source, ingestor, checkpoints, mode, HarvestListener.NONE, () -> false);
    }

    /**
     * Harvests a source. Never throws: every failure ends up in the report.
     *
     * @param source      the source
     * @param ingestor    where the records go
     * @param checkpoints the checkpoint store
     * @param mode        incremental or full
     * @param listener    observes the progress
     * @param cancelled   tells whether the harvest should stop (checked between records)
     * @return the report
     */
    public HarvestReport run(final HarvestSource source, final RecordIngestor ingestor,
            final CheckpointStore checkpoints, final HarvestMode mode, final HarvestListener listener,
            final BooleanSupplier cancelled) {
        final Run run = new Run(source, mode, clock.instant(), listener);
        try {
            run.window = retrying("read the checkpoint of " + source.id(), () -> window(source, checkpoints, mode));
            retrying("prepare the ingest of " + source.id(), () -> {
                ingestor.begin(source.id());
                return Boolean.TRUE;
            });
            String cursor = null;
            do {
                if (cancelled.getAsBoolean()) {
                    return run.end(HarvestOutcome.CANCELLED, "Cancelled", null);
                }
                final String requested = cursor;
                final HarvestPage page = retrying("fetch a page from " + source.id(),
                    () -> source.fetch(run.window, requested));
                run.page(page);
                for (final HarvestedRecord record : page.records()) {
                    if (cancelled.getAsBoolean()) {
                        return run.end(HarvestOutcome.CANCELLED, "Cancelled", null);
                    }
                    ingest(run, ingestor, record);
                    if (run.tooManyFailures()) {
                        return run.end(HarvestOutcome.FAILED, "Stopped after " + run.failed + " failed records", null);
                    }
                }
                if (page.hasNext() && page.nextCursor().equals(cursor)) {
                    throw HarvestException.fatal("The source " + source.id() + " answered the same cursor twice", null);
                }
                cursor = page.nextCursor();
            } while (cursor != null);
            if (run.failed > 0) {
                return run.end(HarvestOutcome.COMPLETED_WITH_ERRORS, null, null);
            }
            return run.end(HarvestOutcome.COMPLETED, null, checkpoint(source, checkpoints, run));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return run.end(HarvestOutcome.CANCELLED, "Interrupted", null);
        } catch (HarvestException e) {
            LOG.warn("Harvest of {} failed: {}", source.id(), e.getMessage());
            return run.end(HarvestOutcome.FAILED, e.getMessage(), null);
        } catch (RuntimeException e) {
            LOG.error("Harvest of {} failed unexpectedly", source.id(), e);
            return run.end(HarvestOutcome.FAILED, "Unexpected failure: " + e, null);
        }
    }

    private static HarvestWindow window(final HarvestSource source, final CheckpointStore checkpoints,
            final HarvestMode mode) {
        if (mode == HarvestMode.FULL || !source.capabilities().incremental()) {
            return HarvestWindow.ALL;
        }
        return checkpoints.load(source.id()).map(HarvestWindow::new).orElse(HarvestWindow.ALL);
    }

    private void ingest(final Run run, final RecordIngestor ingestor, final HarvestedRecord record)
            throws InterruptedException {
        final IngestAction action;
        try {
            action = retrying("ingest " + record.identifier(), () -> ingestor.ingest(run.source.id(), record));
        } catch (HarvestException e) {
            if (e.isRecoverable()) {
                // the target is unavailable: every further record would fail the same way
                throw e;
            }
            run.fail(new RecordFailure(record.identifier(), e.getMessage()));
            return;
        } catch (RuntimeException e) {
            // a defect triggered by one record must not cost the others (maxFailures still applies)
            LOG.error("Ingest of {} from {} failed unexpectedly", record.identifier(), run.source.id(), e);
            run.fail(new RecordFailure(record.identifier(), "Unexpected failure: " + e));
            return;
        }
        run.ingested(record, action);
    }

    private Instant checkpoint(final HarvestSource source, final CheckpointStore checkpoints, final Run run)
            throws InterruptedException {
        if (!source.capabilities().incremental()) {
            return null;
        }
        final Instant base = run.sourceTime != null ? run.sourceTime : run.startedAt;
        final Instant checkpoint = base.minus(policy.overlap());
        retrying("save the checkpoint of " + source.id(), () -> {
            checkpoints.save(source.id(), checkpoint);
            return checkpoint;
        });
        return checkpoint;
    }

    private <T> T retrying(final String what, final Supplier<T> step) throws InterruptedException {
        for (int attempt = 1;; attempt++) {
            try {
                return step.get();
            } catch (HarvestException e) {
                if (!e.isRecoverable() || attempt >= policy.maxAttempts()) {
                    throw e;
                }
                final Duration delay = policy.delay(attempt, e.getRetryAfter());
                LOG.info("Could not {} (attempt {} of {}): {}; retrying in {}", what, attempt, policy.maxAttempts(),
                    e.getMessage(), delay);
                sleeper.sleep(delay);
            }
        }
    }

    /**
     * Waits between retries.
     */
    @FunctionalInterface
    public interface Sleeper {

        /** Sleeps on the current thread. */
        Sleeper THREAD = duration -> Thread.sleep(duration.toMillis());

        /**
         * Waits.
         *
         * @param duration how long
         * @throws InterruptedException when interrupted (the harvest is then cancelled)
         */
        void sleep(Duration duration) throws InterruptedException;

    }

    /** The tally of one harvest. */
    private final class Run {

        private final HarvestSource source;

        private final HarvestMode mode;

        private final Instant startedAt;

        private final HarvestListener listener;

        private final List<RecordFailure> failures = new ArrayList<>();

        private HarvestWindow window = HarvestWindow.ALL;

        private Instant sourceTime;

        private int pages;

        private long harvested;

        private long created;

        private long updated;

        private long deleted;

        private long skipped;

        private long failed;

        Run(final HarvestSource source, final HarvestMode mode, final Instant startedAt,
                final HarvestListener listener) {
            this.source = source;
            this.mode = mode;
            this.startedAt = startedAt;
            this.listener = listener;
        }

        void page(final HarvestPage page) {
            if (pages == 0) {
                sourceTime = page.responseTime();
            }
            pages++;
            harvested += page.records().size() + page.failures().size();
            listener.pageFetched(page);
            page.failures().forEach(this::fail);
        }

        void ingested(final HarvestedRecord record, final IngestAction action) {
            switch (action) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                case DELETED -> deleted++;
                case SKIPPED -> skipped++;
                default -> throw new IllegalStateException("Unknown action " + action);
            }
            listener.recordIngested(record, action);
        }

        void fail(final RecordFailure failure) {
            failed++;
            if (failures.size() < policy.maxReportedFailures()) {
                failures.add(failure);
            }
            LOG.warn("Harvest of {}: record {} failed: {}", source.id(), failure.identifier(), failure.reason());
            listener.recordFailed(failure);
        }

        boolean tooManyFailures() {
            return policy.maxFailures() > 0 && failed >= policy.maxFailures();
        }

        HarvestReport end(final HarvestOutcome outcome, final String message, final Instant checkpoint) {
            final HarvestReport report = new HarvestReport(source.id(), source.type(), mode, outcome, startedAt,
                clock.instant(), window.from(), checkpoint, pages, harvested, created, updated, deleted, skipped,
                failed, failures, message);
            LOG.info("Harvest of {} ({} {}): {} after {} pages; {} records, {} created, {} updated, {} deleted, "
                + "{} skipped, {} failed{}", source.id(), source.type(), mode, outcome, pages, harvested, created,
                updated, deleted, skipped, failed, message == null ? "" : ": " + message);
            return report;
        }

    }

}
