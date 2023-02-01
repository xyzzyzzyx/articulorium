package com.articulorium.topology.web;

import com.articulorium.i18n.Messages;
import com.articulorium.topology.platform.PlatformException;
import com.articulorium.topology.service.InvalidTopologyException;
import com.articulorium.topology.service.TopologyNotFoundException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders errors of the topology API as RFC 9457 problem documents.
 *
 * <p>The {@code title} is written in the request's locale; validation {@code errors} and the
 * {@code detail} built from them are diagnostics in the default locale, which
 * {@code Content-Language} states. A missing topology is reported entirely in the request's
 * locale.</p>
 */
@RestControllerAdvice
public class TopologyExceptionHandler {

    private final Messages messages;

    /**
     * Creates the handler.
     *
     * @param messages the texts in the request's locale
     */
    public TopologyExceptionHandler(final Messages messages) {
        this.messages = messages;
    }

    /**
     * Invalid topologies and requests: 400 with every problem listed.
     *
     * @param e the exception
     * @return the problem
     */
    @ExceptionHandler(InvalidTopologyException.class)
    public ResponseEntity<ProblemDetail> invalid(final InvalidTopologyException e) {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle(messages.get("problem.invalid"));
        problem.setProperty("errors", e.getErrors());
        return respond(problem, messages.defaultLocale().tag());
    }

    /**
     * Unknown topologies: 404.
     *
     * @param e the exception
     * @return the problem
     */
    @ExceptionHandler(TopologyNotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(final TopologyNotFoundException e) {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
            messages.get("problem.not-found", e.getId()));
        problem.setTitle(messages.status(HttpStatus.NOT_FOUND.value()));
        return respond(problem, messages.descriptor().tag());
    }

    /**
     * Platform refusals keep the platform's status (401/403 from access control, 404, 409); other
     * platform failures are 502.
     *
     * @param e the exception
     * @return the problem
     */
    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ProblemDetail> platform(final PlatformException e) {
        final HttpStatus resolved = HttpStatus.resolve(e.getStatus());
        final HttpStatus status = resolved == null ? HttpStatus.BAD_GATEWAY : resolved;
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle(messages.status(status.value()));
        return respond(problem, messages.defaultLocale().tag());
    }

    private static ResponseEntity<ProblemDetail> respond(final ProblemDetail problem, final String detailLanguage) {
        return ResponseEntity.status(problem.getStatus())
            .header(HttpHeaders.CONTENT_LANGUAGE, detailLanguage)
            .body(problem);
    }

}
