package com.articulorium.platform;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.articulorium.platform.webac.TestTokens;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end tests of OpenID Connect access tokens as WebAC identities: tokens signed by an
 * identity provider whose key set is served over HTTP (as Keycloak's {@code /certs} endpoint is),
 * with the roles of {@code webac/root-acl-oidc.ttl}.
 */
@SpringBootTest(properties = {
    "articulorium.webac.root-acl=classpath:webac/root-acl-oidc.ttl",
    "articulorium.webac.oidc.enabled=true",
    "articulorium.webac.oidc.issuer-uri=" + TestTokens.ISSUER,
    "articulorium.webac.oidc.audiences=" + TestTokens.AUDIENCE,
    "articulorium.webac.oidc.group-claims=realm_access.roles,groups"
})
@AutoConfigureMockMvc
class OidcIntegrationTest {

    private static final String BASE = "http://localhost";

    private static final String TURTLE = "text/turtle";

    private static final TestTokens TOKENS = new TestTokens();

    private static final HttpServer IDP = identityProvider();

    @Autowired
    private MockMvc mvc;

    private static HttpServer identityProvider() {
        try {
            final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/certs", exchange -> {
                final byte[] body = TOKENS.jwks().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void keySet(final DynamicPropertyRegistry registry) {
        registry.add("articulorium.webac.oidc.jwk-set-uri",
            () -> "http://127.0.0.1:" + IDP.getAddress().getPort() + "/certs");
    }

    @AfterAll
    static void stop() {
        IDP.stop(0);
    }

    private static String bearer(final String token) {
        return "Bearer " + token;
    }

    /** Everyone reads; anonymous writers are asked to authenticate. */
    @Test
    void anonymousAgentsRead() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk())
            .andExpect(header().string("WAC-Allow", "user=\"read\",public=\"read\""));
        mvc.perform(post("/").contentType(TURTLE).content(""))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("Bearer")));
    }

    /** The editors role writes and is recorded as creator; only administrators change ACLs. */
    @Test
    void realmRolesGrantAccess() throws Exception {
        final String editor = TOKENS.token("curator", "editors");
        final String container = mvc.perform(post("/").header(HttpHeaders.AUTHORIZATION, bearer(editor))
                .header("Slug", "oidc-" + UUID.randomUUID().toString().substring(0, 8))
                .contentType(TURTLE).content(""))
            .andExpect(status().isCreated()).andReturn().getResponse().getHeader(HttpHeaders.LOCATION)
            .substring(BASE.length());

        mvc.perform(get(container).accept("application/n-triples"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("info:oidc/agent/curator")));

        final String acl = "@prefix acl: <http://www.w3.org/ns/auth/acl#> .\n"
            + "<#c> a acl:Authorization ; acl:agent <info:oidc/agent/curator> ; acl:accessTo <" + BASE + container
            + "> ; acl:mode acl:Read, acl:Write, acl:Control .\n";
        mvc.perform(put(container + "/fcr:acl").header(HttpHeaders.AUTHORIZATION, bearer(editor))
                .contentType(TURTLE).content(acl))
            .andExpect(status().isForbidden());
        mvc.perform(put(container + "/fcr:acl")
                .header(HttpHeaders.AUTHORIZATION, bearer(TOKENS.token("admin", "administrators")))
                .contentType(TURTLE).content(acl))
            .andExpect(status().isCreated());
    }

    /** Authenticated agents without a granting role are forbidden, not asked to authenticate. */
    @Test
    void agentsWithoutRolesAreForbidden() throws Exception {
        mvc.perform(post("/").header(HttpHeaders.AUTHORIZATION, bearer(TOKENS.token("reader")))
                .contentType(TURTLE).content(""))
            .andExpect(status().isForbidden());
    }

    /** Tokens that fail validation are rejected whatever the ACL grants. */
    @Test
    void invalidTokensAreRejected() throws Exception {
        final String expired = TOKENS.sign(TOKENS.claims("curator", "editors")
            .expirationTime(Date.from(Instant.now().minusSeconds(600))).build());
        final String forged = TestTokens.sign(TOKENS.claims("curator", "editors").build(), new TestTokens().key());
        for (final String token : new String[] {expired, forged}) {
            mvc.perform(get("/").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("The access token is not valid"));
        }
        mvc.perform(get("/.well-known/webac").header(HttpHeaders.AUTHORIZATION, bearer(expired)))
            .andExpect(status().isUnauthorized());
    }

    /** The WebAC endpoint tells the user interface who signed in. */
    @Test
    void accessInformationNamesTheAgent() throws Exception {
        mvc.perform(get("/.well-known/webac")
                .header(HttpHeaders.AUTHORIZATION, bearer(TOKENS.token("admin", "administrators"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.agent.webId").value("info:oidc/agent/admin"))
            .andExpect(jsonPath("$.agent.authenticated").value(true))
            .andExpect(jsonPath("$.agent.groups").value(containsInAnyOrder("info:oidc/group/administrators")))
            .andExpect(jsonPath("$.userModes").value(containsInAnyOrder("read", "write", "append", "control")));
    }

}
