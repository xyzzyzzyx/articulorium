package com.articulorium.discovery.solr;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Captures the form parameters of requests to a mocked Solr.
 */
public final class SolrRequests {

    private SolrRequests() {
    }

    /**
     * A matcher that records the request's form parameters.
     *
     * @param into where the parameters are added
     * @return the matcher
     */
    public static RequestMatcher capture(final MultiValueMap<String, String> into) {
        return request -> into.addAll(form(((MockClientHttpRequest) request).getBodyAsString()));
    }

    /**
     * Decodes a form.
     *
     * @param body the {@code application/x-www-form-urlencoded} body
     * @return its parameters
     */
    public static MultiValueMap<String, String> form(final String body) {
        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        if (body.isEmpty()) {
            return form;
        }
        for (final String pair : body.split("&")) {
            final int equals = pair.indexOf('=');
            final String name = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
            final String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            form.add(name, value);
        }
        return form;
    }

    /**
     * The values of a parameter.
     *
     * @param form the parameters
     * @param name the name
     * @return its values, empty when it has none
     */
    public static List<String> values(final MultiValueMap<String, String> form, final String name) {
        final List<String> values = form.get(name);
        return values == null ? List.of() : values;
    }

}
