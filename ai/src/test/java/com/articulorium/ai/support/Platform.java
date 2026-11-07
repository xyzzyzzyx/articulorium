package com.articulorium.ai.support;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Arrays;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * A mocked platform and topology service at {@code http://platform.test/} and
 * {@code http://topology.test/}, expectations in any order.
 */
public final class Platform {

    /** The LDP root. */
    public static final String BASE = "http://platform.test/";

    /** The topology API. */
    public static final String TOPOLOGY = "http://topology.test/";

    /** The credentials of the caller in tests. */
    public static final String TOKEN = "Bearer alice";

    private final MockRestServiceServer server;

    private Platform(final MockRestServiceServer server) {
        this.server = server;
    }

    /**
     * Binds a mock server to a builder.
     *
     * @param builder the builder the clients are built from
     * @return the platform
     */
    public static Platform bindTo(final RestClient.Builder builder) {
        return new Platform(MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build());
    }

    /**
     * Returns the mock server.
     *
     * @return the server
     */
    public MockRestServiceServer server() {
        return server;
    }

    /**
     * Expects the access check of a resource.
     *
     * @param path          the resource
     * @param authenticated whether the caller is known
     * @param modes         its modes
     * @return this
     */
    public Platform access(final String path, final boolean authenticated, final String... modes) {
        server.expect(ExpectedCount.manyTimes(), requestTo(BASE + ".well-known/webac?resource=" + path))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""
                {"agent": {"webId": %s, "authenticated": %s}, "userModes": [%s]}
                """.formatted(authenticated ? "\"info:oidc/agent/alice\"" : "null", authenticated,
                String.join(",", Arrays.stream(modes).map(mode -> "\"" + mode + "\"").toList())),
                MediaType.APPLICATION_JSON));
        return this;
    }

    /**
     * Expects reads of a resource's RDF.
     *
     * @param path     the resource
     * @param ntriples its N-Triples ({@code <>} is replaced with the resource's IRI)
     * @return this
     */
    public Platform rdf(final String path, final String ntriples) {
        server.expect(ExpectedCount.manyTimes(), requestTo(BASE + path))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Accept", "application/n-triples"))
            .andRespond(withSuccess(ntriples.replace("<>", "<" + BASE + path + ">"),
                MediaType.parseMediaType("application/n-triples")));
        return this;
    }

    /**
     * Expects reads of a resource the caller may not read.
     *
     * @param path   the resource
     * @param status the status (403, 404)
     * @return this
     */
    public Platform refused(final String path, final HttpStatus status) {
        server.expect(ExpectedCount.manyTimes(), requestTo(BASE + path)).andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(status));
        return this;
    }

    /**
     * Expects reads of a binary: RDF clients are sent to its description, others get the bytes.
     *
     * @param path        the binary
     * @param mediaType   its media type
     * @param bytes       its bytes
     * @param description its description's N-Triples ({@code <>} is the binary)
     * @return this
     */
    public Platform binary(final String path, final String mediaType, final byte[] bytes, final String description) {
        server.expect(ExpectedCount.manyTimes(), requestTo(BASE + path)).andExpect(method(HttpMethod.GET))
            .andRespond(request -> {
                final String accept = request.getHeaders().getFirst("Accept");
                if (accept != null && accept.contains("n-triples")) {
                    return withStatus(HttpStatus.SEE_OTHER).location(java.net.URI.create(BASE + path + "/description"))
                        .createResponse(request);
                }
                return withSuccess(bytes, MediaType.parseMediaType(mediaType)).createResponse(request);
            });
        server.expect(ExpectedCount.manyTimes(), requestTo(BASE + path + "/description"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(description.replace("<>", "<" + BASE + path + ">"),
                MediaType.parseMediaType("application/n-triples")));
        return this;
    }

    /**
     * Expects the list of topologies.
     *
     * @param json the summaries
     * @return this
     */
    public Platform topologies(final String json) {
        server.expect(ExpectedCount.manyTimes(), requestTo(TOPOLOGY + "topologies")).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        return this;
    }

    /**
     * Expects reads of a topology.
     *
     * @param id   the id
     * @param json the topology
     * @return this
     */
    public Platform topology(final String id, final String json) {
        server.expect(ExpectedCount.manyTimes(), requestTo(TOPOLOGY + "topologies/" + id))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        return this;
    }

}
