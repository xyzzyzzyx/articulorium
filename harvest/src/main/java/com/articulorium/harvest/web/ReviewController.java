package com.articulorium.harvest.web;

import com.articulorium.harvest.classify.Classification;
import com.articulorium.harvest.classify.ReviewService;
import com.articulorium.harvest.web.HarvestAccess.Caller;
import com.articulorium.harvest.web.HarvestAccess.Operation;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The review queue of the user interface: the AI agent's classifications of harvested records,
 * to accept (the record is placed in a collection), reject (it stays where it was harvested) or
 * classify again. Served with the harvest API when
 * {@code articulorium.harvest.classification.enabled=true}.
 *
 * <p>Viewing needs {@code read} and deciding {@code write} access to the harvested records, as for
 * the harvest API. Placing a record also writes to its collection with the caller's own
 * credentials, so the platform decides whether the caller may add items there.</p>
 */
@RestController
@RequestMapping(path = "/classifications", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnBooleanProperty(name = "articulorium.harvest.classification.enabled")
public class ReviewController {

    private final ReviewService reviews;

    private final HarvestAccess access;

    /**
     * Creates the controller.
     *
     * @param reviews the review queue
     * @param access  decides who may do what
     */
    public ReviewController(final ReviewService reviews, final HarvestAccess access) {
        this.reviews = reviews;
        this.access = access;
    }

    /**
     * A page of the queue, with whether the caller may decide.
     *
     * @param classifications the classifications
     * @param total           how many match
     * @param offset          how many were skipped
     * @param decide          whether the caller may accept, reject and retry
     */
    public record Queue(java.util.List<Classification> classifications, long total, int offset, boolean decide) {
    }

    /**
     * What to accept.
     *
     * @param collection the collection (path), {@code null} for the suggested one
     * @param topology   the topology id, {@code none} for a plain object, {@code null} for the suggested one
     */
    public record Acceptance(@Nullable String collection, @Nullable String topology) {
    }

    /**
     * Lists classifications, the most recently changed first.
     *
     * @param source        only this source's
     * @param status        {@code open} (waiting for a person, the default) or {@code all}
     * @param offset        skipped
     * @param limit         wanted (at most 100)
     * @param authorization the caller's credentials
     * @return the page
     */
    @GetMapping
    public Queue list(@RequestParam(required = false) @Nullable final String source,
            @RequestParam(defaultValue = "open") final String status,
            @RequestParam(defaultValue = "0") final int offset, @RequestParam(defaultValue = "25") final int limit,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) @Nullable final String authorization) {
        final Caller caller = access.require(authorization, Operation.VIEW);
        final ReviewService.Page page = reviews.list(authorization, source, !"all".equals(status), offset, limit);
        return new Queue(page.classifications(), page.total(), page.offset(), caller.may(Operation.OPERATE));
    }

    /**
     * Shows a record's classification.
     *
     * @param source        the source
     * @param record        the record's name
     * @param authorization the caller's credentials
     * @return the classification
     */
    @GetMapping("/{source}/{record}")
    public Classification get(@PathVariable final String source, @PathVariable final String record,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) @Nullable final String authorization) {
        access.require(authorization, Operation.VIEW);
        return reviews.get(authorization, source, record);
    }

    /**
     * Accepts a classification: places the record in a collection.
     *
     * @param source        the source
     * @param record        the record's name
     * @param acceptance    the collection and topology, when not the suggested ones
     * @param authorization the caller's credentials
     * @return the accepted classification
     */
    @PostMapping(path = "/{source}/{record}/acceptance", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Classification accept(@PathVariable final String source, @PathVariable final String record,
            @RequestBody(required = false) @Nullable final Acceptance acceptance,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) @Nullable final String authorization) {
        final Caller caller = access.require(authorization, Operation.OPERATE);
        return reviews.accept(authorization, caller.name(), source, record,
            acceptance == null ? null : acceptance.collection(), acceptance == null ? null : acceptance.topology());
    }

    /**
     * Rejects a classification: the record stays where it was harvested.
     *
     * @param source        the source
     * @param record        the record's name
     * @param authorization the caller's credentials
     * @return the rejected classification
     */
    @PostMapping(path = "/{source}/{record}/rejection")
    public Classification reject(@PathVariable final String source, @PathVariable final String record,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) @Nullable final String authorization) {
        final Caller caller = access.require(authorization, Operation.OPERATE);
        return reviews.reject(authorization, caller.name(), source, record);
    }

    /**
     * Classifies a record again.
     *
     * @param source        the source
     * @param record        the record's name
     * @param authorization the caller's credentials
     * @return the new classification
     */
    @PostMapping(path = "/{source}/{record}/retry")
    public Classification retry(@PathVariable final String source, @PathVariable final String record,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) @Nullable final String authorization) {
        access.require(authorization, Operation.OPERATE);
        return reviews.retry(authorization, source, record);
    }

}
