package com.articulorium.activitystreams;

import java.util.List;
import java.util.stream.Collectors;

/**
 * An Activity Streams document cannot be read: it is not JSON, not a JSON object, or (when
 * validation was asked for) not valid Activity Streams.
 */
public class ActivityStreamsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<Violation> violations;

    /**
     * Creates the exception for a document that cannot be parsed.
     *
     * @param message what went wrong
     * @param cause   the parser's exception, or {@code null}
     */
    public ActivityStreamsException(final String message, final Throwable cause) {
        super(message, cause);
        this.violations = List.of(new Violation("", message));
    }

    /**
     * Creates the exception for a document that is not valid.
     *
     * @param violations the problems found; not empty
     */
    public ActivityStreamsException(final List<Violation> violations) {
        super("Not a valid Activity Streams document: " + violations.stream()
            .map(Violation::toString).collect(Collectors.joining("; ")));
        this.violations = List.copyOf(violations);
    }

    /**
     * Returns the problems found.
     *
     * @return the violations, at least one
     */
    public List<Violation> getViolations() {
        return violations;
    }

}
