package com.articulorium.discovery.web;

import java.util.Locale;

import com.articulorium.discovery.index.IndexUnavailableException;
import com.articulorium.discovery.search.NotFoundException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Renders refusals of the discovery API as RFC 9457 problem documents. Details are diagnostics in
 * English ({@code Content-Language: en}); the user interface shows its own translated text for the
 * status. Nothing of the search index's or the platform's own answers is passed on.
 */
@RestControllerAdvice(assignableTypes = DiscoveryApiController.class)
public class DiscoveryExceptionHandler {

    /** Language of the details. */
    static final String DETAIL_LANGUAGE = Locale.ENGLISH.toLanguageTag();

    /**
     * Resources without a page for the visitor (missing, outside the discovery layer, or private).
     *
     * @param e the exception
     * @return 404
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(final NotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * Requests out of bounds: malformed paths, unknown sort orders or kinds, too long searches.
     *
     * @param e the exception
     * @return 400
     */
    @ExceptionHandler({IllegalArgumentException.class, MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ProblemDetail> invalid(final Exception e) {
        return respond(HttpStatus.BAD_REQUEST, e instanceof MethodArgumentTypeMismatchException mismatch
            ? "Invalid value of " + mismatch.getName() : e.getMessage());
    }

    /**
     * The search index is unavailable.
     *
     * @param e the exception
     * @return 503
     */
    @ExceptionHandler(IndexUnavailableException.class)
    public ResponseEntity<ProblemDetail> unavailable(final IndexUnavailableException e) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    private static ResponseEntity<ProblemDetail> respond(final HttpStatus status, final String detail) {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        return ResponseEntity.status(status)
            .header(HttpHeaders.CONTENT_LANGUAGE, DETAIL_LANGUAGE)
            .body(problem);
    }

}
