package com.articulorium.service.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * HTML5 routing fallback: a browser navigation to a client-side route (which has no static
 * resource) is answered with the application page; any other missing resource gets a 404.
 */
@ControllerAdvice
public class SpaFallbackAdvice {

    private final IndexPage page;

    /**
     * Creates the advice.
     *
     * @param page the page model
     */
    public SpaFallbackAdvice(final IndexPage page) {
        this.page = page;
    }

    /**
     * Handles requests no controller or static resource matched.
     *
     * @param exception the error
     * @param request   the request
     * @param response  the response
     * @param model     the view model
     * @return the index view for client routes, otherwise {@code null} after sending 404
     * @throws IOException when the error cannot be sent
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public String handle(final NoResourceFoundException exception, final HttpServletRequest request,
            final HttpServletResponse response, final Model model) throws IOException {
        if (SpaController.isClientRoute(request)) {
            return page.render(model, response);
        }
        response.sendError(HttpStatus.NOT_FOUND.value());
        return null;
    }

}
