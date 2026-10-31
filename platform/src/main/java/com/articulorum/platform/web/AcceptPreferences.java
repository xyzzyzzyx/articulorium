package com.articulorum.platform.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.articulorum.platform.rdf.RdfMediaTypes;

import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

/**
 * Decides whether a client prefers the HTML view of a resource over its RDF representation.
 */
public final class AcceptPreferences {

    private AcceptPreferences() {
        // utility class
    }

    /**
     * Tells whether the {@code Accept} header ranks HTML above every RDF media type.
     *
     * <p>Browsers send {@code text/html} first and therefore get the HTML view; RDF clients,
     * {@code curl} ({@code *}{@code /*}) and clients sending no header get Turtle.</p>
     *
     * @param accept the {@code Accept} header, may be {@code null}
     * @return {@code true} when HTML is preferred
     */
    public static boolean prefersHtml(final String accept) {
        if (accept == null || accept.isBlank()) {
            return false;
        }
        final List<MediaType> types;
        try {
            types = new ArrayList<>(MediaType.parseMediaTypes(accept));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
        // stable sort: equal qualities keep the client's order
        types.sort(Comparator.comparingDouble(MediaType::getQualityValue).reversed());
        final List<MediaType> rdfTypes = RdfMediaTypes.writableTypes();
        for (final MediaType type : types) {
            if (type.getQualityValue() <= 0) {
                continue;
            }
            if (type.equalsTypeAndSubtype(MediaType.TEXT_HTML) || type.equalsTypeAndSubtype(MediaType.APPLICATION_XHTML_XML)) {
                return true;
            }
            if (type.isWildcardType() || rdfTypes.stream().anyMatch(type::isCompatibleWith)) {
                return false;
            }
        }
        return false;
    }

    /**
     * Tells whether a client asking for a non-RDF source would rather have its RDF description:
     * the {@code Accept} header admits no media type compatible with the binary but admits an
     * RDF syntax. Linked Data clients (which send only RDF types) are then redirected to the
     * description, while browsers and generic clients ({@code *}{@code /*}) get the bytes.
     *
     * @param accept     the {@code Accept} header, may be {@code null}
     * @param binaryType the media type of the stored binary
     * @return {@code true} when the description should be served instead
     */
    public static boolean prefersDescription(final String accept, final MediaType binaryType) {
        if (accept == null || accept.isBlank()) {
            return false;
        }
        final List<MediaType> types;
        try {
            types = MediaType.parseMediaTypes(accept);
        } catch (InvalidMediaTypeException e) {
            return false;
        }
        final List<MediaType> rdfTypes = RdfMediaTypes.writableTypes();
        boolean rdfAcceptable = false;
        for (final MediaType type : types) {
            if (type.getQualityValue() <= 0) {
                continue;
            }
            if (type.isCompatibleWith(binaryType)) {
                return false;
            }
            if (rdfTypes.stream().anyMatch(type::isCompatibleWith)) {
                rdfAcceptable = true;
            }
        }
        return rdfAcceptable;
    }

}
