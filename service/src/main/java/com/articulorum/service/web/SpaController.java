package com.articulorum.service.web;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Serves the Angular single page application.
 *
 * <p>The application's {@code index.html} is rendered as a Thymeleaf template so that its
 * {@code <base href>} reflects the path the browser used (including a gateway prefix applied
 * through {@code X-Forwarded-Prefix}). Browser navigations to client-side routes such as
 * {@code /browse/books} have no static resource; they are answered with the same page so the
 * Angular router can take over (HTML5 routing). Requests for missing API resources or assets
 * still get a 404.</p>
 */
@Hidden
@Controller
public class SpaController {

    /** Name of the Thymeleaf view rendering {@code index.html}. */
    static final String INDEX_VIEW = "index";

    /**
     * Renders the application.
     *
     * @return the index view
     */
    @GetMapping("/")
    public String index() {
        return INDEX_VIEW;
    }

    /**
     * Exposes the base href of the application to the index template.
     *
     * @param model the view model
     */
    @ModelAttribute
    public void addBase(final Model model) {
        model.addAttribute("base", baseHref());
    }

    /**
     * Computes the base href: the context path as seen by the browser, ending with a slash.
     *
     * @return for example {@code /} or {@code /ui/}
     */
    static String baseHref() {
        final String path = ServletUriComponentsBuilder.fromCurrentContextPath().build().getPath();
        if (path == null || path.isEmpty()) {
            return "/";
        }
        return path.endsWith("/") ? path : path + "/";
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
