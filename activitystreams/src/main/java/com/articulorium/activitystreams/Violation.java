package com.articulorium.activitystreams;

import java.util.Objects;

/**
 * A problem found in an Activity Streams document.
 *
 * @param path    where: a property path such as {@code object.published} or {@code to[1]}; empty
 *                for the document itself
 * @param message what is wrong, for example {@code must be an absolute IRI}
 */
public record Violation(String path, String message) {

    /**
     * Creates a violation.
     *
     * @param path    where
     * @param message what is wrong
     */
    public Violation {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(message, "message");
    }

    /**
     * Describes the violation in one line.
     *
     * @return for example {@code object.published must be an RFC 3339 date-time}
     */
    @Override
    public String toString() {
        return path.isEmpty() ? message : path + " " + message;
    }

}
