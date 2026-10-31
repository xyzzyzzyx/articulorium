package com.articulorum.platform.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.articulorum.domain.BinaryContent;
import com.articulorum.domain.LdpResource;
import com.articulorum.domain.Triple;
import com.articulorum.domain.rdf.ResourcePaths;
import com.articulorum.domain.vocabulary.Fedora;
import com.articulorum.domain.vocabulary.Ldp;
import com.articulorum.domain.vocabulary.Premis;
import com.articulorum.domain.vocabulary.Rdf;
import com.articulorum.platform.ldp.Preference;
import com.articulorum.platform.repository.LdpResourceRepository;

import org.springframework.stereotype.Component;

/**
 * Computes the server-managed triples of a resource's representation.
 *
 * <p>These triples are never stored; they are derived from the current state of the repository
 * every time a resource is rendered:</p>
 * <ul>
 *   <li>{@code rdf:type} triples for each LDP class of the interaction model (LDP 4.2.1.1);</li>
 *   <li>{@code ldp:contains} triples of containers (LDP 5.2.1.4);</li>
 *   <li>membership triples of direct and indirect containers, which appear in the representation
 *       of the <em>membership resource</em> ({@code ldp:hasMemberRelation}) or of the
 *       <em>member</em> ({@code ldp:isMemberOfRelation}) (LDP 5.4, 5.5);</li>
 *   <li>technical metadata of non-RDF sources (size, digest, media type, file name);</li>
 *   <li>Fedora repository metadata, as a Fedora 6 server emits it: the {@code fedora:Resource},
 *       {@code fedora:Container}, {@code fedora:ArchivalGroup} and {@code fedora:Binary} types and {@code fedora:created},
 *       {@code fedora:createdBy}, {@code fedora:lastModified} and
 *       {@code fedora:lastModifiedBy}.</li>
 * </ul>
 */
@Component
public class ServerManagedTriples {

    private final LdpResourceRepository repository;

    /**
     * Creates the component.
     *
     * @param repository the resource repository
     */
    public ServerManagedTriples(final LdpResourceRepository repository) {
        this.repository = repository;
    }

    /**
     * Computes the server-managed triples of a resource.
     *
     * @param resource   the resource
     * @param preference which optional triples to include
     * @return the triples, with server-relative IRIs
     */
    public List<Triple> compute(final LdpResource resource, final Preference preference) {
        final List<Triple> triples = new ArrayList<>();
        final String subject = resource.getRelativeIri();
        for (final String type : resource.getInteractionModel().getTypeIris()) {
            triples.add(Triple.iri(subject, Rdf.TYPE, type));
        }
        triples.addAll(fedoraMetadata(resource));
        if (resource.isNonRdfSource() && resource.getBinary() != null) {
            triples.addAll(technicalMetadata(subject, resource.getBinary()));
        }
        if (resource.isContainer() && preference.includeContainment()) {
            for (final String child : repository.findChildPaths(resource.getPath())) {
                triples.add(Triple.iri(subject, Ldp.CONTAINS, ResourcePaths.toRelativeIri(child)));
            }
        }
        if (preference.includeMembership()) {
            triples.addAll(hasMemberTriples(subject));
            isMemberOfTriple(resource).ifPresent(triples::add);
        }
        return triples;
    }

    /**
     * Computes the Fedora repository types and audit metadata of a resource.
     *
     * @param resource the resource
     * @return the triples
     */
    static List<Triple> fedoraMetadata(final LdpResource resource) {
        final String subject = resource.getRelativeIri();
        final List<Triple> triples = new ArrayList<>();
        triples.add(Triple.iri(subject, Rdf.TYPE, Fedora.RESOURCE));
        if (resource.isContainer()) {
            triples.add(Triple.iri(subject, Rdf.TYPE, Fedora.CONTAINER));
        }
        if (resource.isArchivalGroup()) {
            triples.add(Triple.iri(subject, Rdf.TYPE, Fedora.ARCHIVAL_GROUP));
        }
        if (resource.isNonRdfSource()) {
            triples.add(Triple.iri(subject, Rdf.TYPE, Fedora.BINARY));
        }
        if (resource.getCreated() != null) {
            triples.add(Triple.literal(subject, Fedora.CREATED, resource.getCreated().toString(), Fedora.XSD_DATE_TIME,
                null));
        }
        if (resource.getCreatedBy() != null) {
            triples.add(Triple.literal(subject, Fedora.CREATED_BY, resource.getCreatedBy()));
        }
        if (resource.getModified() != null) {
            triples.add(Triple.literal(subject, Fedora.LAST_MODIFIED, resource.getModified().toString(),
                Fedora.XSD_DATE_TIME, null));
        }
        if (resource.getLastModifiedBy() != null) {
            triples.add(Triple.literal(subject, Fedora.LAST_MODIFIED_BY, resource.getLastModifiedBy()));
        }
        return triples;
    }

    /**
     * Computes the technical metadata of a binary: size, SHA-256 digest, media type and file name.
     *
     * @param subject the relative IRI of the binary
     * @param binary  the stored content
     * @return the triples
     */
    public static List<Triple> technicalMetadata(final String subject, final BinaryContent binary) {
        final List<Triple> triples = new ArrayList<>();
        if (binary.getSize() != null) {
            triples.add(Triple.literal(subject, Premis.HAS_SIZE, Long.toString(binary.getSize()), Premis.XSD_LONG, null));
        }
        if (binary.getSha256() != null) {
            triples.add(Triple.iri(subject, Premis.HAS_MESSAGE_DIGEST, Premis.SHA256_URN_PREFIX + binary.getSha256()));
        }
        if (binary.getContentType() != null) {
            triples.add(Triple.literal(subject, Premis.HAS_MIME_TYPE, binary.getContentType()));
        }
        if (binary.getFilename() != null) {
            triples.add(Triple.literal(subject, Premis.FILENAME, binary.getFilename()));
        }
        return triples;
    }

    /**
     * Membership triples whose subject is the given resource (or one of its fragment IRIs):
     * one triple per member of every container that uses the resource as membership resource.
     */
    private List<Triple> hasMemberTriples(final String subject) {
        final Map<UUID, LdpResource> containers = new LinkedHashMap<>();
        repository.findByMembershipResourceIn(List.of(subject)).forEach(c -> containers.put(c.getId(), c));
        repository.findByMembershipResourceStartingWith(subject + "#").forEach(c -> containers.put(c.getId(), c));
        final List<Triple> triples = new ArrayList<>();
        for (final LdpResource container : containers.values()) {
            if (!container.getInteractionModel().isMembershipContainer() || container.getHasMemberRelation() == null) {
                continue;
            }
            for (final LdpResource member : repository.findByParentPathOrderByPath(container.getPath())) {
                for (final String memberIri : LdpRules.memberIris(container, member)) {
                    triples.add(Triple.iri(container.getMembershipResource(), container.getHasMemberRelation(), memberIri));
                }
            }
        }
        return triples;
    }

    /** The {@code ldp:isMemberOfRelation} triple of a resource contained by a direct container. */
    private Optional<Triple> isMemberOfTriple(final LdpResource resource) {
        if (resource.getParentPath() == null) {
            return Optional.empty();
        }
        return repository.findByPath(resource.getParentPath())
            .filter(parent -> parent.getIsMemberOfRelation() != null && parent.getMembershipResource() != null)
            .filter(LdpRules::isMemberSubject)
            .map(parent -> Triple.iri(resource.getRelativeIri(), parent.getIsMemberOfRelation(),
                parent.getMembershipResource()));
    }

}
