package com.articulorium.oai.web;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.articulorium.oai.metadata.DcValue;
import com.articulorium.oai.metadata.OaiDc;
import com.articulorium.oai.protocol.OaiArguments;
import com.articulorium.oai.protocol.OaiDates;
import com.articulorium.oai.service.OaiResponse;
import com.articulorium.oai.service.OaiResponse.Header;
import com.articulorium.oai.service.OaiResponse.OaiRecord;
import com.articulorium.oai.service.OaiResponse.Token;

import org.springframework.stereotype.Component;

/**
 * Serialises {@link OaiResponse}s as OAI-PMH 2.0 XML (section 3.2), valid against
 * {@code OAI-PMH.xsd} and, for records, {@code oai_dc.xsd}.
 */
@Component
public class OaiXmlWriter {

    /** The OAI-PMH namespace. */
    public static final String OAI = "http://www.openarchives.org/OAI/2.0/";

    /** The schema of OAI-PMH responses. */
    public static final String OAI_SCHEMA = "http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd";

    private static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";

    private static final String XML = "http://www.w3.org/XML/1998/namespace";

    private final XMLOutputFactory factory = XMLOutputFactory.newFactory();

    /**
     * Writes a response.
     *
     * @param response the response
     * @return the UTF-8 encoded document
     */
    public byte[] write(final OaiResponse response) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            final XMLStreamWriter xml = factory.createXMLStreamWriter(out, StandardCharsets.UTF_8.name());
            xml.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            xml.setDefaultNamespace(OAI);
            xml.setPrefix("xsi", XSI);
            xml.writeStartElement(OAI, "OAI-PMH");
            xml.writeDefaultNamespace(OAI);
            xml.writeNamespace("xsi", XSI);
            xml.writeAttribute(XSI, "schemaLocation", OAI + " " + OAI_SCHEMA);
            text(xml, "responseDate", OaiDates.format(response.responseDate()));
            request(xml, response);
            if (response.error() != null) {
                xml.writeStartElement(OAI, "error");
                xml.writeAttribute("code", response.error().code());
                characters(xml, response.errorMessage());
                xml.writeEndElement();
            } else {
                body(xml, response.body());
            }
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Cannot write an OAI-PMH response", e);
        }
        return out.toByteArray();
    }

    private static void request(final XMLStreamWriter xml, final OaiResponse response) throws XMLStreamException {
        xml.writeStartElement(OAI, "request");
        if (response.arguments() != null && response.verb() != null) {
            xml.writeAttribute(OaiArguments.VERB, response.verb().value());
            for (final Map.Entry<String, String> argument : response.arguments().entrySet()) {
                xml.writeAttribute(argument.getKey(), sanitize(argument.getValue()));
            }
        }
        characters(xml, response.baseUrl());
        xml.writeEndElement();
    }

    private static void body(final XMLStreamWriter xml, final OaiResponse.Body body) throws XMLStreamException {
        switch (body) {
            case OaiResponse.Identify identify -> {
                xml.writeStartElement(OAI, "Identify");
                text(xml, "repositoryName", identify.repositoryName());
                text(xml, "baseURL", identify.baseUrl());
                text(xml, "protocolVersion", "2.0");
                for (final String email : identify.adminEmails()) {
                    text(xml, "adminEmail", email);
                }
                text(xml, "earliestDatestamp", OaiDates.format(identify.earliestDatestamp()));
                text(xml, "deletedRecord", "no");
                text(xml, "granularity", OaiDates.GRANULARITY);
                xml.writeEndElement();
            }
            case OaiResponse.MetadataFormats formats -> {
                xml.writeStartElement(OAI, "ListMetadataFormats");
                for (final OaiResponse.MetadataFormat format : formats.formats()) {
                    xml.writeStartElement(OAI, "metadataFormat");
                    text(xml, "metadataPrefix", format.prefix());
                    text(xml, "schema", format.schema());
                    text(xml, "metadataNamespace", format.namespace());
                    xml.writeEndElement();
                }
                xml.writeEndElement();
            }
            case OaiResponse.Sets sets -> {
                xml.writeStartElement(OAI, "ListSets");
                for (final OaiResponse.SetEntry set : sets.sets()) {
                    xml.writeStartElement(OAI, "set");
                    text(xml, "setSpec", set.spec());
                    text(xml, "setName", set.name());
                    xml.writeEndElement();
                }
                token(xml, sets.token());
                xml.writeEndElement();
            }
            case OaiResponse.Record record -> {
                xml.writeStartElement(OAI, "GetRecord");
                record(xml, record.record());
                xml.writeEndElement();
            }
            case OaiResponse.Identifiers identifiers -> {
                xml.writeStartElement(OAI, "ListIdentifiers");
                for (final Header header : identifiers.headers()) {
                    header(xml, header);
                }
                token(xml, identifiers.token());
                xml.writeEndElement();
            }
            case OaiResponse.Records records -> {
                xml.writeStartElement(OAI, "ListRecords");
                for (final OaiRecord record : records.records()) {
                    record(xml, record);
                }
                token(xml, records.token());
                xml.writeEndElement();
            }
        }
    }

    private static void record(final XMLStreamWriter xml, final OaiRecord record) throws XMLStreamException {
        xml.writeStartElement(OAI, "record");
        header(xml, record.header());
        xml.writeStartElement(OAI, "metadata");
        xml.writeStartElement("oai_dc", "dc", OaiDc.NAMESPACE);
        xml.writeNamespace("oai_dc", OaiDc.NAMESPACE);
        xml.writeNamespace("dc", OaiDc.DC_NAMESPACE);
        xml.writeAttribute(XSI, "schemaLocation", OaiDc.NAMESPACE + " " + OaiDc.SCHEMA);
        for (final DcValue value : record.metadata()) {
            xml.writeStartElement("dc", value.element(), OaiDc.DC_NAMESPACE);
            if (value.language() != null) {
                xml.writeAttribute("xml", XML, "lang", sanitize(value.language()));
            }
            characters(xml, value.value());
            xml.writeEndElement();
        }
        xml.writeEndElement();
        xml.writeEndElement();
        xml.writeEndElement();
    }

    private static void header(final XMLStreamWriter xml, final Header header) throws XMLStreamException {
        xml.writeStartElement(OAI, "header");
        text(xml, "identifier", header.identifier());
        text(xml, "datestamp", OaiDates.format(header.datestamp()));
        for (final String spec : header.setSpecs()) {
            text(xml, "setSpec", spec);
        }
        xml.writeEndElement();
    }

    private static void token(final XMLStreamWriter xml, final Token token) throws XMLStreamException {
        if (token == null) {
            return;
        }
        xml.writeStartElement(OAI, "resumptionToken");
        xml.writeAttribute("cursor", Integer.toString(token.cursor()));
        characters(xml, token.value());
        xml.writeEndElement();
    }

    private static void text(final XMLStreamWriter xml, final String name, final String value)
            throws XMLStreamException {
        xml.writeStartElement(OAI, name);
        characters(xml, value);
        xml.writeEndElement();
    }

    private static void characters(final XMLStreamWriter xml, final String value) throws XMLStreamException {
        if (value != null && !value.isEmpty()) {
            xml.writeCharacters(sanitize(value));
        }
    }

    /**
     * Removes the characters XML 1.0 cannot represent (most control characters, unpaired
     * surrogates, U+FFFE and U+FFFF), which literals of an RDF description may contain.
     *
     * @param value the text
     * @return the text without them
     */
    static String sanitize(final String value) {
        final StringBuilder clean = new StringBuilder(value.length());
        value.codePoints().filter(OaiXmlWriter::isXmlChar).forEach(clean::appendCodePoint);
        return clean.toString();
    }

    private static boolean isXmlChar(final int c) {
        return c == 0x9 || c == 0xA || c == 0xD || c >= 0x20 && c <= 0xD7FF || c >= 0xE000 && c <= 0xFFFD
            || c >= 0x10000 && c <= 0x10FFFF;
    }

}
