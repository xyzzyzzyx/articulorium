package com.articulorium.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

/**
 * Tests of {@link AcceptLanguageInterceptor}.
 */
class AcceptLanguageInterceptorTest {

    private final AcceptLanguageInterceptor interceptor = new AcceptLanguageInterceptor();

    @AfterEach
    void reset() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void passesTheRequestLocale() throws Exception {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("pt-BR"));
        final MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://p/"));
        interceptor.intercept(request, new byte[0], (r, b) -> new MockClientHttpResponse());
        assertThat(request.getHeaders().getFirst(HttpHeaders.ACCEPT_LANGUAGE)).isEqualTo("pt-BR");
    }

    @Test
    void keepsAnExplicitHeaderAndCallsOutsideRequests() throws Exception {
        LocaleContextHolder.setLocale(Locale.GERMAN);
        final MockClientHttpRequest explicit = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://p/"));
        explicit.getHeaders().set(HttpHeaders.ACCEPT_LANGUAGE, "fr");
        interceptor.intercept(explicit, new byte[0], (r, b) -> new MockClientHttpResponse());
        assertThat(explicit.getHeaders().getFirst(HttpHeaders.ACCEPT_LANGUAGE)).isEqualTo("fr");

        LocaleContextHolder.resetLocaleContext();
        final MockClientHttpRequest background = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://p/"));
        interceptor.intercept(background, new byte[0], (r, b) -> new MockClientHttpResponse());
        assertThat(background.getHeaders().containsHeader(HttpHeaders.ACCEPT_LANGUAGE)).isFalse();
    }

}
