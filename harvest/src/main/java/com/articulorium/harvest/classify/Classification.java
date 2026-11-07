package com.articulorium.harvest.classify;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The classification of a harvested record, as stored below its copy ({@code <record>/classification})
 * and shown in the review queue.
 *
 * @param source       the harvest source
 * @param record       the path of the record's copy where it was ingested
 * @param identifier   the record's identifier in its source
 * @param title        the record's title
 * @param status       where the classification stands
 * @param suggestion   the agent's suggestion ({@code null} when classification failed)
 * @param failure      why classification failed (a reason code), {@code null} otherwise
 * @param uncertain    whether the agent's confidence is below the review threshold
 * @param classifiedAt when the record was classified (or classification failed)
 * @param placedAt     the path of the record's item once placed in a collection
 * @param decidedBy    who accepted or rejected the suggestion ({@code auto} when accepted automatically)
 * @param decidedAt    when
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Classification(String source, String record, String identifier, String title, Status status,
        Suggestion suggestion, String failure, boolean uncertain, Instant classifiedAt, String placedAt,
        String decidedBy, Instant decidedAt) {

    /** Where a classification stands. */
    public enum Status {

        /** Waits for a person. */
        PENDING,

        /** Being accepted: the item exists, the record is being moved there. */
        PLACING,

        /** Accepted: the record lives in its item. */
        ACCEPTED,

        /** Rejected: the record stays where it was harvested. */
        REJECTED,

        /** Classification failed (the agent was unavailable or failed); a person may retry it. */
        FAILED;

        /**
         * Tells whether a person still has to act.
         *
         * @return {@code true} for pending and failed classifications
         */
        public boolean open() {
            return this == PENDING || this == FAILED || this == PLACING;
        }

    }

}
