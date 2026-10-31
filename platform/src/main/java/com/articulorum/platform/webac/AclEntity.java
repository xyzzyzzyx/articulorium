package com.articulorum.platform.webac;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A stored access control list: the N-Triples of {@code <resource>/fcr:acl}.
 *
 * <p>ACLs are kept apart from LDP resources so that they never appear in containment, search or
 * the triplestore, and so that deciding access needs no RDF parsing of the resources
 * themselves.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ldp_acls", indexes = @Index(name = "ldp_acls_path_idx", columnList = "path", unique = true))
public class AclEntity {

    /** Largest stored ACL in characters. */
    public static final int MAX_LENGTH = 1_048_576;

    @Id
    private UUID id;

    /** Path of the resource the ACL belongs to (empty for the root). */
    @Column(name = "path", nullable = false, unique = true, length = 1024)
    private String path;

    /** The ACL's triples as N-Triples with server-relative IRIs resolved against a fixed base. */
    @Column(name = "content", nullable = false, length = MAX_LENGTH)
    private String content;

    @Column(name = "created", nullable = false)
    private Instant created;

    @Column(name = "modified", nullable = false)
    private Instant modified;

    @Column(name = "modified_by", length = 256)
    private String modifiedBy;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long version;

    /**
     * Creates an ACL for a resource.
     *
     * @param path    the resource path
     * @param content the N-Triples
     * @param agent   who created it
     */
    public AclEntity(final String path, final String content, final String agent) {
        this.id = UUID.randomUUID();
        this.path = path;
        this.content = content;
        this.created = Instant.now();
        this.modified = created;
        this.modifiedBy = agent;
    }

}
