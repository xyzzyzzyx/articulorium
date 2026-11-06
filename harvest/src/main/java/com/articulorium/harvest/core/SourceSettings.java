package com.articulorium.harvest.core;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The configuration of one source, as handed to its {@link HarvestSourceFactory}.
 *
 * @param id          the source identifier (letters, digits, {@code -} and {@code _})
 * @param type        the source type
 * @param url         the endpoint (OAI-PMH base URL, DSpace REST API, Fedora LDP base, VIVO base)
 * @param pageSize    how many records to ask for per page, when the protocol lets the client choose
 * @param credentials how to authenticate to the source
 * @param options     type-specific options
 */
public record SourceSettings(String id, String type, URI url, int pageSize, SourceCredentials credentials,
        Map<String, String> options) {

    /**
     * Creates settings.
     *
     * @param id          the identifier
     * @param type        the type
     * @param url         the endpoint (absolute http or https URL)
     * @param pageSize    the page size (at least 1)
     * @param credentials the credentials
     * @param options     the options
     */
    public SourceSettings {
        Objects.requireNonNull(id, "id");
        if (!id.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Source identifiers may only hold letters, digits, - and _: " + id);
        }
        Objects.requireNonNull(type, "type");
        if (url == null || !url.isAbsolute() || !("http".equals(url.getScheme()) || "https".equals(url.getScheme()))) {
            throw new IllegalArgumentException("Source " + id + " needs an absolute http or https url");
        }
        if (url.getRawUserInfo() != null) {
            // credentials in URLs end up in logs and reports
            throw new IllegalArgumentException("Source " + id + ": put credentials in username/password/token, not in the url");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("The page size of source " + id + " must be at least 1");
        }
        credentials = credentials == null ? SourceCredentials.NONE : credentials;
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    /**
     * Returns the base URL with a trailing slash, for resolving relative paths.
     *
     * @return the base
     */
    public String base() {
        final String text = url.toString();
        return text.endsWith("/") ? text : text + "/";
    }

    /**
     * Reads an option.
     *
     * @param name the option
     * @return its value, or empty when unset or blank
     */
    public Optional<String> option(final String name) {
        final String value = options.get(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    /**
     * Reads an option with a default.
     *
     * @param name     the option
     * @param fallback the default
     * @return its value, or the default
     */
    public String option(final String name, final String fallback) {
        return option(name).orElse(fallback);
    }

    /**
     * Reads a boolean option.
     *
     * @param name     the option
     * @param fallback the default
     * @return its value
     * @throws IllegalArgumentException when the value is neither {@code true} nor {@code false}
     */
    public boolean flag(final String name, final boolean fallback) {
        return option(name).map(value -> {
            if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("Option " + name + " of source " + id + " must be true or false");
            }
            return Boolean.parseBoolean(value);
        }).orElse(fallback);
    }

}
