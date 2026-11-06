package com.articulorium.harvest.source;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Namespace-aware XML parsing hardened against external entities and DTDs (responses come from
 * other systems), and small DOM helpers.
 */
public final class Xml {

    private Xml() {
        // utility class
    }

    /**
     * Parses a document.
     *
     * @param bytes the document
     * @return the DOM
     * @throws SAXException when the bytes are not well-formed XML or declare a DTD
     */
    public static Document parse(final byte[] bytes) throws SAXException {
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            final DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("The XML parser cannot be configured securely", e);
        } catch (IOException e) {
            throw new SAXException(e);
        }
    }

    /**
     * Returns the element children of a node with a name.
     *
     * @param parent    the parent
     * @param namespace the namespace
     * @param localName the local name
     * @return the children, in document order
     */
    public static List<Element> children(final Node parent, final String namespace, final String localName) {
        final List<Element> children = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && localName.equals(element.getLocalName())
                    && namespace.equals(element.getNamespaceURI())) {
                children.add(element);
            }
        }
        return children;
    }

    /**
     * Returns the element children of a node.
     *
     * @param parent the parent
     * @return the children, in document order
     */
    public static List<Element> children(final Node parent) {
        final List<Element> children = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                children.add(element);
            }
        }
        return children;
    }

    /**
     * Returns the first element child with a name.
     *
     * @param parent    the parent
     * @param namespace the namespace
     * @param localName the local name
     * @return the child, or {@code null}
     */
    public static Element child(final Node parent, final String namespace, final String localName) {
        final List<Element> children = children(parent, namespace, localName);
        return children.isEmpty() ? null : children.get(0);
    }

    /**
     * Returns the trimmed text of the first element child with a name.
     *
     * @param parent    the parent
     * @param namespace the namespace
     * @param localName the local name
     * @return the text, or {@code null} when there is no such child or it is blank
     */
    public static String text(final Node parent, final String namespace, final String localName) {
        final Element child = child(parent, namespace, localName);
        if (child == null) {
            return null;
        }
        final String text = child.getTextContent().strip();
        return text.isEmpty() ? null : text;
    }

}
