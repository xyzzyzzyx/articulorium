package com.articulorum.platform.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.articulorum.platform.search.SearchService;
import com.articulorum.platform.service.HistoryService;
import com.articulorum.platform.web.ExtensionHandler;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BinarySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.tags.Tag;

/**
 * OpenAPI description of the platform, served at {@code /.well-known/openapi} and rendered by
 * Swagger UI at {@code /.well-known/swagger-ui.html}.
 *
 * <p>The LDP controller maps every path with a single catch-all handler, which springdoc cannot
 * describe meaningfully, so the controller is hidden from scanning and this configuration
 * documents the LDP operations and the Fedora API extensions explicitly: HTTP methods,
 * LDP request headers ({@code Link}, {@code Slug}, {@code Prefer}, preconditions), accepted
 * bodies and responses. Other controllers are documented from their annotations.</p>
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    /** Tag of the LDP resource operations. */
    static final String TAG_LDP = "Linked Data Platform";

    /** Tag of the versioning operations. */
    static final String TAG_VERSIONS = "Versions (Memento)";

    /** Tag of fixity and search. */
    static final String TAG_REPOSITORY = "Fixity and search";

    /** RDF media types the platform reads and writes. */
    static final List<String> RDF_TYPES = List.of("text/turtle", "application/ld+json", "application/n-triples",
        "application/rdf+xml", "application/rdf+json", "text/n3");

    private static final String PATH_DESCRIPTION = "Path of the resource below the server root, for example "
        + "`books/moby-dick`. It may contain `/`; send the slashes unencoded (Swagger UI encodes them, so use the "
        + "root path or a single segment when trying requests here).";

    /**
     * General API information.
     *
     * @param version the application version
     * @return the OpenAPI root
     */
    @Bean
    public OpenAPI platformOpenApi(@Value("${articulorum.api.version:1.0.0}") final String version) {
        return new OpenAPI()
            .info(new Info()
                .title("Articulorum Linked Data Platform")
                .version(version)
                .description("W3C Linked Data Platform 1.0 server with Fedora Commons compatible versioning "
                    + "(Memento), fixity and search, backed by an OCFL storage layer. "
                    + "Constraints on clients: `/.well-known/ldp-constraints`.")
                .contact(new Contact().name("Articulorum").url("https://github.com/xyzzyzzyx/articulorium"))
                .license(new License().name("See LICENSE").url("https://github.com/xyzzyzzyx/articulorium")))
            .addTagsItem(new Tag().name(TAG_LDP).description("Read, create, replace, patch and delete LDP resources"))
            .addTagsItem(new Tag().name(TAG_VERSIONS).description("Resource versions stored as OCFL object versions"))
            .addTagsItem(new Tag().name(TAG_REPOSITORY).description("Fedora API extensions: fixity and search"));
    }

    /**
     * Adds the LDP operations and the Fedora API extensions to the generated document.
     *
     * @return the customizer
     */
    @Bean
    public OpenApiCustomizer ldpOperationsCustomizer() {
        return OpenApiConfiguration::documentLdp;
    }

    /**
     * Documents the LDP operations and extensions on an OpenAPI document.
     *
     * @param openApi the document to complete
     */
    static void documentLdp(final OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        final Components components = openApi.getComponents();
        registerSchemas(components, ExtensionHandler.VersionList.class, HistoryService.FixityReport.class,
            SearchService.SearchResult.class);
        final Paths paths = openApi.getPaths() == null ? new Paths() : openApi.getPaths();
        paths.addPathItem("/", resourceItem(false));
        paths.addPathItem("/{path}", resourceItem(true));
        paths.addPathItem("/{path}/fcr:versions", versionsItem());
        paths.addPathItem("/{path}/fcr:versions/{versionId}", mementoItem());
        paths.addPathItem("/{path}/fcr:fixity", fixityItem());
        paths.addPathItem("/fcr:search", searchItem());
        openApi.setPaths(paths);
    }

    private static void registerSchemas(final Components components, final Class<?>... types) {
        for (final Class<?> type : types) {
            final Map<String, Schema> schemas = ModelConverters.getInstance().readAll(new AnnotatedType(type));
            schemas.forEach(components::addSchemas);
        }
    }

    // ------------------------------------------------------------------------------------------
    // LDP resources
    // ------------------------------------------------------------------------------------------

    private static PathItem resourceItem(final boolean nested) {
        final List<Parameter> path = nested ? List.of(pathParameter()) : List.of();
        final PathItem item = new PathItem();
        item.setGet(operation("get" + suffix(nested), "Retrieve a resource",
            "RDF sources and containers are content negotiated (Turtle by default, HTML for browsers); non-RDF "
                + "sources return their bytes (with Range support). An RDF-only `Accept` on a non-RDF source "
                + "redirects (303) to its description.", path)
            .addParametersItem(header("Accept", "Requested media type"))
            .addParametersItem(header("Prefer", "`return=representation; include=\"...\"` or `omit=\"...\"` with "
                + "ldp:PreferContainment, ldp:PreferMembership, ldp:PreferMinimalContainer"))
            .addParametersItem(header("If-None-Match", "Entity tag; 304 when it matches"))
            .responses(responses()
                .addApiResponse("200", rdfResponse("The representation").addHeaderObject("ETag", stringHeader())
                    .addHeaderObject("Link", stringHeader()))
                .addApiResponse("303", new ApiResponse().description("RDF requested for a non-RDF source: see its "
                    + "description"))
                .addApiResponse("304", new ApiResponse().description("Not modified"))
                .addApiResponse("404", plainResponse("Not found"))));
        item.setHead(operation("head" + suffix(nested), "Retrieve the headers of a resource", null, path)
            .responses(responses().addApiResponse("200", new ApiResponse().description("Headers only"))
                .addApiResponse("404", plainResponse("Not found"))));
        item.setOptions(operation("options" + suffix(nested), "Describe the supported operations",
            "Returns `Allow`, `Accept-Post` (containers) and `Accept-Patch` (RDF sources).", path)
            .responses(responses().addApiResponse("204", new ApiResponse().description("Supported operations"))));
        item.setPost(operation("create" + suffix(nested), "Create a resource in a container",
            "An RDF body creates an RDF source or container, any other body a non-RDF source, and a "
                + "`multipart/form-data` upload its `file` part. `Link: <ldp:...>; rel=\"type\"` requests an "
                + "interaction model.", path)
            .addParametersItem(header("Slug", "Suggested name of the new resource"))
            .addParametersItem(header("Link", "Requested interaction model, e.g. "
                + "`<http://www.w3.org/ns/ldp#BasicContainer>; rel=\"type\"`"))
            .requestBody(writeBody(true))
            .responses(responses()
                .addApiResponse("201", plainResponse("Created; the body and `Location` hold the new IRI")
                    .addHeaderObject("Location", stringHeader()))
                .addApiResponse("400", plainResponse("Malformed body"))
                .addApiResponse("405", plainResponse("The target is not a container"))
                .addApiResponse("409", plainResponse("Constraint violation; see `Link rel=constrainedBy`"))
                .addApiResponse("413", plainResponse("Body too large"))
                .addApiResponse("415", plainResponse("Unsupported media type"))));
        item.setPut(operation("replace" + suffix(nested), "Replace a resource, or create it at this path",
            "Replacing the bytes of a non-RDF source stores a new OCFL version.", path)
            .addParametersItem(header("If-Match", "Entity tag the resource must have"))
            .addParametersItem(header("Link", "Requested interaction model when creating"))
            .requestBody(writeBody(false))
            .responses(responses()
                .addApiResponse("201", plainResponse("Created"))
                .addApiResponse("204", new ApiResponse().description("Replaced"))
                .addApiResponse("409", plainResponse("Constraint violation"))
                .addApiResponse("412", plainResponse("Precondition failed"))));
        item.setPatch(operation("patch" + suffix(nested), "Apply a SPARQL Update to an RDF resource", null, path)
            .addParametersItem(header("If-Match", "Entity tag the resource must have"))
            .requestBody(new RequestBody().required(true).content(new Content()
                .addMediaType("application/sparql-update", new MediaType().schema(new StringSchema()
                    .example("PREFIX dcterms: <http://purl.org/dc/terms/>\nINSERT DATA { <> dcterms:title \"Title\" }")))))
            .responses(responses()
                .addApiResponse("204", new ApiResponse().description("Patched"))
                .addApiResponse("400", plainResponse("Invalid SPARQL Update"))
                .addApiResponse("409", plainResponse("The update touches server-managed triples"))
                .addApiResponse("412", plainResponse("Precondition failed"))));
        if (nested) {
            item.setDelete(operation("delete", "Delete a resource (recursively for containers)",
                "The resource's stored content and version history are purged.", path)
                .addParametersItem(header("If-Match", "Entity tag the resource must have"))
                .responses(responses()
                    .addApiResponse("204", new ApiResponse().description("Deleted"))
                    .addApiResponse("404", plainResponse("Not found"))
                    .addApiResponse("412", plainResponse("Precondition failed"))));
        }
        return item;
    }

    private static RequestBody writeBody(final boolean multipart) {
        final Content content = new Content();
        for (final String type : RDF_TYPES) {
            content.addMediaType(type, new MediaType().schema(new StringSchema()));
        }
        content.addMediaType("*/*", new MediaType().schema(new BinarySchema()));
        if (multipart) {
            content.addMediaType("multipart/form-data", new MediaType().schema(new Schema<>()
                .type("object")
                .addProperty("file", new BinarySchema())
                .addProperty("slug", new StringSchema())
                .addProperty("kind", new StringSchema()._enum(List.of("container")))));
        }
        return new RequestBody().content(content);
    }

    // ------------------------------------------------------------------------------------------
    // Extensions
    // ------------------------------------------------------------------------------------------

    private static PathItem versionsItem() {
        final PathItem item = new PathItem();
        item.setGet(tagged(new Operation(), TAG_VERSIONS)
            .operationId("listVersions")
            .summary("List the versions of a resource (Memento TimeMap)")
            .addParametersItem(pathParameter())
            .responses(responses().addApiResponse("200", new ApiResponse().description("The versions, oldest first")
                .content(new Content()
                    .addMediaType("application/json", jsonOf("VersionList"))
                    .addMediaType("application/link-format", new MediaType().schema(new StringSchema()))))));
        item.setPost(tagged(new Operation(), TAG_VERSIONS)
            .operationId("createVersion")
            .summary("Record the current state of a resource as a new version")
            .addParametersItem(pathParameter())
            .responses(responses()
                .addApiResponse("201", plainResponse("Created; `Location` is the memento IRI"))
                .addApiResponse("405", plainResponse("The root container is not versioned"))));
        return item;
    }

    private static PathItem mementoItem() {
        final PathItem item = new PathItem();
        item.setGet(tagged(new Operation(), TAG_VERSIONS)
            .operationId("getMemento")
            .summary("Retrieve a historic version of a resource")
            .description("RDF state at that version, or the bytes of a non-RDF source's version. The response "
                + "carries `Memento-Datetime`.")
            .addParametersItem(pathParameter())
            .addParametersItem(new PathParameter().name("versionId").description("Version identifier, e.g. `v2`")
                .schema(new StringSchema().pattern("v[0-9]+")))
            .responses(responses()
                .addApiResponse("200", rdfResponse("The memento").addHeaderObject("Memento-Datetime", stringHeader()))
                .addApiResponse("404", plainResponse("No such version"))));
        return item;
    }

    private static PathItem fixityItem() {
        final PathItem item = new PathItem();
        item.setGet(tagged(new Operation(), TAG_REPOSITORY)
            .operationId("checkFixity")
            .summary("Check the fixity of a non-RDF source")
            .description("Re-computes the size and SHA-256 digest of the stored bytes and validates the OCFL object.")
            .addParametersItem(pathParameter())
            .responses(responses()
                .addApiResponse("200", new ApiResponse().description("The fixity report")
                    .content(new Content().addMediaType("application/json", jsonOf("FixityReport"))))
                .addApiResponse("405", plainResponse("Not a non-RDF source"))));
        return item;
    }

    private static PathItem searchItem() {
        final PathItem item = new PathItem();
        item.setGet(tagged(new Operation(), TAG_REPOSITORY)
            .operationId("search")
            .summary("Search resources (Fedora search API)")
            .addParametersItem(new QueryParameter().name("condition")
                .description("`<field><op><value>`; fields fedora_id, created, modified, content_size, mime_type, "
                    + "rdf_type; operators =, <, <=, >, >=; `*` wildcards with `=`. Conditions are combined with AND.")
                .schema(new ArraySchema().items(new StringSchema().example("mime_type=image/*"))))
            .addParametersItem(new QueryParameter().name("max_results").schema(new IntegerSchema()._default(100)))
            .addParametersItem(new QueryParameter().name("offset").schema(new IntegerSchema()._default(0)))
            .addParametersItem(new QueryParameter().name("order_by").schema(new StringSchema()._enum(
                List.of("fedora_id", "created", "modified", "content_size", "mime_type"))))
            .addParametersItem(new QueryParameter().name("order").schema(new StringSchema()._enum(List.of("asc", "desc"))))
            .responses(responses()
                .addApiResponse("200", new ApiResponse().description("A page of results")
                    .content(new Content().addMediaType("application/json", jsonOf("SearchResult"))))
                .addApiResponse("400", plainResponse("Invalid condition"))));
        return item;
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private static String suffix(final boolean nested) {
        return nested ? "" : "Root";
    }

    private static Operation operation(final String id, final String summary, final String description,
            final List<Parameter> parameters) {
        final Operation operation = tagged(new Operation(), TAG_LDP).operationId(id).summary(summary)
            .description(description);
        parameters.forEach(operation::addParametersItem);
        return operation;
    }

    private static Operation tagged(final Operation operation, final String tag) {
        return operation.tags(List.of(tag));
    }

    private static Parameter pathParameter() {
        return new PathParameter().name("path").description(PATH_DESCRIPTION).schema(new StringSchema());
    }

    private static Parameter header(final String name, final String description) {
        return new HeaderParameter().name(name).description(description).required(false).schema(new StringSchema());
    }

    private static Header stringHeader() {
        return new Header().schema(new StringSchema());
    }

    private static ApiResponses responses() {
        return new ApiResponses();
    }

    private static ApiResponse plainResponse(final String description) {
        return new ApiResponse().description(description)
            .content(new Content().addMediaType("text/plain", new MediaType().schema(new StringSchema())));
    }

    private static ApiResponse rdfResponse(final String description) {
        final Content content = new Content();
        for (final String type : RDF_TYPES) {
            content.addMediaType(type, new MediaType().schema(new StringSchema()));
        }
        content.addMediaType("text/html", new MediaType().schema(new StringSchema()));
        content.addMediaType("*/*", new MediaType().schema(new BinarySchema()));
        return new ApiResponse().description(description).content(content).headers(new LinkedHashMap<>());
    }

    private static MediaType jsonOf(final String schemaName) {
        return new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + schemaName));
    }

}
