package com.articulorium.platform.webac;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Tests {@link BearerTokens}.
 */
class BearerTokensTest {

    @Test
    void readsBearerTokensCaseInsensitively() {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "bearer  abc ");
        assertThat(BearerTokens.bearer(request)).isEqualTo("abc");
    }

    @Test
    void ignoresOtherSchemesAndEmptyTokens() {
        final MockHttpServletRequest basic = new MockHttpServletRequest();
        basic.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        assertThat(BearerTokens.bearer(basic)).isNull();

        final MockHttpServletRequest empty = new MockHttpServletRequest();
        empty.addHeader("Authorization", "Bearer   ");
        assertThat(BearerTokens.bearer(empty)).isNull();

        assertThat(BearerTokens.bearer(new MockHttpServletRequest())).isNull();
    }

    @Test
    void recognisesJwts() {
        assertThat(BearerTokens.isJwt(new TestTokens().token("alice"))).isTrue();
        assertThat(BearerTokens.isJwt("eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4In0.")).isFalse();
        assertThat(BearerTokens.isJwt("6f1c2a3e-1234-4cde-9abc-0123456789ab")).isFalse();
        assertThat(BearerTokens.isJwt("a.b.c.d")).isFalse();
        assertThat(BearerTokens.isJwt(null)).isFalse();
    }

}
