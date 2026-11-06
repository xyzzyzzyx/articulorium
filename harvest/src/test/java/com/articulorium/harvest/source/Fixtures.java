package com.articulorium.harvest.source;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Map;

import com.articulorium.harvest.core.SourceCredentials;
import com.articulorium.harvest.core.SourceSettings;

/**
 * Test fixtures: recorded responses of the source protocols and settings.
 */
public final class Fixtures {

    private Fixtures() {
        // utility class
    }

    /**
     * Reads a fixture.
     *
     * @param path the path below {@code fixtures/}
     * @return the bytes
     */
    public static byte[] bytes(final String path) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + path)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Builds settings.
     *
     * @param type        the type
     * @param url         the url
     * @param pageSize    the page size
     * @param credentials the credentials
     * @param options     the options
     * @return the settings
     */
    public static SourceSettings settings(final String type, final String url, final int pageSize,
            final SourceCredentials credentials, final Map<String, String> options) {
        return new SourceSettings("test", type, URI.create(url), pageSize, credentials, options);
    }

}
