package com.articulorum.platform.namespace;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * A namespace prefix known to the platform, used to produce compact RDF serialisations.
 */
@Entity
@Table(name = "namespace_prefixes", indexes = {
    @Index(name = "namespace_prefixes_prefix_idx", columnList = "prefix", unique = true)
})
public class NamespacePrefix {

    @Id
    private UUID id;

    @Column(name = "prefix", nullable = false, unique = true, length = 64)
    private String prefix;

    @Column(name = "namespace", nullable = false, unique = true, length = 1024)
    private String namespace;

    @Column(name = "description", length = 1024)
    private String description;

    /** Creates an empty prefix; required by JPA. */
    protected NamespacePrefix() {
        // for JPA
    }

    /**
     * Creates a namespace prefix.
     *
     * @param prefix      the prefix, for example {@code dcterms}
     * @param namespace   the namespace IRI
     * @param description a human readable description, may be {@code null}
     */
    public NamespacePrefix(final String prefix, final String namespace, final String description) {
        this.id = UUID.randomUUID();
        this.prefix = prefix;
        this.namespace = namespace;
        this.description = description;
    }

    /** @return the identifier */
    public UUID getId() {
        return id;
    }

    /** @return the prefix */
    public String getPrefix() {
        return prefix;
    }

    /** @return the namespace IRI */
    public String getNamespace() {
        return namespace;
    }

    /** @return the description, or {@code null} */
    public String getDescription() {
        return description;
    }

}
