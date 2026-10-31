package com.articulorum.domain.vocabulary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Fedora}.
 */
class FedoraTest {

    @Test
    void termsAreInTheFedoraNamespace() {
        assertEquals("http://fedora.info/definitions/v4/repository#created", Fedora.CREATED);
        assertEquals("http://fedora.info/definitions/v4/repository#Binary", Fedora.BINARY);
        assertEquals("http://www.w3.org/2001/XMLSchema#dateTime", Fedora.XSD_DATE_TIME);
    }

    @Test
    void recognisesFedoraTerms() {
        assertTrue(Fedora.isFedoraTerm(Fedora.LAST_MODIFIED_BY));
        assertTrue(Fedora.isFedoraTerm(Fedora.CONTAINER));
        assertFalse(Fedora.isFedoraTerm(Ldp.CONTAINER));
        assertFalse(Fedora.isFedoraTerm(null));
    }

}
