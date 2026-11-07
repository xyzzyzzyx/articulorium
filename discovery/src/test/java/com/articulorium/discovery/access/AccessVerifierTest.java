package com.articulorium.discovery.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.articulorium.discovery.MutableClock;
import com.articulorium.discovery.config.DiscoveryProperties;
import com.articulorium.discovery.config.PublicUrls;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Tests the access checks against a mocked platform.
 */
class AccessVerifierTest {

    private static final String WEBAC = "http://platform.test/.well-known/webac?resource=";

    private MockRestServiceServer platform;

    private DiscoveryProperties properties;

    private MutableClock clock;

    private AccessVerifier access;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        platform = MockRestServiceServer.bindTo(builder).build();
        properties = new DiscoveryProperties();
        properties.setPlatformUrl("http://platform.test");
        clock = new MutableClock(Instant.parse("2026-11-07T12:00:00Z"));
        access = new AccessVerifier(builder, properties, new PublicUrls(properties), clock);
    }

    private static String modes(final String... modes) {
        return "{\"userModes\": [" + String.join(",", java.util.Arrays.stream(modes).map(m -> "\"" + m + "\"").toList())
            + "], \"publicModes\": []}";
    }

    @Test
    void asksThePlatformWithTheVisitorsCredentialsAndThePublicBase() {
        platform.expect(requestTo(WEBAC + "communities/c/book"))
            .andExpect(header("Authorization", "Bearer alice"))
            .andExpect(header("X-Forwarded-Host", "localhost"))
            .andExpect(header("X-Forwarded-Port", "8000"))
            .andExpect(header("X-Forwarded-Prefix", "/platform"))
            .andRespond(withSuccess(modes("read"), MediaType.APPLICATION_JSON));

        assertThat(access.mayRead("communities/c/book", "Bearer alice")).isTrue();
        platform.verify();
    }

    @Test
    void hidesWhatThePlatformDoesNotGrantOrCannotDecide() {
        platform.expect(requestTo(WEBAC + "private")).andExpect(headerDoesNotExist("Authorization"))
            .andRespond(withSuccess(modes(), MediaType.APPLICATION_JSON));
        platform.expect(requestTo(WEBAC + "refused")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        platform.expect(requestTo(WEBAC + "broken")).andRespond(withServerError());
        platform.expect(requestTo(WEBAC + "odd")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(access.mayRead("private", null)).isFalse();
        assertThat(access.mayRead("refused", "Bearer expired")).isFalse();
        assertThat(access.mayRead("broken", "Bearer x")).isFalse();
        assertThat(access.mayRead("odd", "Bearer x")).isFalse();
    }

    @Test
    void remembersAnonymousAnswersForAWhile() {
        platform.expect(once(), requestTo(WEBAC + "public")).andRespond(withSuccess(modes("read"), MediaType.APPLICATION_JSON));

        assertThat(access.mayRead("public", null)).isTrue();
        assertThat(access.mayRead("public", null)).isTrue();
        platform.verify();

        platform.reset();
        clock.advance(Duration.ofSeconds(61));
        platform.expect(requestTo(WEBAC + "public")).andRespond(withSuccess(modes(), MediaType.APPLICATION_JSON));
        assertThat(access.mayRead("public", null)).isFalse();
        platform.verify();
    }

    @Test
    void neverRemembersAnswersForSignedInVisitors() {
        platform.expect(requestTo(WEBAC + "x")).andRespond(withSuccess(modes("read"), MediaType.APPLICATION_JSON));
        platform.expect(requestTo(WEBAC + "x")).andRespond(withSuccess(modes(), MediaType.APPLICATION_JSON));

        assertThat(access.mayRead("x", "Bearer a")).isTrue();
        assertThat(access.mayRead("x", "Bearer b")).isFalse();
        platform.verify();
    }

    @Test
    void keepsReadableResourcesInOrder() {
        properties.getAccess().setParallelism(1);
        access = new AccessVerifier(rebind(), properties, new PublicUrls(properties), clock);
        platform.expect(requestTo(WEBAC + "a")).andRespond(withSuccess(modes("read"), MediaType.APPLICATION_JSON));
        platform.expect(requestTo(WEBAC + "b")).andRespond(withSuccess(modes(), MediaType.APPLICATION_JSON));
        platform.expect(requestTo(WEBAC + "c")).andRespond(withSuccess(modes("read", "write"), MediaType.APPLICATION_JSON));

        assertThat(access.readable(List.of("a", "b", "c"), null)).containsExactly("a", "c");
    }

    @Test
    void trustsTheIndexWhenVerificationIsOff() {
        properties.getAccess().setVerify(false);

        assertThat(access.mayRead("anything", null)).isTrue();
        assertThat(access.readable(List.of("a", "b"), null)).containsExactly("a", "b");
        platform.verify();
    }

    private RestClient.Builder rebind() {
        final RestClient.Builder builder = RestClient.builder();
        // concurrent checks reach the platform in any order
        platform = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        return builder;
    }

}
