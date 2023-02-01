package com.articulorium.service.web;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the Angular single page application.
 *
 * <p>The application's {@code index.html} is rendered as a Thymeleaf template ({@link IndexPage})
 * so that its {@code <base href>} reflects the path the browser used and its language, writing
 * direction and texts are those of the request's locale. Browser navigations to client-side
 * routes such as {@code /browse/books} have no static resource; they are answered with the same
 * page so the Angular router can take over (HTML5 routing). Requests for missing API resources or
 * assets still get a 404.</p>
 */
@Hidden
@Controller
public class SpaController {

    private final IndexPage page;

    /**
     * Creates the controller.
     *
     * @param page the page model
     */
    public SpaController(final IndexPage page) {
        this.page = page;
    }

    /**
     * Renders the application.
     *
     * @param model    the view model
     * @param response the response
     * @return the index view
     */
    @GetMapping("/")
    public String index(final Model model, final HttpServletResponse response) {
        return page.render(model, response);
    }

    /**
     * Tells whether a request is a browser navigation to a client-side route.
     *
     * <p>A GET outside the API and the actuator is a client route when it explicitly accepts HTML
     * (a browser navigation, even to a route such as {@code /browse/books/notes.txt} whose last
     * segment looks like a file name), or when it accepts anything and has no file extension.
     * Requests for missing assets ({@code /missing.js} fetched by a script tag with
     * {@code Accept: *}{@code /*}) are therefore still answered with 404.</p>
     *
     * @param request the request
     * @return {@code true} when the application should answer with {@code index.html}
     */
    static boolean isClientRoute(final HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) {
            return false;
        }
        final String uri = request.getRequestURI().substring(request.getContextPath().length());
        if (uri.startsWith("/api/") || uri.startsWith("/actuator/")) {
            return false;
        }
        final String accept = request.getHeader("Accept");
        if (accept != null && accept.contains(MediaType.TEXT_HTML_VALUE)) {
            return true;
        }
        final String last = uri.substring(uri.lastIndexOf('/') + 1);
        return !last.contains(".") && (accept == null || accept.contains("*/*"));
    }

}
