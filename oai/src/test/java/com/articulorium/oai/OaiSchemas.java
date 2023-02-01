package com.articulorium.oai;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URL;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.SAXException;

/**
 * Validates documents against the official OAI-PMH 2.0 and oai_dc schemas.
 *
 * <p>The schemas are test resources ({@code src/test/resources/xsd}): {@code OAI-PMH.xsd} and
 * {@code oai_dc.xsd} as published at {@code http://www.openarchives.org/OAI/2.0/}, the DCMI's
 * {@code simpledc20021212.xsd} and the W3C's {@code xml.xsd}. Their remote imports are resolved to
 * those copies, so validation works offline. The envelope schema processes {@code metadata}
 * strictly, so every oai_dc record is validated too.</p>
 */
public final class OaiSchemas {

    private static final Schema SCHEMA = load();

    private OaiSchemas() {
        // utility class
    }

    /**
     * Validates a document.
     *
     * @param document the XML
     * @throws AssertionError when the document is not valid
     */
    public static void validate(final byte[] document) {
        try {
            SCHEMA.newValidator().validate(new StreamSource(new ByteArrayInputStream(document)));
        } catch (SAXException | IOException e) {
            throw new AssertionError("Not a valid OAI-PMH response: " + e.getMessage() + "\n"
                + new String(document, java.nio.charset.StandardCharsets.UTF_8), e);
        }
    }

    private static Schema load() {
        try {
            final SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setResourceResolver(new LocalResolver());
            return factory.newSchema(new StreamSource[] {source("OAI-PMH.xsd"), source("oai_dc.xsd")});
        } catch (SAXException e) {
            throw new IllegalStateException("Cannot load the OAI-PMH schemas", e);
        }
    }

    private static StreamSource source(final String name) {
        final URL url = OaiSchemas.class.getResource("/xsd/" + name);
        return new StreamSource(url.toExternalForm());
    }

    /** Resolves remote schema locations to the local copies, by file name. */
    private static final class LocalResolver implements LSResourceResolver {

        @Override
        public LSInput resolveResource(final String type, final String namespace, final String publicId,
                final String systemId, final String baseUri) {
            if (systemId == null) {
                return null;
            }
            final String name = systemId.substring(systemId.lastIndexOf('/') + 1);
            final URL url = OaiSchemas.class.getResource("/xsd/" + name);
            if (url == null) {
                throw new IllegalStateException("No local copy of schema " + systemId);
            }
            return new Input(publicId, url.toExternalForm(), baseUri);
        }

    }

    /** A schema document read from the class path. */
    private record Input(String publicId, String systemId, String baseUri) implements LSInput {

        @Override
        public Reader getCharacterStream() {
            return null;
        }

        @Override
        public void setCharacterStream(final Reader characterStream) {
            // read-only
        }

        @Override
        public InputStream getByteStream() {
            try {
                return java.net.URI.create(systemId).toURL().openStream();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void setByteStream(final InputStream byteStream) {
            // read-only
        }

        @Override
        public String getStringData() {
            return null;
        }

        @Override
        public void setStringData(final String stringData) {
            // read-only
        }

        @Override
        public String getSystemId() {
            return systemId;
        }

        @Override
        public void setSystemId(final String systemId) {
            // read-only
        }

        @Override
        public String getPublicId() {
            return publicId;
        }

        @Override
        public void setPublicId(final String publicId) {
            // read-only
        }

        @Override
        public String getBaseURI() {
            return baseUri;
        }

        @Override
        public void setBaseURI(final String baseUri) {
            // read-only
        }

        @Override
        public String getEncoding() {
            return null;
        }

        @Override
        public void setEncoding(final String encoding) {
            // read-only
        }

        @Override
        public boolean getCertifiedText() {
            return false;
        }

        @Override
        public void setCertifiedText(final boolean certifiedText) {
            // read-only
        }

    }

}
