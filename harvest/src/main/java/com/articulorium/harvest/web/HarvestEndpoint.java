package com.articulorium.harvest.web;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.articulorium.harvest.core.HarvestMode;
import com.articulorium.harvest.service.HarvestRequestException;
import com.articulorium.harvest.service.HarvestService;
import com.articulorium.harvest.service.SourceStatus;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.stereotype.Component;

/**
 * The {@code harvest} actuator endpoint: lists the sources with their latest reports, starts and
 * cancels harvests. It is served on the management port, which the gateway does not expose:
 * starting harvests is an operator's task.
 *
 * <ul>
 * <li>{@code GET /actuator/harvest}: every source</li>
 * <li>{@code GET /actuator/harvest/{source}}: one source</li>
 * <li>{@code POST /actuator/harvest/{source}} with {@code {"mode": "full"}} or no body
 * (incremental): starts a harvest (202)</li>
 * <li>{@code DELETE /actuator/harvest/{source}}: stops the running harvest (202)</li>
 * </ul>
 */
@Component
@Endpoint(id = "harvest")
public class HarvestEndpoint {

    private final HarvestService harvests;

    /**
     * Creates the endpoint.
     *
     * @param harvests the harvest service
     */
    public HarvestEndpoint(final HarvestService harvests) {
        this.harvests = harvests;
    }

    /**
     * Lists the sources.
     *
     * @return the sources
     */
    @ReadOperation
    public Map<String, List<SourceStatus>> sources() {
        return Map.of("sources", harvests.statuses());
    }

    /**
     * Shows one source.
     *
     * @param source the source identifier
     * @return the source, or 404
     */
    @ReadOperation
    public WebEndpointResponse<Object> source(@Selector final String source) {
        return harvests.status(source).<WebEndpointResponse<Object>>map(WebEndpointResponse::new)
            .orElseGet(() -> error(WebEndpointResponse.STATUS_NOT_FOUND, "No harvest source " + source));
    }

    /**
     * Starts a harvest.
     *
     * @param source the source identifier
     * @param mode   {@code incremental} (default) or {@code full}
     * @return 202 with the source, or 400, 404 or 409
     */
    @WriteOperation
    public WebEndpointResponse<Object> start(@Selector final String source, @Nullable final String mode) {
        final HarvestMode parsed;
        try {
            parsed = mode == null || mode.isBlank() ? HarvestMode.INCREMENTAL
                : HarvestMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return error(WebEndpointResponse.STATUS_BAD_REQUEST, "mode must be incremental or full");
        }
        try {
            return new WebEndpointResponse<>(harvests.start(source, parsed), 202);
        } catch (HarvestRequestException e) {
            return refused(e);
        }
    }

    /**
     * Stops a running harvest.
     *
     * @param source the source identifier
     * @return 202 with the source, or 404 or 409
     */
    @DeleteOperation
    public WebEndpointResponse<Object> cancel(@Selector final String source) {
        try {
            return new WebEndpointResponse<>(harvests.cancel(source), 202);
        } catch (HarvestRequestException e) {
            return refused(e);
        }
    }

    private static WebEndpointResponse<Object> refused(final HarvestRequestException e) {
        final int status = switch (e.getReason()) {
            case UNKNOWN_SOURCE -> WebEndpointResponse.STATUS_NOT_FOUND;
            case INVALID -> WebEndpointResponse.STATUS_BAD_REQUEST;
            case DISABLED, RUNNING, NOT_RUNNING -> 409;
        };
        return error(status, e.getMessage());
    }

    private static WebEndpointResponse<Object> error(final int status, final String message) {
        return new WebEndpointResponse<>(Map.of("error", message), status);
    }

}
