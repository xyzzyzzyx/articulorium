package com.articulorium.service.i18n;

import com.articulorium.i18n.LocaleDescriptor;
import com.articulorium.i18n.SupportedLocales;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The user interface's translations, as built into the {@code ui} module
 * ({@code static/i18n/<tag>.json}).
 *
 * <p>Each locale's texts are merged over the default locale's, so the client always receives a
 * complete set: a text a translation lacks shows in the default locale rather than as a key.
 * The result is the <em>state</em> the client starts from, either inlined into
 * {@code index.html} (no extra request, no flash of untranslated text) or fetched from
 * {@code api/i18n} when the user switches language.</p>
 */
public class UiTranslations {

    private static final Logger LOG = LoggerFactory.getLogger(UiTranslations.class);

    /** Where the {@code ui} module puts its translations. */
    public static final String DEFAULT_LOCATION = "classpath:/static/i18n/";

    private final ResourceLoader resources;

    private final String location;

    private final SupportedLocales locales;

    private final JsonMapper json;

    private final String cookieName;

    private final Map<String, State> cache = new ConcurrentHashMap<>();

    /**
     * Creates the translations.
     *
     * @param resources  loads the translation files
     * @param location   the directory of the files, ending with {@code /}
     * @param locales    the enabled locales
     * @param json       JSON support
     * @param cookieName the cookie the client stores the chosen locale in
     */
    public UiTranslations(final ResourceLoader resources, final String location,
            final SupportedLocales locales, final JsonMapper json, final String cookieName) {
        this.resources = resources;
        this.location = location;
        this.locales = locales;
        this.json = json;
        this.cookieName = cookieName;
    }

    /**
     * The client state of a locale.
     *
     * @param locale an enabled locale
     * @return the state
     */
    public State state(final LocaleDescriptor locale) {
        return cache.computeIfAbsent(locale.tag(), tag -> build(locale));
    }

    /**
     * The client state of a requested tag.
     *
     * @param tag a language tag
     * @return the state, empty when the tag matches no enabled locale exactly or by language
     */
    public Optional<State> state(final String tag) {
        return locales.match(tag).map(this::state);
    }

    private State build(final LocaleDescriptor locale) {
        final LocaleDescriptor fallback = locales.defaultLocale();
        final ObjectNode messages = read(fallback).orElseGet(json::createObjectNode);
        if (!locale.equals(fallback)) {
            read(locale).ifPresentOrElse(translation -> merge(messages, translation),
                () -> LOG.warn("No user interface translation for {}: texts show in {}",
                    locale.tag(), fallback.tag()));
        }
        final ObjectNode state = json.createObjectNode();
        state.put("locale", locale.tag());
        state.put("direction", locale.direction());
        state.put("defaultLocale", fallback.tag());
        state.put("cookie", cookieName);
        final ArrayNode list = state.putArray("locales");
        locales.all().forEach(entry -> list.addObject()
            .put("tag", entry.tag())
            .put("name", entry.name())
            .put("direction", entry.direction()));
        state.set("messages", messages);
        final String text = json.writeValueAsString(state);
        return new State(locale, text, etag(text));
    }

    private Optional<ObjectNode> read(final LocaleDescriptor locale) {
        final Resource resource = resources.getResource(location + locale.tag() + ".json");
        if (!resource.exists()) {
            return Optional.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            final JsonNode node = json.readTree(in);
            return node instanceof ObjectNode object ? Optional.of(object) : Optional.empty();
        } catch (IOException | JacksonException e) {
            LOG.error("Cannot read the user interface translation {}", resource, e);
            return Optional.empty();
        }
    }

    /**
     * Copies every text of a translation over the default texts, recursively.
     *
     * @param target      the default texts, modified
     * @param translation the translation
     */
    static void merge(final ObjectNode target, final JsonNode translation) {
        translation.properties().forEach(entry -> {
            final JsonNode existing = target.get(entry.getKey());
            if (existing instanceof ObjectNode nested && entry.getValue().isObject()) {
                merge(nested, entry.getValue());
            } else if (entry.getValue().isString() && !entry.getValue().asString().isBlank()
                    || entry.getValue().isObject()) {
                target.set(entry.getKey(), entry.getValue());
            }
        });
    }

    private static String etag(final String text) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
            return "\"" + HexFormat.of().formatHex(digest, 0, 16) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * What the client starts from in one locale.
     *
     * @param locale the locale
     * @param json   {@code {locale, direction, defaultLocale, cookie, locales, messages}}
     * @param etag   the entity tag of {@code json}
     */
    public record State(LocaleDescriptor locale, String json, String etag) {

        /**
         * The state as the content of an HTML {@code <script>} element: every {@code <} is
         * written as a JSON escape, so no text can end the element or open a comment.
         *
         * @return the escaped JSON
         */
        public String inlineJson() {
            return json.replace("<", "\\u003c");
        }

    }

}
