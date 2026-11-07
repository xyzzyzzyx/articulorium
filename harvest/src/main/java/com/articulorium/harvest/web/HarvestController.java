package com.articulorium.harvest.web;

import java.util.List;
import java.util.Locale;

import com.articulorium.harvest.core.HarvestMode;
import com.articulorium.harvest.service.HarvestRequestException;
import com.articulorium.harvest.service.HarvestRequestException.Reason;
import com.articulorium.harvest.service.HarvestService;
import com.articulorium.harvest.service.SourceStatus;
import com.articulorium.harvest.web.HarvestAccess.Caller;
import com.articulorium.harvest.web.HarvestAccess.Operation;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The harvest API used by the user interface, served on the service port and routed by the gateway
 * under {@code /harvest}: the same operations as the {@code harvest} actuator endpoint, with Web
 * Access Control decided by the platform ({@link HarvestAccess}).
 *
 * <ul>
 * <li>{@code GET /sources}: every source, and whether the caller may start and cancel harvests</li>
 * <li>{@code GET /sources/{id}}: one source</li>
 * <li>{@code POST /sources/{id}/harvests} with {@code {"mode": "full"}} or {@code {"mode":
 * "incremental"}} (the default): starts a harvest (202)</li>
 * <li>{@code DELETE /sources/{id}/harvests/running}: asks the running harvest to stop (202)</li>
 * </ul>
 *
 * <p>Sources are shown without credentials. Refusals are RFC 9457 problem documents
 * ({@link HarvestApiExceptionHandler}). Turn the API off with
 * {@code articulorium.harvest.api.enabled=false}; the actuator endpoint stays.</p>
 */
@RestController
@RequestMapping(path = "/sources", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnBooleanProperty(name = "articulorium.harvest.api.enabled", matchIfMissing = true)
public class HarvestController {

    private static final Logger LOG = LoggerFactory.getLogger(HarvestController.class);

    private final HarvestService harvests;

    private final HarvestAccess access;

    /**
     * Creates the controller.
     *
     * @param harvests the harvest service
     * @param access   decides who may do what
     */
    public HarvestController(final HarvestService harvests, final HarvestAccess access) {
        this.harvests = harvests;
        this.access = access;
    }

    /**
     * Every source, in configuration order.
     *
     * @param sources the sources with their harvests in progress and latest reports
     * @param operate whether the caller may start and cancel harvests
     */
    public record SourceList(List<SourceStatus> sources, boolean operate) {
    }

    /**
     * A request to start a harvest.
     *
     * @param mode {@code incremental} (default) or {@code full}
     */
    public record HarvestRequest(@Nullable String mode) {
    }

    /**
     * Lists the sources.
     *
     * @param authorization the caller's credentials
     * @return the sources
     */
    @GetMapping
    public SourceList list(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
            @Nullable final String authorization) {
        final Caller caller = access.require(authorization, Operation.VIEW);
        return new SourceList(harvests.statuses(), caller.may(Operation.OPERATE));
    }

    /**
     * Shows one source.
     *
     * @param id            the source
     * @param authorization the caller's credentials
     * @return the source
     */
    @GetMapping("/{id}")
    public SourceStatus get(@PathVariable final String id,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) @Nullable final String authorization) {
        access.require(authorization, Operation.VIEW);
        return harvests.status(id).orElseThrow(() -> unknown(id));
    }

    /**
     * Starts a harvest.
     *
     * @param id            the source
     * @param request       the mode; incremental without a body
     * @param authorization the caller's credentials
     * @return 202 with the source
     */
    @PostMapping(path = "/{id}/harvests", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SourceStatus> start(@PathVariable final String id,
            @RequestBody(required = false) @Nullable final HarvestRequest request,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) @Nullable final String authorization) {
        final Caller caller = access.require(authorization, Operation.OPERATE);
        final HarvestMode mode = mode(request == null ? null : request.mode());
        final SourceStatus status = harvests.start(id, mode);
        LOG.info("{} harvest of {} started by {}", mode, id, caller.name());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(status);
    }

    /**
     * Asks the running harvest of a source to stop after the record in progress.
     *
     * @param id            the source
     * @param authorization the caller's credentials
     * @return 202 with the source
     */
    @DeleteMapping("/{id}/harvests/running")
    public ResponseEntity<SourceStatus> cancel(@PathVariable final String id,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) @Nullable final String authorization) {
        final Caller caller = access.require(authorization, Operation.OPERATE);
        final SourceStatus status = harvests.cancel(id);
        LOG.info("Harvest of {} cancelled by {}", id, caller.name());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(status);
    }

    /**
     * Parses a harvest mode.
     *
     * @param mode {@code incremental}, {@code full}, or {@code null} / blank for incremental
     * @return the mode
     * @throws HarvestRequestException for anything else
     */
    static HarvestMode mode(@Nullable final String mode) {
        if (mode == null || mode.isBlank()) {
            return HarvestMode.INCREMENTAL;
        }
        try {
            return HarvestMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new HarvestRequestException(Reason.INVALID, "mode must be incremental or full");
        }
    }

    private static HarvestRequestException unknown(final String id) {
        return new HarvestRequestException(Reason.UNKNOWN_SOURCE, "No harvest source " + id);
    }

}
