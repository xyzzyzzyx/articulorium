package com.articulorium.harvest.service;

import java.time.Instant;
import java.util.List;

import com.articulorium.harvest.core.HarvestMode;
import com.articulorium.harvest.core.HarvestReport;
import com.articulorium.harvest.core.SourceCapabilities;

/**
 * The state of a configured source, as the actuator endpoint shows it.
 *
 * @param id           the identifier
 * @param type         the type
 * @param url          the endpoint (never with credentials)
 * @param enabled      whether it may be harvested
 * @param schedule     the cron expression of scheduled harvests, or {@code null}
 * @param capabilities what its protocol supports
 * @param running      the harvest in progress, or {@code null}
 * @param reports      the most recent reports, the latest first
 */
public record SourceStatus(String id, String type, String url, boolean enabled, String schedule,
        SourceCapabilities capabilities, Running running, List<HarvestReport> reports) {

    /**
     * A harvest in progress.
     *
     * @param mode      incremental or full
     * @param startedAt when it started
     * @param harvested records listed so far
     * @param failed    records failed so far
     * @param cancelled whether it was asked to stop
     */
    public record Running(HarvestMode mode, Instant startedAt, long harvested, long failed, boolean cancelled) {

    }

}
