package com.articulorum.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.articulorum.domain.vocabulary.Ldp;
import com.articulorum.domain.vocabulary.Premis;
import com.articulorum.domain.vocabulary.Rdf;
import com.articulorum.event.EventAction;
import com.articulorum.event.ResourceEvent;
import com.articulorum.platform.rdf.RdfMediaTypes;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end tests of the Linked Data Platform over HTTP (MockMvc against the full application
 * context with an in-memory database and file system binary storage).
 *
 * <p>Each test works in its own uniquely named container so tests are independent although they
 * share one database.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class LdpIntegrationTest {

    private static final String BASE = "http://localhost";

    private static final String TURTLE = "text/turtle";

    private static final String DCTERMS = "http://purl.org/dc/terms/";

    private static final String PCDM_HAS_MEMBER = "http://pcdm.org/models#hasMember";

    private static final String PCDM_MEMBER_OF = "http://pcdm.org/models#memberOf";

    private static final String ORE_PROXY_FOR = "http://www.openarchives.org/ore/terms/proxyFor";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationEvents events;

    // ------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------

    private static String unique(final String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String path(final String location) {
        return location.substring(BASE.length());
    }

    private static String typeLink(final String type) {
        return "<" + type + ">; rel=\"type\"";
    }

    /** Creates a container below the root and returns its path (e.g. {@code /books-1a2b3c4d}). */
    private String container(final String slug, final String turtle) throws Exception {
        final MockHttpServletResponse response = mvc.perform(post("/")
                .header("Slug", slug)
                .contentType(TURTLE)
                .content(turtle))
            .andExpect(status().isCreated())
            .andReturn().getResponse();
        return path(response.getHeader(HttpHeaders.LOCATION));
    }

    private String container(final String slug) throws Exception {
        return container(slug, "<> <" + DCTERMS + "title> \"" + slug + "\" .");
    }

    private Model rdf(final String path) throws Exception {
        return rdf(path, null);
    }

    private Model rdf(final String path, final String prefer) throws Exception {
        final var request = get(path).accept(TURTLE);
        if (prefer != null) {
            request.header("Prefer", prefer);
        }
        final String body = mvc.perform(request).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        final Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(body), BASE + path, "TURTLE");
        return model;
    }

    private String etag(final String path) throws Exception {
        return mvc.perform(head(path)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    private static boolean has(final Model model, final String subject, final String predicate, final String object) {
        final Resource s = model.createResource(BASE + subject);
        final Property p = model.createProperty(predicate);
        return model.contains(s, p, model.createResource(object.startsWith("/") ? BASE + object : object));
    }

    private static boolean hasLiteral(final Model model, final String subject, final String predicate, final String value) {
        return model.contains(model.createResource(BASE + subject), model.createProperty(predicate), value);
    }

    // ------------------------------------------------------------------------------------------
    // root and discovery
    // ------------------------------------------------------------------------------------------

    @Test
    void rootIsABasicContainerThatCannotBeDeleted() throws Exception {
        mvc.perform(get("/").accept(TURTLE))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(RdfMediaTypes.TEXT_TURTLE))
            .andExpect(header().stringValues(HttpHeaders.LINK, hasItems(
                typeLink(Ldp.RESOURCE), typeLink(Ldp.RDF_SOURCE), typeLink(Ldp.CONTAINER), typeLink(Ldp.BASIC_CONTAINER),
                "<" + BASE + "/.well-known/ldp-constraints>; rel=\"" + Ldp.CONSTRAINED_BY + "\"")))
            .andExpect(header().exists(HttpHeaders.ETAG))
            .andExpect(header().exists("Accept-Post"))
            .andExpect(header().string("Accept-Patch", "application/sparql-update"));

        final Model root = rdf("/");
        assertThat(hasLiteral(root, "/", Rdf.RDFS_LABEL, "articulorum")).isTrue();
        assertThat(has(root, "/", Rdf.TYPE, Ldp.BASIC_CONTAINER)).isTrue();

        mvc.perform(delete("/"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(header().exists(HttpHeaders.ALLOW));
    }

    @Test
    void defaultRepresentationIsTurtle() throws Exception {
        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(RdfMediaTypes.TEXT_TURTLE));
        mvc.perform(get("/").accept(MediaType.ALL))
            .andExpect(content().contentTypeCompatibleWith(RdfMediaTypes.TEXT_TURTLE));
    }

    @Test
    void contentNegotiationProducesEveryRdfSyntax() throws Exception {
        for (final MediaType type : RdfMediaTypes.writableTypes()) {
            mvc.perform(get("/").accept(type))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(type));
        }
        mvc.perform(get("/").accept(MediaType.IMAGE_PNG)).andExpect(status().isNotAcceptable());
    }

    @Test
    void optionsAdvertisesCapabilities() throws Exception {
        final String books = container(unique("options"));
        mvc.perform(options(books))
            .andExpect(status().isNoContent())
            .andExpect(header().string(HttpHeaders.ALLOW, allOf(
                containsString("POST"), containsString("PATCH"),
                containsString("DELETE"))))
            .andExpect(header().string("Accept-Post", containsString("text/turtle")));
    }

    @Test
    void headReturnsHeadersWithoutBody() throws Exception {
        mvc.perform(head("/"))
            .andExpect(status().isOk())
            .andExpect(header().exists(HttpHeaders.ETAG))
            .andExpect(content().string(""));
    }

    @Test
    void constraintsDocumentIsPublished() throws Exception {
        mvc.perform(get("/.well-known/ldp-constraints"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
            .andExpect(content().string(containsString("Server-managed triples")));
    }

    @Test
    void missingResourcesAre404WithConstraintsLink() throws Exception {
        mvc.perform(get("/" + unique("missing")))
            .andExpect(status().isNotFound())
            .andExpect(header().string(HttpHeaders.LINK, containsString(Ldp.CONSTRAINED_BY)));
    }

    @Test
    void browsersGetAnHtmlViewWithUploadForm() throws Exception {
        final String books = container(unique("html"));
        mvc.perform(get(books).accept(MediaType.TEXT_HTML, MediaType.ALL))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string(containsString("multipart/form-data")));
    }

    // ------------------------------------------------------------------------------------------
    // creation (POST)
    // ------------------------------------------------------------------------------------------

    @Test
    void postCreatesBasicContainerWithSlugAndContainment() throws Exception {
        final String slug = unique("books");
        mvc.perform(post("/").header("Slug", slug).contentType(TURTLE)
                .content("<> <" + DCTERMS + "title> \"Books\" ."))
            .andExpect(status().isCreated())
            .andExpect(header().string(HttpHeaders.LOCATION, BASE + "/" + slug))
            .andExpect(header().stringValues(HttpHeaders.LINK, hasItem(typeLink(Ldp.BASIC_CONTAINER))))
            .andExpect(content().string(BASE + "/" + slug));

        assertThat(hasLiteral(rdf("/" + slug), "/" + slug, DCTERMS + "title", "Books")).isTrue();
        assertThat(has(rdf("/"), "/", Ldp.CONTAINS, "/" + slug)).isTrue();
    }

    @Test
    void postPublishesCreatedAndUpdatedEvents() throws Exception {
        final String books = container(unique("events"));
        final List<ResourceEvent> published = events.stream(ResourceEvent.class).toList();
        assertThat(published).anySatisfy(event -> {
            assertThat(event.getAction()).isEqualTo(EventAction.CREATED);
            assertThat(event.getResource().getRelativeIri()).isEqualTo(books);
            assertThat(event.getBaseUri()).isEqualTo(BASE);
            assertThat(event.getTriples()).isNotEmpty();
        });
        assertThat(published).anySatisfy(event -> {
            assertThat(event.getAction()).isEqualTo(EventAction.UPDATED);
            assertThat(event.getResource().isRoot()).isTrue();
        });
    }

    @Test
    void postWithoutBodyCreatesEmptyBasicContainer() throws Exception {
        final String location = mvc.perform(post("/"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(has(rdf(path(location)), path(location), Rdf.TYPE, Ldp.BASIC_CONTAINER)).isTrue();
    }

    @Test
    void linkHeaderRequestsRdfSource() throws Exception {
        final String books = container(unique("rs"));
        final String location = mvc.perform(post(books).header(HttpHeaders.LINK, typeLink(Ldp.RDF_SOURCE))
                .contentType(TURTLE).content("<> <" + DCTERMS + "title> \"A note\" ."))
            .andExpect(status().isCreated())
            .andExpect(header().stringValues(HttpHeaders.LINK, not(
                hasItem(typeLink(Ldp.CONTAINER)))))
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);

        mvc.perform(post(path(location)).contentType(TURTLE).content(""))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(header().string(HttpHeaders.ALLOW, not(containsString("POST"))));
    }

    @Test
    void slugCollisionsGetAFreshName() throws Exception {
        final String books = container(unique("slugs"));
        final String first = mvc.perform(post(books).header("Slug", "chapter").contentType(TURTLE).content(""))
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        final String second = mvc.perform(post(books).header("Slug", "chapter").contentType(TURTLE).content(""))
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(first).isEqualTo(BASE + books + "/chapter");
        assertThat(second).startsWith(BASE + books + "/chapter-").isNotEqualTo(first);
    }

    @Test
    void reservedRootNamesAreNotUsed() throws Exception {
        final String location = mvc.perform(post("/").header("Slug", ".well-known").contentType(TURTLE).content(""))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).isNotEqualTo(BASE + "/.well-known");
    }

    @Test
    void invalidRdfIsRejected() throws Exception {
        mvc.perform(post("/").contentType(TURTLE).content("<> <broken"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void containmentTriplesInBodyAreRejected() throws Exception {
        mvc.perform(post("/").contentType(TURTLE).content("<> <" + Ldp.CONTAINS + "> </elsewhere> ."))
            .andExpect(status().isConflict())
            .andExpect(header().string(HttpHeaders.LINK, containsString(Ldp.CONSTRAINED_BY)));
    }

    @Test
    void containerTypeRequiresRdfBody() throws Exception {
        mvc.perform(post("/").header(HttpHeaders.LINK, typeLink(Ldp.BASIC_CONTAINER))
                .contentType(MediaType.IMAGE_PNG).content(new byte[] { 1, 2, 3 }))
            .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void jsonLdBodiesAreAccepted() throws Exception {
        final String json = "{\"@id\": \"\", \"http://purl.org/dc/terms/title\": \"From JSON-LD\"}";
        final String location = mvc.perform(post("/").contentType(RdfMediaTypes.APPLICATION_LD_JSON).content(json))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(hasLiteral(rdf(path(location)), path(location), DCTERMS + "title", "From JSON-LD")).isTrue();
    }

    // ------------------------------------------------------------------------------------------
    // non-RDF sources (files)
    // ------------------------------------------------------------------------------------------

    @Test
    void rawBinaryBodyCreatesNonRdfSourceWithDescription() throws Exception {
        final String files = container(unique("files"));
        final byte[] bytes = "hello, binary".getBytes(StandardCharsets.UTF_8);
        final String location = mvc.perform(post(files).header("Slug", "hello.txt")
                .contentType(MediaType.TEXT_PLAIN).content(bytes))
            .andExpect(status().isCreated())
            .andExpect(header().stringValues(HttpHeaders.LINK, hasItems(
                typeLink(Ldp.NON_RDF_SOURCE), "<" + BASE + files + "/hello.txt/description>; rel=\"describedby\"")))
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        final String file = path(location);

        mvc.perform(get(file))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
            .andExpect(content().bytes(bytes))
            .andExpect(header().string("Digest", startsWith("sha-256=")))
            .andExpect(header().string(HttpHeaders.ALLOW, not(containsString("PATCH"))));

        mvc.perform(get(file + "/description").accept(TURTLE))
            .andExpect(status().isOk())
            .andExpect(header().stringValues(HttpHeaders.LINK, hasItem("<" + BASE + file + ">; rel=\"describes\"")));
        final Model description = rdf(file + "/description");
        assertThat(has(description, file, Rdf.TYPE, Ldp.NON_RDF_SOURCE)).isTrue();
        assertThat(description.contains(description.createResource(BASE + file), description.createProperty(Premis.HAS_SIZE)))
            .isTrue();
        assertThat(hasLiteral(description, file, Premis.HAS_MIME_TYPE, "text/plain")).isTrue();

        assertThat(has(rdf(files), files, Ldp.CONTAINS, file)).isTrue();
    }

    @Test
    void rdfOnlyClientsAreRedirectedToTheDescriptionOfBinaries() throws Exception {
        final String files = container(unique("conneg"));
        final String file = path(mvc.perform(post(files).contentType(MediaType.APPLICATION_PDF).content(new byte[] { 1 }))
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION));
        mvc.perform(get(file).accept(RdfMediaTypes.TEXT_TURTLE))
            .andExpect(status().isSeeOther())
            .andExpect(header().string(HttpHeaders.LOCATION, BASE + file + "/description"));
        mvc.perform(get(file).accept(MediaType.ALL)).andExpect(status().isOk());
    }

    @Test
    void binariesSupportRangeRequests() throws Exception {
        final String files = container(unique("range"));
        final String location = mvc.perform(post(files).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content("0123456789".getBytes(StandardCharsets.US_ASCII)))
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        mvc.perform(get(path(location)).header(HttpHeaders.RANGE, "bytes=2-4"))
            .andExpect(status().isPartialContent())
            .andExpect(content().string("234"));
    }

    @Test
    void plainJsonUploadsBecomeFiles() throws Exception {
        final String files = container(unique("json"));
        mvc.perform(post(files).contentType(MediaType.APPLICATION_JSON).content("{\"a\":1}"))
            .andExpect(status().isCreated())
            .andExpect(header().stringValues(HttpHeaders.LINK, hasItem(typeLink(Ldp.NON_RDF_SOURCE))));
    }

    @Test
    void rdfCanBeStoredAsFileWithLinkHeader() throws Exception {
        final String files = container(unique("ttlfile"));
        final String turtle = "<a> <b> <c> .";
        final String location = mvc.perform(post(files).header(HttpHeaders.LINK, typeLink(Ldp.NON_RDF_SOURCE))
                .contentType(TURTLE).content(turtle))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        mvc.perform(get(path(location)))
            .andExpect(content().contentTypeCompatibleWith(RdfMediaTypes.TEXT_TURTLE))
            .andExpect(content().string(turtle));
    }

    @Test
    void multipartUploadCreatesFileNamedAfterTheUpload() throws Exception {
        final String files = container(unique("upload"));
        final MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", new byte[] { 37, 80, 68, 70 });
        final String location = mvc.perform(multipart(files).file(file))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).isEqualTo(BASE + files + "/report.pdf");
        mvc.perform(get(path(location)))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("report.pdf")));
    }

    @Test
    void browserFormUploadRedirectsBackToTheContainer() throws Exception {
        final String files = container(unique("form"));
        final MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", "notes".getBytes());
        mvc.perform(multipart(files).file(file).param("slug", "my-notes").accept(MediaType.TEXT_HTML))
            .andExpect(status().isSeeOther())
            .andExpect(header().string(HttpHeaders.LOCATION, BASE + files));
        mvc.perform(get(files + "/my-notes")).andExpect(status().isOk()).andExpect(content().string("notes"));
    }

    @Test
    void browserFormCanCreateContainers() throws Exception {
        final String parent = container(unique("formdir"));
        mvc.perform(multipart(parent).param("kind", "container").param("slug", "sub"))
            .andExpect(status().isCreated());
        assertThat(has(rdf(parent + "/sub"), parent + "/sub", Rdf.TYPE, Ldp.BASIC_CONTAINER)).isTrue();
    }

    @Test
    void putReplacesBinaryContent() throws Exception {
        final String files = container(unique("replace"));
        final String file = path(mvc.perform(post(files).contentType(MediaType.TEXT_PLAIN).content("v1"))
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION));
        final String before = etag(file);

        mvc.perform(put(file).header(HttpHeaders.IF_MATCH, before).contentType(MediaType.TEXT_PLAIN).content("version 2"))
            .andExpect(status().isNoContent());

        mvc.perform(get(file)).andExpect(content().string("version 2"));
        assertThat(etag(file)).isNotEqualTo(before);
    }

    @Test
    void descriptionsCanBeReplacedAndPatched() throws Exception {
        final String files = container(unique("describe"));
        final String file = path(mvc.perform(post(files).contentType(MediaType.TEXT_PLAIN).content("x"))
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION));

        mvc.perform(put(file + "/description").contentType(TURTLE).content("<> <" + DCTERMS + "title> \"A file\" ."))
            .andExpect(status().isNoContent());
        assertThat(hasLiteral(rdf(file + "/description"), file, DCTERMS + "title", "A file")).isTrue();

        mvc.perform(patch(file + "/description").contentType(RdfMediaTypes.APPLICATION_SPARQL_UPDATE)
                .content("INSERT DATA { <> <" + DCTERMS + "creator> \"Ishmael\" }"))
            .andExpect(status().isNoContent());
        assertThat(hasLiteral(rdf(file + "/description"), file, DCTERMS + "creator", "Ishmael")).isTrue();

        mvc.perform(put(file + "/description").contentType(TURTLE)
                .content("<> <" + Premis.HAS_MIME_TYPE + "> \"image/png\" ."))
            .andExpect(status().isConflict());

        mvc.perform(patch(file).contentType(RdfMediaTypes.APPLICATION_SPARQL_UPDATE).content("INSERT DATA { <> <a:b> \"c\" }"))
            .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete(file + "/description")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void binariesCannotContainResources() throws Exception {
        final String files = container(unique("nochild"));
        final String file = path(mvc.perform(post(files).contentType(MediaType.TEXT_PLAIN).content("x"))
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION));
        mvc.perform(post(file).contentType(TURTLE).content("")).andExpect(status().isMethodNotAllowed());
        mvc.perform(put(file + "/child").contentType(TURTLE).content("")).andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------------------------------
    // replacement (PUT) and preconditions
    // ------------------------------------------------------------------------------------------

    @Test
    void putReplacesClientManagedTriples() throws Exception {
        final String books = container(unique("put"));
        mvc.perform(put(books).contentType(TURTLE).content("<> <" + DCTERMS + "title> \"Renamed\" ."))
            .andExpect(status().isNoContent())
            .andExpect(header().exists(HttpHeaders.ETAG));
        final Model model = rdf(books);
        assertThat(hasLiteral(model, books, DCTERMS + "title", "Renamed")).isTrue();
        assertThat(model.listStatements(model.createResource(BASE + books), model.createProperty(DCTERMS + "title"),
            (RDFNode) null).toList()).hasSize(1);
    }

    @Test
    void putAcceptsEchoedServerManagedTriples() throws Exception {
        final String books = container(unique("echo"));
        mvc.perform(post(books).header("Slug", "child").contentType(TURTLE).content("")).andExpect(status().isCreated());
        final String body = mvc.perform(get(books).accept(TURTLE)).andReturn().getResponse().getContentAsString();
        mvc.perform(put(books).contentType(TURTLE).content(body)).andExpect(status().isNoContent());
    }

    @Test
    void putCannotChangeContainmentOrInteractionModel() throws Exception {
        final String books = container(unique("immutable"));
        mvc.perform(put(books).contentType(TURTLE).content("<> <" + Ldp.CONTAINS + "> </elsewhere> ."))
            .andExpect(status().isConflict());
        mvc.perform(put(books).contentType(TURTLE).content("<> a <" + Ldp.DIRECT_CONTAINER + "> ."))
            .andExpect(status().isConflict());
        mvc.perform(put(books).header(HttpHeaders.LINK, typeLink(Ldp.RDF_SOURCE)).contentType(TURTLE).content(""))
            .andExpect(status().isConflict());
    }

    @Test
    void ifMatchGuardsUpdates() throws Exception {
        final String books = container(unique("ifmatch"));
        final String current = etag(books);
        mvc.perform(put(books).header(HttpHeaders.IF_MATCH, "\"stale\"").contentType(TURTLE).content(""))
            .andExpect(status().isPreconditionFailed());
        mvc.perform(put(books).header(HttpHeaders.IF_MATCH, current).contentType(TURTLE).content(""))
            .andExpect(status().isNoContent());
        mvc.perform(delete(books).header(HttpHeaders.IF_MATCH, current))
            .andExpect(status().isPreconditionFailed());
    }

    @Test
    void ifNoneMatchProducesNotModified() throws Exception {
        final String books = container(unique("conditional"));
        final String current = etag(books);
        mvc.perform(get(books).header(HttpHeaders.IF_NONE_MATCH, current)).andExpect(status().isNotModified());
        mvc.perform(get(books).header(HttpHeaders.IF_NONE_MATCH, "\"other\"")).andExpect(status().isOk());
    }

    @Test
    void addingAChildChangesTheParentsEntityTag() throws Exception {
        final String books = container(unique("etag"));
        final String before = etag(books);
        mvc.perform(post(books).contentType(TURTLE).content("")).andExpect(status().isCreated());
        assertThat(etag(books)).isNotEqualTo(before);
    }

    @Test
    void putCreatesResourcesAtClientChosenPaths() throws Exception {
        final String books = container(unique("putcreate"));
        mvc.perform(put(books + "/chosen").contentType(TURTLE).content("<> <" + DCTERMS + "title> \"Chosen\" ."))
            .andExpect(status().isCreated())
            .andExpect(header().string(HttpHeaders.LOCATION, BASE + books + "/chosen"));
        assertThat(has(rdf(books), books, Ldp.CONTAINS, books + "/chosen")).isTrue();

        mvc.perform(put(books + "/chosen").header(HttpHeaders.IF_NONE_MATCH, "*").contentType(TURTLE).content(""))
            .andExpect(status().isPreconditionFailed());
        mvc.perform(put("/" + unique("nowhere") + "/child").contentType(TURTLE).content(""))
            .andExpect(status().isConflict());
        mvc.perform(put(books + "/missing").header(HttpHeaders.IF_MATCH, "\"x\"").contentType(TURTLE).content(""))
            .andExpect(status().isPreconditionFailed());
    }

    // ------------------------------------------------------------------------------------------
    // PATCH
    // ------------------------------------------------------------------------------------------

    @Test
    void patchAppliesSparqlUpdate() throws Exception {
        final String books = container(unique("patch"));
        mvc.perform(patch(books).contentType(RdfMediaTypes.APPLICATION_SPARQL_UPDATE)
                .content("DELETE { <> <" + DCTERMS + "title> ?t } INSERT { <> <" + DCTERMS + "title> \"Patched\" } "
                    + "WHERE { <> <" + DCTERMS + "title> ?t }"))
            .andExpect(status().isNoContent());
        final Model model = rdf(books);
        assertThat(hasLiteral(model, books, DCTERMS + "title", "Patched")).isTrue();
    }

    @Test
    void patchCannotTouchServerManagedTriples() throws Exception {
        final String books = container(unique("patchguard"));
        mvc.perform(post(books).header("Slug", "child").contentType(TURTLE).content("")).andExpect(status().isCreated());
        mvc.perform(patch(books).contentType(RdfMediaTypes.APPLICATION_SPARQL_UPDATE)
                .content("DELETE WHERE { <> <" + Ldp.CONTAINS + "> ?child }"))
            .andExpect(status().isConflict());
        mvc.perform(patch(books).contentType(RdfMediaTypes.APPLICATION_SPARQL_UPDATE)
                .content("INSERT DATA { <> <" + Ldp.CONTAINS + "> </x> }"))
            .andExpect(status().isConflict());
        assertThat(has(rdf(books), books, Ldp.CONTAINS, books + "/child")).isTrue();
    }

    @Test
    void patchRequiresSparqlUpdate() throws Exception {
        final String books = container(unique("patchtype"));
        mvc.perform(patch(books).contentType(TURTLE).content("<> <a:b> \"c\" ."))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(header().string("Accept-Patch", "application/sparql-update"));
        mvc.perform(patch(books).contentType(RdfMediaTypes.APPLICATION_SPARQL_UPDATE).content("NOT SPARQL"))
            .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------------------------------
    // DELETE
    // ------------------------------------------------------------------------------------------

    @Test
    void deleteRemovesContainersRecursively() throws Exception {
        final String books = container(unique("delete"));
        mvc.perform(post(books).header("Slug", "a").contentType(TURTLE).content("")).andExpect(status().isCreated());
        mvc.perform(post(books + "/a").header("Slug", "file").contentType(MediaType.TEXT_PLAIN).content("bytes"))
            .andExpect(status().isCreated());

        mvc.perform(delete(books)).andExpect(status().isNoContent());

        mvc.perform(get(books)).andExpect(status().isNotFound());
        mvc.perform(get(books + "/a")).andExpect(status().isNotFound());
        mvc.perform(get(books + "/a/file")).andExpect(status().isNotFound());
        assertThat(has(rdf("/"), "/", Ldp.CONTAINS, books)).isFalse();
        assertThat(events.stream(ResourceEvent.class).filter(e -> e.getAction() == EventAction.DELETED).count())
            .isEqualTo(3);
    }

    // ------------------------------------------------------------------------------------------
    // Prefer
    // ------------------------------------------------------------------------------------------

    @Test
    void preferMinimalContainerOmitsContainment() throws Exception {
        final String books = container(unique("prefer"));
        mvc.perform(post(books).header("Slug", "child").contentType(TURTLE).content("")).andExpect(status().isCreated());
        final String prefer = "return=representation; include=\"" + Ldp.PREFER_MINIMAL_CONTAINER + "\"";

        mvc.perform(get(books).accept(TURTLE).header("Prefer", prefer))
            .andExpect(header().string("Preference-Applied", "return=representation"));
        final Model minimal = rdf(books, prefer);
        assertThat(has(minimal, books, Ldp.CONTAINS, books + "/child")).isFalse();
        assertThat(has(minimal, books, Rdf.TYPE, Ldp.BASIC_CONTAINER)).isTrue();
        assertThat(has(rdf(books), books, Ldp.CONTAINS, books + "/child")).isTrue();
    }

    // ------------------------------------------------------------------------------------------
    // membership
    // ------------------------------------------------------------------------------------------

    @Test
    void directContainerMaintainsMembershipTriples() throws Exception {
        final String collection = container(unique("collection"));
        final String members = path(mvc.perform(post(collection).header("Slug", "members").contentType(TURTLE)
                .content("<> <" + Ldp.MEMBERSHIP_RESOURCE + "> <" + collection + "> ; <"
                    + Ldp.HAS_MEMBER_RELATION + "> <" + PCDM_HAS_MEMBER + "> ."))
            .andExpect(status().isCreated())
            .andExpect(header().stringValues(HttpHeaders.LINK, hasItem(typeLink(Ldp.DIRECT_CONTAINER))))
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION));

        final String collectionEtag = etag(collection);
        final String member = path(mvc.perform(post(members).header("Slug", "object-1").contentType(TURTLE).content(""))
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION));

        assertThat(has(rdf(collection), collection, PCDM_HAS_MEMBER, member)).isTrue();
        assertThat(etag(collection)).isNotEqualTo(collectionEtag);

        final String prefer = "return=representation; omit=\"" + Ldp.PREFER_MEMBERSHIP + "\"";
        assertThat(has(rdf(collection, prefer), collection, PCDM_HAS_MEMBER, member)).isFalse();

        mvc.perform(delete(member)).andExpect(status().isNoContent());
        assertThat(has(rdf(collection), collection, PCDM_HAS_MEMBER, member)).isFalse();
    }

    @Test
    void directContainerDefaultsToItselfAndLdpMember() throws Exception {
        final String direct = path(mvc.perform(post("/").header(HttpHeaders.LINK, typeLink(Ldp.DIRECT_CONTAINER))
                .contentType(TURTLE).content(""))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION));
        final String member = path(mvc.perform(post(direct).contentType(TURTLE).content(""))
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION));
        final Model model = rdf(direct);
        assertThat(has(model, direct, Ldp.MEMBERSHIP_RESOURCE, direct)).isTrue();
        assertThat(has(model, direct, Ldp.MEMBER, member)).isTrue();
    }

    @Test
    void isMemberOfRelationAppearsOnTheMember() throws Exception {
        final String collection = container(unique("memberof"));
        final String members = path(mvc.perform(post(collection).contentType(TURTLE)
                .content("<> <" + Ldp.MEMBERSHIP_RESOURCE + "> <" + collection + "> ; <"
                    + Ldp.IS_MEMBER_OF_RELATION + "> <" + PCDM_MEMBER_OF + "> ."))
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION));
        final String member = path(mvc.perform(post(members).contentType(TURTLE).content(""))
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION));
        assertThat(has(rdf(member), member, PCDM_MEMBER_OF, collection)).isTrue();
    }

    @Test
    void indirectContainerUsesInsertedContent() throws Exception {
        final String collection = container(unique("indirect"));
        final String proxies = path(mvc.perform(post(collection).header("Slug", "proxies").contentType(TURTLE)
                .content("<> <" + Ldp.MEMBERSHIP_RESOURCE + "> <" + collection + "> ; <"
                    + Ldp.HAS_MEMBER_RELATION + "> <" + PCDM_HAS_MEMBER + "> ; <"
                    + Ldp.INSERTED_CONTENT_RELATION + "> <" + ORE_PROXY_FOR + "> ."))
            .andExpect(status().isCreated())
            .andExpect(header().stringValues(HttpHeaders.LINK, hasItem(typeLink(Ldp.INDIRECT_CONTAINER))))
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION));

        mvc.perform(post(proxies).contentType(TURTLE).content("<> <" + ORE_PROXY_FOR + "> <http://example.org/object/9> ."))
            .andExpect(status().isCreated());

        assertThat(has(rdf(collection), collection, PCDM_HAS_MEMBER, "http://example.org/object/9")).isTrue();
    }

    @Test
    void indirectContainerRequiresInsertedContentRelation() throws Exception {
        mvc.perform(post("/").header(HttpHeaders.LINK, typeLink(Ldp.INDIRECT_CONTAINER)).contentType(TURTLE).content(""))
            .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------------------------------
    // proxies
    // ------------------------------------------------------------------------------------------

    @Test
    void forwardedHeadersShapeResourceIris() throws Exception {
        final String location = mvc.perform(post("/").header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "data.example.org").header("X-Forwarded-Prefix", "/platform")
                .contentType(TURTLE).content(""))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).startsWith("https://data.example.org/platform/");
        mvc.perform(get(location.substring("https://data.example.org/platform".length())))
            .andExpect(status().isOk());
    }

}
