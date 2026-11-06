package com.articulorium.i18n;

import java.io.IOException;
import java.util.Locale;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Passes the locale of the request being served to the services it calls.
 *
 * <p>Added to the HTTP clients of the topology, transform and OAI services, so that the platform
 * answers them (error messages, language-dependent representations) in the user's locale. Calls
 * made outside a request (bus listeners, scheduled work) or that already carry
 * {@code Accept-Language} are left alone.</p>
 */
public class AcceptLanguageInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(final HttpRequest request, final byte[] body,
            final ClientHttpRequestExecution execution) throws IOException {
        final LocaleContext context = LocaleContextHolder.getLocaleContext();
        final Locale locale = context == null ? null : context.getLocale();
        if (locale != null && !request.getHeaders().containsHeader(HttpHeaders.ACCEPT_LANGUAGE)) {
            request.getHeaders().set(HttpHeaders.ACCEPT_LANGUAGE, locale.toLanguageTag());
        }
        return execution.execute(request, body);
    }

}
