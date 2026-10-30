package com.articulorum.domain;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * A single RDF statement persisted as part of an {@link LdpResource}.
 *
 * <p>Terms are stored as strings so the model stays independent of any RDF library:</p>
 * <ul>
 *   <li>The <b>subject</b> is an IRI or, when it starts with {@value #BLANK_NODE_PREFIX}, a blank
 *       node label.</li>
 *   <li>The <b>predicate</b> is always an absolute IRI.</li>
 *   <li>The <b>object</b> is interpreted according to {@link #getObjectKind()}; literals carry an
 *       optional datatype IRI and language tag (empty strings when absent).</li>
 * </ul>
 *
 * <p>IRIs that identify resources hosted by this server are stored <em>relative</em> to the
 * server base and therefore start with {@code /} (see {@link com.articulorum.domain.rdf.Iris}).
 * This keeps the stored graph valid no matter which host name, port or gateway prefix a client
 * used to reach the platform.</p>
 *
 * <p>Instances are value objects: equality covers every field.</p>
 */
@Embeddable
public class Triple {

    /** Prefix that marks a blank node label in the subject position. */
    public static final String BLANK_NODE_PREFIX = "_:";

    @Column(name = "triple_subject", nullable = false, length = 2048)
    private String subject;

    @Column(name = "triple_predicate", nullable = false, length = 2048)
    private String predicate;

    @Column(name = "triple_object", nullable = false, length = 16384)
    private String object;

    @Enumerated(EnumType.STRING)
    @Column(name = "object_kind", nullable = false, length = 16)
    private TermKind objectKind;

    @Column(name = "object_datatype", nullable = false, length = 2048)
    private String datatype = "";

    @Column(name = "object_language", nullable = false, length = 64)
    private String language = "";

    /**
     * Creates an empty triple. Required by JPA and Jackson; application code should use the
     * static factory methods instead.
     */
    public Triple() {
        // for JPA and Jackson
    }

    /**
     * Creates a triple.
     *
     * @param subject    the subject IRI or {@code _:label}
     * @param predicate  the predicate IRI
     * @param object     the object IRI, blank node label or literal lexical form
     * @param objectKind the kind of the object term
     * @param datatype   the literal datatype IRI, or {@code null}/empty
     * @param language   the literal language tag, or {@code null}/empty
     */
    public Triple(final String subject, final String predicate, final String object, final TermKind objectKind,
            final String datatype, final String language) {
        this.subject = Objects.requireNonNull(subject, "subject");
        this.predicate = Objects.requireNonNull(predicate, "predicate");
        this.object = Objects.requireNonNull(object, "object");
        this.objectKind = Objects.requireNonNull(objectKind, "objectKind");
        this.datatype = datatype == null ? "" : datatype;
        this.language = language == null ? "" : language;
    }

    /**
     * Creates a triple whose object is an IRI.
     *
     * @param subject   the subject
     * @param predicate the predicate IRI
     * @param iri       the object IRI (absolute or server-relative)
     * @return the triple
     */
    public static Triple iri(final String subject, final String predicate, final String iri) {
        return new Triple(subject, predicate, iri, TermKind.IRI, null, null);
    }

    /**
     * Creates a triple whose object is a blank node.
     *
     * @param subject   the subject
     * @param predicate the predicate IRI
     * @param label     the blank node label, without {@code _:}
     * @return the triple
     */
    public static Triple blankNode(final String subject, final String predicate, final String label) {
        return new Triple(subject, predicate, label, TermKind.BLANK_NODE, null, null);
    }

    /**
     * Creates a triple whose object is a plain ({@code xsd:string}) literal.
     *
     * @param subject   the subject
     * @param predicate the predicate IRI
     * @param lexical   the literal value
     * @return the triple
     */
    public static Triple literal(final String subject, final String predicate, final String lexical) {
        return new Triple(subject, predicate, lexical, TermKind.LITERAL, null, null);
    }

    /**
     * Creates a triple whose object is a typed or language-tagged literal.
     *
     * @param subject   the subject
     * @param predicate the predicate IRI
     * @param lexical   the literal lexical form
     * @param datatype  the datatype IRI, or {@code null} for {@code xsd:string}
     * @param language  the language tag, or {@code null}
     * @return the triple
     */
    public static Triple literal(final String subject, final String predicate, final String lexical,
            final String datatype, final String language) {
        return new Triple(subject, predicate, lexical, TermKind.LITERAL, datatype, language);
    }

    /** @return the subject IRI or {@code _:label} */
    public String getSubject() {
        return subject;
    }

    /** @param subject the subject IRI or {@code _:label} */
    public void setSubject(final String subject) {
        this.subject = subject;
    }

    /** @return the predicate IRI */
    public String getPredicate() {
        return predicate;
    }

    /** @param predicate the predicate IRI */
    public void setPredicate(final String predicate) {
        this.predicate = predicate;
    }

    /** @return the object IRI, blank node label or literal lexical form */
    public String getObject() {
        return object;
    }

    /** @param object the object IRI, blank node label or literal lexical form */
    public void setObject(final String object) {
        this.object = object;
    }

    /** @return the kind of the object term */
    public TermKind getObjectKind() {
        return objectKind;
    }

    /** @param objectKind the kind of the object term */
    public void setObjectKind(final TermKind objectKind) {
        this.objectKind = objectKind;
    }

    /** @return the literal datatype IRI, or an empty string */
    public String getDatatype() {
        return datatype;
    }

    /** @param datatype the literal datatype IRI; {@code null} is stored as empty */
    public void setDatatype(final String datatype) {
        this.datatype = datatype == null ? "" : datatype;
    }

    /** @return the literal language tag, or an empty string */
    public String getLanguage() {
        return language;
    }

    /** @param language the literal language tag; {@code null} is stored as empty */
    public void setLanguage(final String language) {
        this.language = language == null ? "" : language;
    }

    /**
     * Tells whether the subject is a blank node.
     *
     * @return {@code true} when the subject starts with {@value #BLANK_NODE_PREFIX}
     */
    @JsonIgnore
    public boolean isBlankSubject() {
        return subject != null && subject.startsWith(BLANK_NODE_PREFIX);
    }

    /**
     * Tells whether this triple has the given subject and predicate.
     *
     * @param expectedSubject   the subject to compare
     * @param expectedPredicate the predicate to compare
     * @return {@code true} when both match
     */
    public boolean matches(final String expectedSubject, final String expectedPredicate) {
        return Objects.equals(subject, expectedSubject) && Objects.equals(predicate, expectedPredicate);
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Triple)) {
            return false;
        }
        final Triple that = (Triple) other;
        return Objects.equals(subject, that.subject)
            && Objects.equals(predicate, that.predicate)
            && Objects.equals(object, that.object)
            && objectKind == that.objectKind
            && Objects.equals(datatype, that.datatype)
            && Objects.equals(language, that.language);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subject, predicate, object, objectKind, datatype, language);
    }

    @Override
    public String toString() {
        return "Triple[" + subject + " " + predicate + " " + objectKind + ":" + object
            + (datatype.isEmpty() ? "" : "^^" + datatype)
            + (language.isEmpty() ? "" : "@" + language) + "]";
    }

}
