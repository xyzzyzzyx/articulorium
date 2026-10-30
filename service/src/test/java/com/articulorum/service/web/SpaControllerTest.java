package com.articulorum.service.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit tests for {@link SpaController#isClientRoute}.
 */
class SpaControllerTest {

    private static MockHttpServletRequest request(final String method, final String uri, final String accept) {
        final MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (accept != null) {
            request.addHeader("Accept", accept);
        }
        return request;
    }

    @Test
    void browserNavigationsAreClientRoutes() {
        assertThat(SpaController.isClientRoute(request("GET", "/browse/books", "text/html,*/*;q=0.8"))).isTrue();
        assertThat(SpaController.isClientRoute(request("GET", "/search", null))).isTrue();
        assertThat(SpaController.isClientRoute(request("GET", "/memento/v1/books", "*/*"))).isTrue();
    }

    @Test
    void navigationsToResourcesNamedLikeFilesAreClientRoutes() {
        assertThat(SpaController.isClientRoute(request("GET", "/browse/books/notes.txt",
            "text/html,application/xhtml+xml,*/*;q=0.8"))).isTrue();
        assertThat(SpaController.isClientRoute(request("GET", "/fixity/scans/page-1.tiff", "text/html"))).isTrue();
    }

    @Test
    void apiAssetsAndOtherMethodsAreNot() {
        assertThat(SpaController.isClientRoute(request("GET", "/api/unknown", "text/html"))).isFalse();
        assertThat(SpaController.isClientRoute(request("GET", "/actuator/x", "text/html"))).isFalse();
        assertThat(SpaController.isClientRoute(request("GET", "/main.js", "*/*"))).isFalse();
        assertThat(SpaController.isClientRoute(request("GET", "/missing.css", null))).isFalse();
        assertThat(SpaController.isClientRoute(request("POST", "/browse", "text/html"))).isFalse();
        assertThat(SpaController.isClientRoute(request("GET", "/browse", "application/json"))).isFalse();
    }

}
