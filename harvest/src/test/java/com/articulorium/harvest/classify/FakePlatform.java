package com.articulorium.harvest.classify;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.articulorium.domain.vocabulary.Ldp;
import com.articulorium.harvest.config.HarvestProperties;
import com.articulorium.harvest.ingest.PlatformAuthorization;
import com.articulorium.harvest.ingest.PlatformClient;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An in-memory platform (LDP resources as RDF, containment, {@code If-None-Match: *}, slugs) and
 * topology API behind a mock server, to test what classification does to the repository by its
 * effects rather than by a script of requests.
 */
final class FakePlatform {

    static final String BASE = "http://platform.test/";

    static final String TOPOLOGY = "http://topology.test/";

    private final Map<String, Model> resources = new LinkedHashMap<>();

    private final Map<String, JsonNode> topologies = new LinkedHashMap<>();

    private final Map<String, String> binaries = new LinkedHashMap<>();

    private final List<String> requests = new ArrayList<>();

    private final JsonMapper json = JsonMapper.builder().build();

    private final RestClient.Builder builder = RestClient.builder();

    private int failNextPuts;

    FakePlatform() {
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.manyTimes(), MockRestRequestMatchers.anything()).andRespond(this::respond);
    }

    /** A platform client presenting credentials. */
    PlatformClient client(final String authorization) {
        return new PlatformClient(builder.build(), BASE, PlatformAuthorization.fixed(authorization));
    }

    /** A topology client. */
    TopologyClient topologies() {
        return new TopologyClient(builder.build(), TOPOLOGY);
    }

    /** Stores a resource ({@code <>} in the N-Triples is the resource). */
    FakePlatform put(final String path, final String ntriples) {
        resources.put(path, parse(ntriples.replace("<>", "<" + BASE + path + ">"), BASE + path));
        return this;
    }

    /** Defines a topology. */
    FakePlatform topology(final String id, final String definition) {
        topologies.put(id, json.readTree(definition));
        return this;
    }

    /** Returns a topology's definition, {@code null} when there is none. */
    JsonNode topology(final String id) {
        return topologies.get(id);
    }

    /** The files stored: "media type, size, file name" by path. */
    Map<String, String> binaries() {
        return binaries;
    }

    /** Fails the next PUT requests with 503. */
    FakePlatform failPuts(final int count) {
        failNextPuts = count;
        return this;
    }

    Model get(final String path) {
        return resources.get(path);
    }

    boolean exists(final String path) {
        return resources.containsKey(path);
    }

    List<String> requests() {
        return requests;
    }

    private ClientHttpResponse respond(final ClientHttpRequest request) throws IOException {
        final MockClientHttpRequest mock = (MockClientHttpRequest) request;
        final URI uri = request.getURI();
        final String body = mock.getBodyAsString(StandardCharsets.UTF_8);
        requests.add(request.getMethod() + " " + uri + " " + request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (uri.toString().startsWith(TOPOLOGY)) {
            return topologyApi(request.getMethod(), uri.getPath(), body);
        }
        final String path = uri.getPath().substring(1);
        if (request.getMethod() == HttpMethod.GET) {
            final Model model = resources.get(path);
            if (model == null) {
                return response(HttpStatus.NOT_FOUND, "", null);
            }
            final Model out = ModelFactory.createDefaultModel().add(model);
            final Resource subject = out.createResource(BASE + path);
            subject.addProperty(out.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                out.createResource(Ldp.BASIC_CONTAINER));
            subject.addProperty(out.createProperty("http://fedora.info/definitions/v4/repository#created"), "2026");
            for (final String child : resources.keySet()) {
                if (child.startsWith(path + "/") && child.indexOf('/', path.length() + 1) < 0) {
                    subject.addProperty(out.createProperty(Ldp.CONTAINS), out.createResource(BASE + child));
                }
            }
            return response(HttpStatus.OK, write(out), "application/n-triples");
        }
        if (request.getMethod() == HttpMethod.PUT) {
            if (failNextPuts > 0) {
                failNextPuts--;
                return response(HttpStatus.SERVICE_UNAVAILABLE, "down", "text/plain");
            }
            final boolean exists = resources.containsKey(path);
            if (exists && "*".equals(request.getHeaders().getFirst(HttpHeaders.IF_NONE_MATCH))) {
                return response(HttpStatus.PRECONDITION_FAILED, "", null);
            }
            resources.put(path, parse(body, BASE + path));
            return response(exists ? HttpStatus.NO_CONTENT : HttpStatus.CREATED, "", null);
        }
        if (request.getMethod() == HttpMethod.POST) {
            final String slug = request.getHeaders().getFirst("Slug");
            String child = path + "/" + slug;
            for (int n = 2; resources.containsKey(child); n++) {
                child = path + "/" + slug + "-" + n;
            }
            final MediaType type = request.getHeaders().getContentType();
            if (type != null && !"turtle".equals(type.getSubtype())) {
                // a file: no triples of its own, its bytes and media type are kept
                resources.put(child, ModelFactory.createDefaultModel());
                binaries.put(child, type + " " + mock.getBodyAsBytes().length + " "
                    + request.getHeaders().getContentDisposition().getFilename());
            } else {
                resources.put(child, turtle(body, BASE + child));
            }
            final MockClientHttpResponse created = new MockClientHttpResponse(new byte[0], HttpStatus.CREATED);
            created.getHeaders().setLocation(URI.create(BASE + child));
            return created;
        }
        if (request.getMethod() == HttpMethod.DELETE) {
            if (!resources.containsKey(path)) {
                return response(HttpStatus.NOT_FOUND, "", null);
            }
            resources.keySet().removeIf(key -> key.equals(path) || key.startsWith(path + "/"));
            return response(HttpStatus.NO_CONTENT, "", null);
        }
        return response(HttpStatus.METHOD_NOT_ALLOWED, "", null);
    }

    private ClientHttpResponse topologyApi(final HttpMethod method, final String path, final String body) {
        final String[] segments = path.substring(1).split("/");
        if (segments.length == 1 && method == HttpMethod.GET) {
            final tools.jackson.databind.node.ArrayNode list = json.createArrayNode();
            topologies.forEach((id, definition) -> list.addObject().put("id", id)
                .put("name", definition.path("name").asString()));
            return response(HttpStatus.OK, list.toString(), MediaType.APPLICATION_JSON_VALUE);
        }
        if (segments.length == 1) {
            final JsonNode created = json.readTree(body);
            final String id = created.path("id").asString();
            if (topologies.containsKey(id)) {
                return response(HttpStatus.BAD_REQUEST, "{\"detail\": \"A topology " + id + " already exists\"}",
                    MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            }
            topologies.put(id, created);
            return response(HttpStatus.CREATED, created.toString(), MediaType.APPLICATION_JSON_VALUE);
        }
        final JsonNode topology = segments.length > 1 ? topologies.get(segments[1]) : null;
        if (topology == null) {
            return response(HttpStatus.NOT_FOUND, "", null);
        }
        if (method == HttpMethod.GET) {
            return response(HttpStatus.OK, topology.toString(), MediaType.APPLICATION_JSON_VALUE);
        }
        final JsonNode request = json.readTree(body);
        final String root = request.path("parent").asString() + "/" + request.path("slug").asString();
        put(root, """
            <> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://pcdm.org/models#Object> .
            <> <http://purl.org/dc/terms/title> "%s" .
            <> <https://articulorium.org/ns/repository#topology> "%s" .
            <> <https://articulorium.org/ns/repository#topologyNode> "%s" .
            """.formatted(request.path("title").asString(), segments[1], topology.path("root").path("key").asString()));
        put(root + "/files", "<> <http://www.w3.org/ns/ldp#hasMemberRelation> <http://pcdm.org/models#hasFile> .\n");
        return response(HttpStatus.CREATED, "{\"path\": \"" + root + "\", \"counts\": " + request.path("counts") + "}",
            MediaType.APPLICATION_JSON_VALUE);
    }

    private static ClientHttpResponse response(final HttpStatus status, final String body, final String type) {
        final MockClientHttpResponse response = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), status);
        if (type != null) {
            response.getHeaders().setContentType(MediaType.parseMediaType(type));
        }
        return response;
    }

    private static Model parse(final String ntriples, final String base) {
        final Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, new ByteArrayInputStream(ntriples.getBytes(StandardCharsets.UTF_8)), base, Lang.NTRIPLES);
        return model;
    }

    private static Model turtle(final String turtle, final String base) {
        final Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, new ByteArrayInputStream(turtle.getBytes(StandardCharsets.UTF_8)), base, Lang.TURTLE);
        return model;
    }

    static String write(final Model model) {
        final StringWriter writer = new StringWriter();
        RDFDataMgr.write(writer, model, Lang.NTRIPLES);
        return writer.toString();
    }

    /** The harvest settings of the tests. */
    static HarvestProperties.PlatformAuth noAuth() {
        return new HarvestProperties.PlatformAuth();
    }

    static Clock clock() {
        return Clock.fixed(java.time.Instant.parse("2026-11-07T10:00:00Z"), java.time.ZoneOffset.UTC);
    }

}
