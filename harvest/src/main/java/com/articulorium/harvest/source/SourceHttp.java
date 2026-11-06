package com.articulorium.harvest.source;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.function.Function;

import com.articulorium.harvest.core.HarvestException;
import com.articulorium.harvest.core.SourceSettings;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The HTTP client of one source: sends the source's credentials and turns HTTP failures into
 * {@link HarvestException}s, recoverable (network errors, timeouts, 408, 429, 5xx, with
 * {@code Retry-After}) or not (other 4xx). Messages name the source and the step, never the
 * credentials.
 */
public final class SourceHttp {

    /** Longest response body excerpt quoted in a failure message. */
    private static final int EXCERPT = 300;

    private final RestClient client;

    private final String sourceId;

    private SourceHttp(final RestClient client, final String sourceId) {
        this.client = client;
        this.sourceId = sourceId;
    }

    /**
     * Creates the client of a source.
     *
     * @param builder       the shared builder (cloned)
     * @param settings      the source settings
     * @param authorization whether to send the credentials as an {@code Authorization} header
     * @return the client
     */
    public static SourceHttp of(final RestClient.Builder builder, final SourceSettings settings,
            final boolean authorization) {
        final RestClient.Builder own = builder.clone();
        final String header = settings.credentials().authorization();
        if (authorization && header != null) {
            own.defaultHeader(HttpHeaders.AUTHORIZATION, header);
        }
        return new SourceHttp(own.build(), settings.id());
    }

    /**
     * Returns the underlying client.
     *
     * @return the client
     */
    public RestClient client() {
        return client;
    }

    /**
     * Sends a request.
     *
     * @param <T>     the result type
     * @param what    the step, for messages ("list records")
     * @param request sends the request with the client
     * @return the result
     * @throws HarvestException when the request fails
     */
    public <T> T call(final String what, final Function<RestClient, T> request) {
        try {
            return request.apply(client);
        } catch (RestClientResponseException e) {
            throw failure(what, e);
        } catch (ResourceAccessException e) {
            throw HarvestException.recoverable("Source " + sourceId + " is not reachable to " + what + ": "
                + describe(e.getMostSpecificCause()), e);
        } catch (RestClientException e) {
            throw HarvestException.fatal("Source " + sourceId + " answered an unreadable response to " + what + ": "
                + e.getMessage(), e);
        }
    }

    /**
     * Fetches a URL.
     *
     * @param what   the step
     * @param uri    the URL
     * @param accept the {@code Accept} header
     * @return the body (empty when there is none)
     */
    public byte[] get(final String what, final URI uri, final String accept) {
        final byte[] body = call(what, http -> http.get().uri(uri).header(HttpHeaders.ACCEPT, accept)
            .retrieve().body(byte[].class));
        return body == null ? new byte[0] : body;
    }

    /**
     * Describes a failure for operators: its message, or its type when it has none (a refused
     * connection).
     *
     * @param failure the failure
     * @return the description
     */
    public static String describe(final Throwable failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
            ? failure.getClass().getSimpleName()
            : failure.getMessage();
    }

    private HarvestException failure(final String what, final RestClientResponseException e) {
        final int status = e.getStatusCode().value();
        final String message = "Source " + sourceId + " answered " + status + " to " + what + excerpt(e);
        if (status == 408 || status == 429 || status >= 500) {
            return new HarvestException(message, true, retryAfter(e.getResponseHeaders(), Clock.systemUTC()), e);
        }
        return HarvestException.fatal(message, e);
    }

    private static String excerpt(final RestClientResponseException e) {
        final String body = e.getResponseBodyAsString(StandardCharsets.UTF_8).strip().replaceAll("\\s+", " ");
        if (body.isEmpty()) {
            return "";
        }
        return ": " + (body.length() > EXCERPT ? body.substring(0, EXCERPT) + "…" : body);
    }

    /**
     * Reads a {@code Retry-After} header: a number of seconds or an HTTP date.
     *
     * @param headers the response headers, may be {@code null}
     * @param clock   the clock (for dates)
     * @return the delay, or {@code null} when absent or unreadable
     */
    static Duration retryAfter(final HttpHeaders headers, final Clock clock) {
        final String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim())));
        } catch (NumberFormatException e) {
            try {
                final Duration until = Duration.between(clock.instant(),
                    ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                return until.isNegative() ? Duration.ZERO : until;
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

}
