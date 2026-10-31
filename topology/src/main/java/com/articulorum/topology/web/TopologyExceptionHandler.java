package com.articulorum.topology.web;

import com.articulorum.topology.platform.PlatformException;
import com.articulorum.topology.service.InvalidTopologyException;
import com.articulorum.topology.service.TopologyNotFoundException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders errors of the topology API as RFC 9457 problem documents.
 */
@RestControllerAdvice
public class TopologyExceptionHandler {

    /**
     * Invalid topologies and requests: 400 with every problem listed.
     *
     * @param e the exception
     * @return the problem
     */
    @ExceptionHandler(InvalidTopologyException.class)
    public ProblemDetail invalid(final InvalidTopologyException e) {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid topology request");
        problem.setProperty("errors", e.getErrors());
        return problem;
    }

    /**
     * Unknown topologies: 404.
     *
     * @param e the exception
     * @return the problem
     */
    @ExceptionHandler(TopologyNotFoundException.class)
    public ProblemDetail notFound(final TopologyNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * Platform refusals keep the platform's status (401/403 from access control, 404, 409); other
     * platform failures are 502.
     *
     * @param e the exception
     * @return the problem
     */
    @ExceptionHandler(PlatformException.class)
    public ProblemDetail platform(final PlatformException e) {
        final HttpStatus status = HttpStatus.resolve(e.getStatus());
        return ProblemDetail.forStatusAndDetail(status == null ? HttpStatus.BAD_GATEWAY : status, e.getMessage());
    }

}
