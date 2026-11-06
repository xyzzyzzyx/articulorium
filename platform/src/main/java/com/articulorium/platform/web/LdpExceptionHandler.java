package com.articulorium.platform.web;

import java.nio.charset.StandardCharsets;

import com.articulorium.domain.vocabulary.Ldp;
import com.articulorium.i18n.Messages;
import com.articulorium.platform.ldp.LinkHeader;
import com.articulorium.platform.ldp.exception.LdpException;
import com.articulorium.platform.storage.BinaryNotFoundException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Translates exceptions raised while serving LDP requests into plain text error responses.
 *
 * <p>Every error response carries the {@code constrainedBy} link so clients can discover why a
 * request was refused (LDP 4.2.1.6).</p>
 *
 * <p>Messages with a code ({@link LdpException#getMessageCode()} and the handler's own) are
 * written in the request's locale; the others (protocol diagnostics naming headers, triples or
 * syntax) only exist in the default locale. {@code Content-Language} tells clients which one they
 * got, so the user interface can show its own translated summary of the status instead.</p>
 */
@Slf4j
@RestControllerAdvice
public class LdpExceptionHandler {

    private final Messages messages;

    /**
     * Creates the handler.
     *
     * @param messages the texts in the request's locale
     */
    public LdpExceptionHandler(final Messages messages) {
        this.messages = messages;
    }

    /**
     * Handles errors raised by the LDP service.
     *
     * @param exception the error
     * @param request   the request
     * @return the error response
     */
    @ExceptionHandler(LdpException.class)
    public ResponseEntity<String> handleLdpException(final LdpException exception, final HttpServletRequest request) {
        if (exception.getStatus().is5xxServerError()) {
            log.error("{} {} failed", request.getMethod(), request.getRequestURI(), exception);
        }
        if (exception.getMessageCode() != null) {
            return localized(exception.getStatus(), exception.getMessageCode(), exception.getMessageArguments(),
                exception.getHeaders(), request);
        }
        return error(exception.getStatus(), exception.getMessage(), defaultLanguage(), exception.getHeaders(),
            request);
    }

    /**
     * Handles multipart uploads exceeding the configured limit.
     *
     * @param exception the error
     * @param request   the request
     * @return 413 Content Too Large
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<String> handleMaxUploadSize(final MaxUploadSizeExceededException exception,
            final HttpServletRequest request) {
        return localized(HttpStatus.PAYLOAD_TOO_LARGE, "error.upload-too-large", null, new HttpHeaders(), request);
    }

    /**
     * Handles concurrent modifications detected by optimistic locking.
     *
     * @param exception the error
     * @param request   the request
     * @return 409 Conflict
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<String> handleOptimisticLock(final ObjectOptimisticLockingFailureException exception,
            final HttpServletRequest request) {
        return localized(HttpStatus.CONFLICT, "error.concurrent-modification", null, new HttpHeaders(), request);
    }

    /**
     * Handles unique constraint violations, i.e. two requests creating the same path at once.
     *
     * @param exception the error
     * @param request   the request
     * @return 409 Conflict
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<String> handleIntegrityViolation(final DataIntegrityViolationException exception,
            final HttpServletRequest request) {
        log.warn("Integrity violation for {} {}: {}", request.getMethod(), request.getRequestURI(),
            exception.getMostSpecificCause().getMessage());
        return localized(HttpStatus.CONFLICT, "error.already-exists", null, new HttpHeaders(), request);
    }

    /**
     * Handles malformed {@code Content-Type} headers.
     *
     * @param exception the error
     * @param request   the request
     * @return 400 Bad Request
     */
    @ExceptionHandler(InvalidMediaTypeException.class)
    public ResponseEntity<String> handleInvalidMediaType(final InvalidMediaTypeException exception,
            final HttpServletRequest request) {
        return localized(HttpStatus.BAD_REQUEST, "error.invalid-media-type", new Object[] { exception.getMessage() },
            new HttpHeaders(), request);
    }

    /**
     * Handles a binary missing from the store although its metadata exists.
     *
     * @param exception the error
     * @param request   the request
     * @return 500 Internal Server Error
     */
    @ExceptionHandler(BinaryNotFoundException.class)
    public ResponseEntity<String> handleMissingBinary(final BinaryNotFoundException exception,
            final HttpServletRequest request) {
        log.error("Binary content missing for {}", request.getRequestURI(), exception);
        return localized(HttpStatus.INTERNAL_SERVER_ERROR, "error.binary-missing", null, new HttpHeaders(), request);
    }

    private ResponseEntity<String> localized(final HttpStatus status, final String code, final Object[] arguments,
            final HttpHeaders extraHeaders, final HttpServletRequest request) {
        return error(status, messages.get(code, arguments == null ? new Object[0] : arguments),
            messages.descriptor().tag(), extraHeaders, request);
    }

    private String defaultLanguage() {
        return messages.defaultLocale().tag();
    }

    private static ResponseEntity<String> error(final HttpStatus status, final String message, final String language,
            final HttpHeaders extraHeaders, final HttpServletRequest request) {
        final HttpHeaders headers = new HttpHeaders();
        headers.addAll(extraHeaders);
        headers.set(HttpHeaders.CONTENT_LANGUAGE, language);
        headers.add(HttpHeaders.LINK, LinkHeader.format(RequestTargets.base(request) + ConstraintsController.PATH,
            Ldp.CONSTRAINED_BY));
        headers.setContentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8));
        return new ResponseEntity<>(message, headers, status);
    }

}
