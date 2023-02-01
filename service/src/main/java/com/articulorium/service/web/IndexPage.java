package com.articulorium.service.web;

import com.articulorium.i18n.LocaleDescriptor;
import com.articulorium.i18n.Messages;
import com.articulorium.service.i18n.UiTranslations;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The model of the application page ({@code index.html}, a Thymeleaf template).
 *
 * <ul>
 *   <li>{@code base}: the {@code <base href>}, the context path as seen by the browser
 *       (including a gateway prefix applied through {@code X-Forwarded-Prefix});</li>
 *   <li>{@code lang} and {@code dir}: the document language and writing direction of the
 *       request's locale;</li>
 *   <li>{@code i18n}: the client's start-up state in that locale (locale, enabled locales,
 *       texts), so the application renders translated from its first frame.</li>
 * </ul>
 *
 * <p>The page varies with the locale, so it carries {@code Content-Language}, {@code Vary} and
 * must not be cached without revalidation.</p>
 */
@Component
public class IndexPage {

    /** Name of the Thymeleaf view rendering {@code index.html}. */
    static final String VIEW = "index";

    private final Messages messages;

    private final UiTranslations translations;

    /**
     * Creates the page model.
     *
     * @param messages     the request's locale
     * @param translations the user interface translations
     */
    public IndexPage(final Messages messages, final UiTranslations translations) {
        this.messages = messages;
        this.translations = translations;
    }

    /**
     * Fills the model and the headers of the page.
     *
     * @param model    the view model
     * @param response the response
     * @return the view name
     */
    public String render(final Model model, final HttpServletResponse response) {
        final LocaleDescriptor locale = messages.descriptor();
        model.addAttribute("base", baseHref());
        model.addAttribute("lang", locale.tag());
        model.addAttribute("dir", locale.direction());
        model.addAttribute("i18n", translations.state(locale).inlineJson());
        response.setHeader(HttpHeaders.CONTENT_LANGUAGE, locale.tag());
        response.setHeader(HttpHeaders.VARY,
            HttpHeaders.ACCEPT_LANGUAGE + ", " + HttpHeaders.COOKIE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        return VIEW;
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

}
