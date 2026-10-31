package com.articulorum.platform.rdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.http.MockHttpOutputMessage;

/**
 * Unit tests for {@link RdfModelHttpMessageConverter}.
 */
class RdfModelHttpMessageConverterTest {

    private final RdfModelHttpMessageConverter converter = new RdfModelHttpMessageConverter();

    private Model sample() {
        final Model model = ModelFactory.createDefaultModel();
        model.createResource("http://localhost/a").addProperty(model.createProperty("http://purl.org/dc/terms/title"), "A");
        return model;
    }

    @Test
    void supportsModelsOnly() {
        assertThat(converter.canWrite(Model.class, RdfMediaTypes.TEXT_TURTLE)).isTrue();
        assertThat(converter.canWrite(String.class, RdfMediaTypes.TEXT_TURTLE)).isFalse();
        assertThat(converter.canWrite(Model.class, MediaType.IMAGE_PNG)).isFalse();
    }

    @Test
    void writesEverySupportedSyntaxReadably() throws Exception {
        for (final MediaType type : RdfMediaTypes.writableTypes()) {
            final MockHttpOutputMessage output = new MockHttpOutputMessage();
            converter.write(sample(), type, output);
            final MockHttpInputMessage input = new MockHttpInputMessage(output.getBodyAsBytes());
            final MediaType readType = MediaType.APPLICATION_JSON.equalsTypeAndSubtype(type) ? RdfMediaTypes.APPLICATION_LD_JSON : type;
            input.getHeaders().setContentType(readType);
            final Model read = converter.read(Model.class, input);
            assertThat(read.isIsomorphicWith(sample())).as("round trip through %s", type).isTrue();
        }
    }

    @Test
    void addsUtf8Charset() throws Exception {
        final MockHttpOutputMessage output = new MockHttpOutputMessage();
        converter.write(sample(), RdfMediaTypes.TEXT_TURTLE, output);
        assertThat(output.getHeaders().getContentType().getCharset()).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void rejectsInvalidRdf() {
        final MockHttpInputMessage input = new MockHttpInputMessage("<a> <b> ".getBytes(StandardCharsets.UTF_8));
        input.getHeaders().setContentType(RdfMediaTypes.TEXT_TURTLE);
        assertThatThrownBy(() -> converter.read(Model.class, input)).isInstanceOf(HttpMessageNotReadableException.class);
    }

}
