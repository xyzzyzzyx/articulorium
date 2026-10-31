package com.articulorum.platform.rdf;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.jena.riot.Lang;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Unit tests for {@link RdfMediaTypes}.
 */
class RdfMediaTypesTest {

    @Test
    void turtleIsTheDefaultWritableType() {
        assertThat(RdfMediaTypes.writableTypes().get(0)).isEqualTo(RdfMediaTypes.TEXT_TURTLE);
    }

    @Test
    void resolvesReadableLanguagesIgnoringParameters() {
        assertThat(RdfMediaTypes.readableLang(MediaType.parseMediaType("text/turtle; charset=UTF-8"))).contains(Lang.TURTLE);
        assertThat(RdfMediaTypes.readableLang(RdfMediaTypes.APPLICATION_LD_JSON)).contains(Lang.JSONLD);
        assertThat(RdfMediaTypes.readableLang(RdfMediaTypes.APPLICATION_RDF_XML)).contains(Lang.RDFXML);
        assertThat(RdfMediaTypes.readableLang(null)).isEmpty();
    }

    @Test
    void plainJsonIsWritableButNotReadAsRdf() {
        assertThat(RdfMediaTypes.isRdf(MediaType.APPLICATION_JSON)).isFalse();
        assertThat(RdfMediaTypes.writableLang(MediaType.APPLICATION_JSON)).contains(Lang.JSONLD);
    }

    @Test
    void binaryTypesAreNotRdf() {
        assertThat(RdfMediaTypes.isRdf(MediaType.IMAGE_PNG)).isFalse();
        assertThat(RdfMediaTypes.isRdf(MediaType.TEXT_PLAIN)).isFalse();
    }

    @Test
    void headerValueListsReadableTypes() {
        assertThat(RdfMediaTypes.readableTypesHeaderValue()).startsWith("text/turtle").contains("application/ld+json");
    }

}
