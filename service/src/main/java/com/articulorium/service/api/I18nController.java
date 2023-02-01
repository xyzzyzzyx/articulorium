package com.articulorium.service.api;

import com.articulorium.i18n.Messages;
import com.articulorium.service.i18n.UiTranslations;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * The user interface's locales and translations.
 *
 * <p>The client normally starts from the state inlined into {@code index.html}; it uses these
 * endpoints when the user switches language (and when it runs without the inlined state, as
 * under {@code ng serve}). Responses carry an entity tag and must be revalidated, so a new
 * release's translations are picked up at once.</p>
 */
@RestController
@RequestMapping(path = "/api/i18n", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "User interface", description = "Settings and agents for the user interface")
public class I18nController {

    private final UiTranslations translations;

    private final Messages messages;

    /**
     * Creates the controller.
     *
     * @param translations the user interface translations
     * @param messages     the request's locale
     */
    public I18nController(final UiTranslations translations, final Messages messages) {
        this.translations = translations;
        this.messages = messages;
    }

    /**
     * The state of the locale negotiated for this request ({@code ?lang=}, the locale cookie,
     * {@code Accept-Language}, the default locale).
     *
     * @param request the request, for conditional requests
     * @return the locale, every enabled locale and the texts
     */
    @GetMapping
    @Operation(operationId = "uiTranslationsNegotiated",
        summary = "Locales and texts of the user interface in the negotiated locale")
    public ResponseEntity<String> negotiated(final WebRequest request) {
        return respond(translations.state(messages.descriptor()), request);
    }

    /**
     * The state of one locale.
     *
     * @param tag     a language tag; a tag of an enabled language in another region is accepted
     * @param request the request, for conditional requests
     * @return the locale, every enabled locale and the texts
     * @throws ResponseStatusException 404 when no enabled locale matches the tag
     */
    @GetMapping("/{tag}")
    @Operation(operationId = "uiTranslations",
        summary = "Locales and texts of the user interface in a locale")
    public ResponseEntity<String> locale(@PathVariable final String tag, final WebRequest request) {
        return respond(translations.state(tag).orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, messages.get("i18n.unknown-locale", tag))), request);
    }

    private static ResponseEntity<String> respond(final UiTranslations.State state,
            final WebRequest request) {
        final ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
            .eTag(state.etag())
            .cacheControl(CacheControl.maxAge(Duration.ZERO).mustRevalidate())
            .header(HttpHeaders.CONTENT_LANGUAGE, state.locale().tag())
            .header(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE + ", " + HttpHeaders.COOKIE);
        if (request.checkNotModified(state.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(state.etag()).build();
        }
        return builder.body(state.json());
    }

}
