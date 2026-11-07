package com.articulorium.ai.web;

import java.util.Locale;

import com.articulorium.ai.AgentException;
import com.articulorium.ai.AgentException.Reason;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders the agent's errors as RFC 9457 problem documents with a {@code reason} the user
 * interface explains in the user's language; the {@code detail} is an English diagnostic for
 * operators ({@code Content-Language: en}) and never holds repository content or credentials.
 */
@RestControllerAdvice
public class AgentExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AgentExceptionHandler.class);

    /**
     * Agent errors.
     *
     * @param e the error
     * @return the problem
     */
    @ExceptionHandler(AgentException.class)
    public ResponseEntity<ProblemDetail> agent(final AgentException e) {
        final Reason reason = e.getReason();
        if (reason.status() >= 500) {
            LOG.warn("AI request failed ({}): {}", reason, e.getMessage());
        } else {
            LOG.debug("AI request refused ({}): {}", reason, e.getMessage());
        }
        final HttpStatus status = HttpStatus.valueOf(reason.status());
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("reason", reason.name());
        final ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
            .header(HttpHeaders.CONTENT_LANGUAGE, Locale.ENGLISH.toLanguageTag());
        if (reason == Reason.UNAUTHENTICATED) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        if (reason == Reason.BUSY || reason == Reason.PLATFORM_UNAVAILABLE || reason == Reason.MODEL_UNAVAILABLE) {
            response.header(HttpHeaders.RETRY_AFTER, "30");
        }
        return response.body(problem);
    }

    /**
     * Unreadable request bodies.
     *
     * @param e the error
     * @return the problem
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> unreadable(final HttpMessageNotReadableException e) {
        return agent(new AgentException(Reason.INVALID_REQUEST, "The request body is not valid JSON for this request"));
    }

}
