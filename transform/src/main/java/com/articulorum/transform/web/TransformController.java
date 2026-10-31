package com.articulorum.transform.web;

import java.io.ByteArrayInputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.articulorum.transform.registry.TransformerRegistry;
import com.articulorum.transform.service.TransformService;
import com.articulorum.transform.spi.TransformationException;
import com.articulorum.transform.spi.Transformer;
import com.articulorum.transform.spi.UnknownTransformerException;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.RiotException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST API of the transform service: lists the registered transformers and previews what a
 * transformer makes of an RDF document, which helps writing and testing new transformations.
 *
 * <pre>
 *   GET  /transformers                              registered transformers
 *   GET  /transformers/{name}                       one transformer
 *   POST /transformers/{name}/preview?iri=&lt;IRI&gt;     transform the posted RDF (Turtle by default)
 * </pre>
 */
@RestController
@RequestMapping(path = "/transformers", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Transformers", description = "Transformations of LDP resources into search documents")
public class TransformController {

    private final TransformerRegistry registry;

    private final TransformService transformService;

    /**
     * Description of a registered transformer.
     *
     * @param name        the transformer name
     * @param description what it produces
     * @param active      whether it runs for every resource change
     */
    public record TransformerInfo(String name, String description, boolean active) {
    }

    /**
     * Creates the controller.
     *
     * @param registry         the transformer registry
     * @param transformService the transformation service
     */
    public TransformController(final TransformerRegistry registry, final TransformService transformService) {
        this.registry = registry;
        this.transformService = transformService;
    }

    /**
     * Lists the registered transformers.
     *
     * @return the transformers
     */
    @GetMapping
    @Operation(operationId = "listTransformers", summary = "List the registered transformers")
    public List<TransformerInfo> list() {
        return registry.all().stream().map(this::info).toList();
    }

    /**
     * Describes one transformer.
     *
     * @param name the transformer name
     * @return the description
     */
    @GetMapping("/{name}")
    @Operation(operationId = "getTransformer", summary = "Describe a transformer")
    public TransformerInfo get(@PathVariable final String name) {
        return info(registry.get(name));
    }

    /**
     * Transforms a posted RDF document with one transformer, without publishing anything.
     *
     * @param name        the transformer name
     * @param iri         the IRI of the resource the document describes (also the base for
     *                    relative IRIs)
     * @param contentType the RDF syntax of the body (Turtle when absent)
     * @param body        the RDF document
     * @return the search document
     */
    @PostMapping(path = "/{name}/preview", consumes = MediaType.ALL_VALUE)
    @Operation(operationId = "previewTransformation", summary = "Transform a posted RDF document")
    public Map<String, Collection<?>> preview(@PathVariable final String name,
            @Parameter(description = "IRI of the described resource") @RequestParam final String iri,
            @RequestHeader(name = HttpHeaders.CONTENT_TYPE, required = false) final String contentType,
            @RequestBody final byte[] body) {
        final Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, new ByteArrayInputStream(body), iri, langOf(contentType));
        return transformService.preview(name, iri, model);
    }

    /**
     * Chooses the RDF syntax of a request body.
     *
     * @param contentType the {@code Content-Type} header, may be {@code null}
     * @return the syntax; Turtle for absent or unknown types
     */
    static Lang langOf(final String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return Lang.TURTLE;
        }
        final Lang lang = RDFLanguages.contentTypeToLang(MediaType.parseMediaType(contentType).getType() + "/"
            + MediaType.parseMediaType(contentType).getSubtype());
        return lang == null ? Lang.TURTLE : lang;
    }

    private TransformerInfo info(final Transformer transformer) {
        return new TransformerInfo(transformer.name(), transformer.description(), registry.isActive(transformer));
    }

    /**
     * Maps unknown transformers to 404 and failed transformations to 422.
     *
     * @param exception the error
     * @return the problem description
     */
    @ExceptionHandler(TransformationException.class)
    public ResponseEntity<ProblemDetail> handleTransformation(final TransformationException exception) {
        final HttpStatus status = exception instanceof UnknownTransformerException
            ? HttpStatus.NOT_FOUND
            : HttpStatus.UNPROCESSABLE_CONTENT;
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, exception.getMessage()));
    }

    /**
     * Maps unparseable RDF to 400.
     *
     * @param exception the error
     * @return the problem description
     */
    @ExceptionHandler(RiotException.class)
    public ResponseEntity<ProblemDetail> handleRdf(final RiotException exception) {
        return ResponseEntity.badRequest()
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid RDF: " + exception.getMessage()));
    }

}
