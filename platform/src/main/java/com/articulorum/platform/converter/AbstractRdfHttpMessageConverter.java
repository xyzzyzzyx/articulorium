package com.articulorum.platform.converter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.articulorum.domain.Container;
import com.articulorum.platform.utility.ContainerUtility;

public abstract class AbstractRdfHttpMessageConverter extends AbstractHttpMessageConverter<Container> {

    public AbstractRdfHttpMessageConverter(MediaType mediaType) {
        super(mediaType);
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return Container.class.isAssignableFrom(clazz);
    }

    @Override
    protected Container readInternal(Class<? extends Container> clazz, HttpInputMessage inputMessage)
            throws IOException, HttpMessageNotReadableException {
        try (InputStream in = inputMessage.getBody()) {
            final String uri = ServletUriComponentsBuilder.fromCurrentServletMapping().build().toUriString();
            final Model model = ModelFactory.createDefaultModel();
            model.read(in, uri, getRdfType());
            return ContainerUtility.fromModel(model);
        }
    }

    @Override
    protected void writeInternal(Container container, HttpOutputMessage outputMessage)
            throws IOException, HttpMessageNotWritableException {
        Model model = ContainerUtility.toModel(container);
        try (OutputStreamWriter out = new OutputStreamWriter(outputMessage.getBody())) {
            model.write(out, getRdfType());
        }
    }

    public abstract String getRdfType();

}