package com.articulorium.harvest.source;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Maps Dublin Core metadata (OAI-PMH {@code oai_dc}, DSpace {@code dc.*} and {@code dcterms.*}
 * fields) to DCMI Metadata Terms ({@code dcterms:}).
 *
 * <p>The fifteen elements map to the {@code dcterms:} property of the same name. Qualified DSpace
 * fields ({@code dc.<element>.<qualifier>}) map to the matching refinement when DCMI defines one
 * ({@code dc.date.issued} to {@code dcterms:issued}, {@code dc.description.abstract} to
 * {@code dcterms:abstract}) and to the element otherwise ({@code dc.contributor.advisor} to
 * {@code dcterms:contributor}).</p>
 */
public final class DublinCore {

    /** The DCMI Metadata Terms namespace. */
    public static final String DCTERMS = "http://purl.org/dc/terms/";

    /** The Dublin Core Metadata Element Set 1.1 namespace (OAI-PMH {@code oai_dc}). */
    public static final String DC_ELEMENTS = "http://purl.org/dc/elements/1.1/";

    /** The fifteen elements. */
    public static final Set<String> ELEMENTS = Set.of("contributor", "coverage", "creator", "date", "description",
        "format", "identifier", "language", "publisher", "relation", "rights", "source", "subject", "title", "type");

    /** Qualified DSpace fields with a DCMI refinement, as {@code element.qualifier}. */
    private static final Map<String, String> REFINEMENTS = Map.ofEntries(
        Map.entry("contributor.author", "creator"),
        Map.entry("title.alternative", "alternative"),
        Map.entry("date.issued", "issued"),
        Map.entry("date.created", "created"),
        Map.entry("date.available", "available"),
        Map.entry("date.accessioned", "dateSubmitted"),
        Map.entry("date.submitted", "dateSubmitted"),
        Map.entry("date.accepted", "dateAccepted"),
        Map.entry("date.copyright", "dateCopyrighted"),
        Map.entry("date.updated", "modified"),
        Map.entry("description.abstract", "abstract"),
        Map.entry("description.tableofcontents", "tableOfContents"),
        Map.entry("format.extent", "extent"),
        Map.entry("format.medium", "medium"),
        Map.entry("format.mimetype", "format"),
        Map.entry("coverage.spatial", "spatial"),
        Map.entry("coverage.temporal", "temporal"),
        Map.entry("relation.ispartof", "isPartOf"),
        Map.entry("relation.haspart", "hasPart"),
        Map.entry("relation.isversionof", "isVersionOf"),
        Map.entry("relation.hasversion", "hasVersion"),
        Map.entry("relation.isreferencedby", "isReferencedBy"),
        Map.entry("relation.references", "references"),
        Map.entry("relation.isreplacedby", "isReplacedBy"),
        Map.entry("relation.replaces", "replaces"),
        Map.entry("relation.requires", "requires"),
        Map.entry("relation.isformatof", "isFormatOf"),
        Map.entry("relation.hasformat", "hasFormat"),
        Map.entry("relation.conformsto", "conformsTo"),
        Map.entry("rights.uri", "license"),
        Map.entry("rights.license", "license"),
        Map.entry("rights.holder", "rightsHolder"),
        Map.entry("rights.accessrights", "accessRights"),
        Map.entry("identifier.citation", "bibliographicCitation"),
        Map.entry("audience.educationlevel", "educationLevel"),
        Map.entry("type.dcmitype", "type"));

    /** DCMI properties whose values are expected to be IRIs. */
    private static final Set<String> IRI_VALUED = Set.of("license", "conformsTo");

    private DublinCore() {
        // utility class
    }

    /**
     * Maps a DSpace field to a {@code dcterms:} property.
     *
     * @param schema    the metadata schema ({@code dc} or {@code dcterms})
     * @param element   the element
     * @param qualifier the qualifier, may be {@code null}
     * @return the property IRI, or empty for fields outside Dublin Core
     */
    public static Optional<String> property(final String schema, final String element, final String qualifier) {
        if (schema == null || element == null) {
            return Optional.empty();
        }
        final String lowerElement = element.toLowerCase(Locale.ROOT);
        if ("dcterms".equalsIgnoreCase(schema)) {
            // dcterms.<term> already names a DCMI term (case matters: dcterms.isPartOf)
            return qualifier == null && element.matches("[A-Za-z]+") ? Optional.of(DCTERMS + element) : Optional.empty();
        }
        if (!"dc".equalsIgnoreCase(schema)) {
            return Optional.empty();
        }
        if (qualifier != null && !qualifier.isBlank()) {
            final String refinement = REFINEMENTS.get(lowerElement + "." + qualifier.toLowerCase(Locale.ROOT));
            if (refinement != null) {
                return Optional.of(DCTERMS + refinement);
            }
        }
        return ELEMENTS.contains(lowerElement) ? Optional.of(DCTERMS + lowerElement) : Optional.empty();
    }

    /**
     * Maps an element of the Dublin Core Metadata Element Set ({@code oai_dc}) to a {@code dcterms:}
     * property.
     *
     * @param element the element's local name
     * @return the property IRI, or empty for an unknown element
     */
    public static Optional<String> element(final String element) {
        return element != null && ELEMENTS.contains(element) ? Optional.of(DCTERMS + element) : Optional.empty();
    }

    /**
     * Tells whether the values of a property are IRIs (when they look like one).
     *
     * @param property the property IRI
     * @return {@code true} for IRI-valued properties
     */
    public static boolean iriValued(final String property) {
        return property.startsWith(DCTERMS) && IRI_VALUED.contains(property.substring(DCTERMS.length()));
    }

    /**
     * Turns a language as repositories write it ({@code en_US}, {@code *}, empty) into a BCP 47 tag.
     *
     * @param language the language, may be {@code null}
     * @return the tag, or {@code ""} for none
     */
    public static String languageTag(final String language) {
        if (language == null) {
            return "";
        }
        final String tag = language.trim().replace('_', '-');
        return tag.matches("[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*") ? tag : "";
    }

}
