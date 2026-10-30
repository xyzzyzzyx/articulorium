package com.articulorum.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.articulorum.domain.rdf.ResourcePaths;
import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A resource managed by the Linked Data Platform.
 *
 * <p>Every LDP resource (RDF source, container or non-RDF source) is stored as one row. The row
 * keeps:</p>
 * <ul>
 *   <li>its <b>path</b> relative to the server root, without leading or trailing slash (the root
 *       container has the empty path);</li>
 *   <li>its <b>interaction model</b>;</li>
 *   <li>the client-managed <b>triples</b> of its RDF state (for a non-RDF source: its
 *       description);</li>
 *   <li>for direct and indirect containers, the <b>membership configuration</b> extracted from
 *       those triples so membership can be queried efficiently;</li>
 *   <li>for non-RDF sources, the <b>binary</b> metadata;</li>
 *   <li>who created and last modified it, and whether historic <b>versions</b> exist in the
 *       OCFL storage layer.</li>
 * </ul>
 *
 * <p>Server-managed triples ({@code ldp:contains}, membership triples and LDP {@code rdf:type}s)
 * are <em>not</em> stored; they are computed when the resource is rendered so they can never go
 * stale.</p>
 *
 * <p>The {@link #getVersion() version} is maintained by JPA optimistic locking and is the basis
 * of the resource's entity tag.</p>
 */
@Entity
@Table(name = "ldp_resources", indexes = {
    @Index(name = "ldp_resources_path_idx", columnList = "path", unique = true),
    @Index(name = "ldp_resources_parent_idx", columnList = "parent_path"),
    @Index(name = "ldp_resources_membership_idx", columnList = "membership_resource")
})
public class LdpResource {

    @Id
    private UUID id;

    @Column(name = "path", nullable = false, unique = true, length = 1024)
    private String path;

    @Column(name = "parent_path", length = 1024)
    private String parentPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "interaction_model", nullable = false, length = 32)
    private InteractionModel interactionModel;

    @Column(name = "membership_resource", length = 2048)
    private String membershipResource;

    @Column(name = "has_member_relation", length = 2048)
    private String hasMemberRelation;

    @Column(name = "is_member_of_relation", length = 2048)
    private String isMemberOfRelation;

    @Column(name = "inserted_content_relation", length = 2048)
    private String insertedContentRelation;

    @Column(name = "created", nullable = false)
    private Instant created;

    @Column(name = "modified", nullable = false)
    private Instant modified;

    @Column(name = "created_by", length = 256)
    private String createdBy;

    @Column(name = "last_modified_by", length = 256)
    private String lastModifiedBy;

    /**
     * Whether the resource has an OCFL object holding historic versions. Non-RDF sources always
     * have one (their bytes live in it); RDF sources get one when the first version is created.
     */
    @Column(name = "versioned", nullable = false)
    private boolean versioned;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long version;

    @Embedded
    private BinaryContent binary;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ldp_triples", joinColumns = @JoinColumn(name = "resource_id"))
    @OrderColumn(name = "triple_index")
    private List<Triple> triples = new ArrayList<>();

    /** Creates an empty resource; required by JPA and Jackson. */
    public LdpResource() {
        // for JPA and Jackson
    }

    /**
     * Creates a new resource with a random identifier and the current time as creation time.
     *
     * @param path             the normalised resource path (empty for the root container)
     * @param interactionModel the interaction model
     */
    public LdpResource(final String path, final InteractionModel interactionModel) {
        this.id = UUID.randomUUID();
        setPath(path);
        this.interactionModel = Objects.requireNonNull(interactionModel, "interactionModel");
        this.created = Instant.now();
        this.modified = this.created;
    }

    /** @return the resource identifier */
    public UUID getId() {
        return id;
    }

    /** @param id the resource identifier */
    public void setId(final UUID id) {
        this.id = id;
    }

    /** @return the normalised path; empty for the root container */
    public String getPath() {
        return path;
    }

    /**
     * Sets the path and derives the parent path from it.
     *
     * @param path the resource path; it is normalised
     */
    public void setPath(final String path) {
        this.path = ResourcePaths.normalize(path);
        this.parentPath = ResourcePaths.parentOf(this.path).orElse(null);
    }

    /** @return the parent path, or {@code null} for the root container */
    public String getParentPath() {
        return parentPath;
    }

    /** @param parentPath the parent path (normally derived by {@link #setPath(String)}) */
    public void setParentPath(final String parentPath) {
        this.parentPath = parentPath;
    }

    /** @return the interaction model */
    public InteractionModel getInteractionModel() {
        return interactionModel;
    }

    /** @param interactionModel the interaction model */
    public void setInteractionModel(final InteractionModel interactionModel) {
        this.interactionModel = interactionModel;
    }

    /** @return the membership resource IRI (server-relative or absolute), or {@code null} */
    public String getMembershipResource() {
        return membershipResource;
    }

    /** @param membershipResource the membership resource IRI */
    public void setMembershipResource(final String membershipResource) {
        this.membershipResource = membershipResource;
    }

    /** @return the {@code ldp:hasMemberRelation} predicate IRI, or {@code null} */
    public String getHasMemberRelation() {
        return hasMemberRelation;
    }

    /** @param hasMemberRelation the {@code ldp:hasMemberRelation} predicate IRI */
    public void setHasMemberRelation(final String hasMemberRelation) {
        this.hasMemberRelation = hasMemberRelation;
    }

    /** @return the {@code ldp:isMemberOfRelation} predicate IRI, or {@code null} */
    public String getIsMemberOfRelation() {
        return isMemberOfRelation;
    }

    /** @param isMemberOfRelation the {@code ldp:isMemberOfRelation} predicate IRI */
    public void setIsMemberOfRelation(final String isMemberOfRelation) {
        this.isMemberOfRelation = isMemberOfRelation;
    }

    /** @return the {@code ldp:insertedContentRelation} predicate IRI, or {@code null} */
    public String getInsertedContentRelation() {
        return insertedContentRelation;
    }

    /** @param insertedContentRelation the {@code ldp:insertedContentRelation} predicate IRI */
    public void setInsertedContentRelation(final String insertedContentRelation) {
        this.insertedContentRelation = insertedContentRelation;
    }

    /** @return the creation instant */
    public Instant getCreated() {
        return created;
    }

    /** @param created the creation instant */
    public void setCreated(final Instant created) {
        this.created = created;
    }

    /** @return the last modification instant */
    public Instant getModified() {
        return modified;
    }

    /** @param modified the last modification instant */
    public void setModified(final Instant modified) {
        this.modified = modified;
    }

    /** @return the agent that created the resource, or {@code null} when unknown */
    public String getCreatedBy() {
        return createdBy;
    }

    /** @param createdBy the agent that created the resource */
    public void setCreatedBy(final String createdBy) {
        this.createdBy = createdBy;
    }

    /** @return the agent that last modified the resource, or {@code null} when unknown */
    public String getLastModifiedBy() {
        return lastModifiedBy;
    }

    /** @param lastModifiedBy the agent that last modified the resource */
    public void setLastModifiedBy(final String lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }

    /** @return {@code true} when an OCFL object with historic versions exists for the resource */
    public boolean isVersioned() {
        return versioned;
    }

    /** @param versioned whether an OCFL object with historic versions exists for the resource */
    public void setVersioned(final boolean versioned) {
        this.versioned = versioned;
    }

    /** @return the optimistic-locking version, or {@code null} before the first flush */
    public Long getVersion() {
        return version;
    }

    /** @param version the optimistic-locking version */
    public void setVersion(final Long version) {
        this.version = version;
    }

    /** @return the binary metadata of a non-RDF source, or {@code null} */
    public BinaryContent getBinary() {
        return binary;
    }

    /** @param binary the binary metadata */
    public void setBinary(final BinaryContent binary) {
        this.binary = binary;
    }

    /** @return the mutable list of client-managed triples */
    public List<Triple> getTriples() {
        return triples;
    }

    /**
     * Replaces the client-managed triples. Duplicates are removed while preserving order, since an
     * RDF graph is a set.
     *
     * @param triples the new triples
     */
    public void setTriples(final Collection<Triple> triples) {
        final List<Triple> distinct = new ArrayList<>(new LinkedHashSet<>(triples));
        if (this.triples == null) {
            this.triples = distinct;
        } else {
            this.triples.clear();
            this.triples.addAll(distinct);
        }
    }

    /**
     * Records a modification: sets the modification time to now. Because the entity changes, JPA
     * increments the version on the next flush, which in turn changes the entity tag.
     */
    public void touch() {
        final Instant now = Instant.now();
        // guarantee a visible change even when two touches fall into the same clock tick
        this.modified = modified != null && !now.isAfter(modified) ? modified.plusNanos(1000) : now;
    }

    /**
     * Records a modification made by an agent: like {@link #touch()}, and remembers the agent as
     * the last modifier.
     *
     * @param agent the agent making the change; {@code null} keeps the previous last modifier
     */
    public void touch(final String agent) {
        touch();
        if (agent != null) {
            this.lastModifiedBy = agent;
        }
    }

    /**
     * Records the agent that creates this resource as both creator and last modifier.
     *
     * @param agent the creating agent, may be {@code null}
     * @return this resource, for chaining
     */
    public LdpResource createdBy(final String agent) {
        this.createdBy = agent;
        this.lastModifiedBy = agent;
        return this;
    }

    /**
     * Returns the IRI of this resource relative to the server base, for example {@code /} for the
     * root container or {@code /books/1}.
     *
     * @return the server-relative IRI
     */
    @JsonIgnore
    public String getRelativeIri() {
        return ResourcePaths.toRelativeIri(path);
    }

    /** @return {@code true} for the root container */
    @JsonIgnore
    public boolean isRoot() {
        return path != null && path.isEmpty();
    }

    /** @return {@code true} when this resource is a container */
    @JsonIgnore
    public boolean isContainer() {
        return interactionModel != null && interactionModel.isContainer();
    }

    /** @return {@code true} when this resource is a non-RDF source */
    @JsonIgnore
    public boolean isNonRdfSource() {
        return interactionModel == InteractionModel.NON_RDF_SOURCE;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LdpResource)) {
            return false;
        }
        return id != null && id.equals(((LdpResource) other).id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "LdpResource[id=" + id + ", path=" + path + ", model=" + interactionModel + ", version=" + version + "]";
    }

}
