package com.articulorum.platform.rdf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RiotException;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

/**
 * Reads and writes Apache Jena {@link Model}s in every RDF syntax listed by {@link RdfMediaTypes}.
 *
 * <p>Registered ahead of Spring's default converters so content negotiation for RDF responses
 * always resolves here; Turtle is produced when the client accepts anything.</p>
 */
public class RdfModelHttpMessageConverter extends AbstractHttpMessageConverter<Model> {

    /** Base IRI used when a body is parsed without request context. */
    static final String DEFAULT_BASE = "urn:articulorum:base/";

    /** Creates the converter supporting every writable RDF media type. */
    public RdfModelHttpMessageConverter() {
        super(StandardCharsets.UTF_8, RdfMediaTypes.writableTypes().toArray(new MediaType[0]));
    }

    @Override
    protected boolean supports(final Class<?> clazz) {
        return Model.class.isAssignableFrom(clazz);
    }

    @Override
    protected Model readInternal(final Class<? extends Model> clazz, final HttpInputMessage inputMessage)
            throws IOException {
        final MediaType contentType = inputMessage.getHeaders().getContentType();
        final Lang lang = RdfMediaTypes.readableLang(contentType)
            .orElseThrow(() -> new HttpMessageNotReadableException("Not an RDF media type: " + contentType, inputMessage));
        final Model model = ModelFactory.createDefaultModel();
        try (InputStream in = inputMessage.getBody()) {
            RDFDataMgr.read(model, in, DEFAULT_BASE, lang);
        } catch (RiotException e) {
            throw new HttpMessageNotReadableException("Invalid RDF: " + e.getMessage(), e, inputMessage);
        }
        return model;
    }

    @Override
    protected void writeInternal(final Model model, final HttpOutputMessage outputMessage) throws IOException {
        final MediaType contentType = outputMessage.getHeaders().getContentType();
        final Lang lang = RdfMediaTypes.writableLang(contentType).orElse(Lang.TURTLE);
        try {
            RDFDataMgr.write(outputMessage.getBody(), model, lang);
        } catch (RiotException e) {
            throw new HttpMessageNotWritableException("Unable to write RDF as " + lang.getName(), e);
        }
    }

}
