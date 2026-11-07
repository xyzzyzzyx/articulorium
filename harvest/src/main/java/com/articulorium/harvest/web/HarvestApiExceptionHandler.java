package com.articulorium.harvest.web;

import java.util.Locale;

import com.articulorium.harvest.classify.ReviewException;
import com.articulorium.harvest.service.HarvestRequestException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders refusals of the harvest API as RFC 9457 problem documents. Details are diagnostics in
 * English ({@code Content-Language: en}); the user interface shows its own text for the status
 * next to them.
 */
@RestControllerAdvice(assignableTypes = {HarvestController.class, ReviewController.class})
public class HarvestApiExceptionHandler {

    /** Language of the details. */
    static final String DETAIL_LANGUAGE = Locale.ENGLISH.toLanguageTag();

    /**
     * Refusals of access control: 401 (with a bearer challenge), 403, or 503 when the platform
     * could not decide.
     *
     * @param e the exception
     * @return the problem
     */
    @ExceptionHandler(HarvestAccessException.class)
    public ResponseEntity<ProblemDetail> access(final HarvestAccessException e) {
        final HttpStatus status = switch (e.getReason()) {
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        final ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
            .header(HttpHeaders.CONTENT_LANGUAGE, DETAIL_LANGUAGE);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response.body(problem(status, e.getMessage()));
    }

    /**
     * Requests the harvest service refuses: unknown sources (404), invalid requests (400), disabled
     * sources and sources already or not running (409).
     *
     * @param e the exception
     * @return the problem
     */
    @ExceptionHandler(HarvestRequestException.class)
    public ResponseEntity<ProblemDetail> refused(final HarvestRequestException e) {
        final HttpStatus status = switch (e.getReason()) {
            case UNKNOWN_SOURCE -> HttpStatus.NOT_FOUND;
            case INVALID -> HttpStatus.BAD_REQUEST;
            case DISABLED, RUNNING, NOT_RUNNING -> HttpStatus.CONFLICT;
        };
        final ProblemDetail problem = problem(status, e.getMessage());
        problem.setProperty("reason", e.getReason().name());
        return respond(status, problem);
    }

    /**
     * Refusals of the review queue, with their status and reason (unknown source or record 404, a
     * decided classification or a missing collection or topology 409, a collection or topology that
     * cannot be used 422, the platform's own refusals with their status).
     *
     * @param e the exception
     * @return the problem
     */
    @ExceptionHandler(ReviewException.class)
    public ResponseEntity<ProblemDetail> review(final ReviewException e) {
        final HttpStatus resolved = HttpStatus.resolve(e.getStatus());
        final HttpStatus status = resolved == null ? HttpStatus.BAD_GATEWAY : resolved;
        final ProblemDetail problem = problem(status, e.getMessage());
        problem.setProperty("reason", e.getReason());
        final ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
            .header(HttpHeaders.CONTENT_LANGUAGE, DETAIL_LANGUAGE);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response.body(problem);
    }

    /**
     * Unreadable request bodies: 400.
     *
     * @param e the exception
     * @return the problem
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> unreadable(final HttpMessageNotReadableException e) {
        return respond(HttpStatus.BAD_REQUEST, problem(HttpStatus.BAD_REQUEST,
            "The body must be JSON such as {\"mode\": \"full\"}"));
    }

    private static ProblemDetail problem(final HttpStatus status, final String detail) {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        return problem;
    }

    private static ResponseEntity<ProblemDetail> respond(final HttpStatus status, final ProblemDetail problem) {
        return ResponseEntity.status(status).header(HttpHeaders.CONTENT_LANGUAGE, DETAIL_LANGUAGE).body(problem);
    }

}
