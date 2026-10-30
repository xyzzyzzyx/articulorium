package com.articulorum.platform.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.articulorum.domain.LdpResource;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence of {@link LdpResource}s. Specifications drive the {@code fcr:search} API.
 */
public interface LdpResourceRepository extends JpaRepository<LdpResource, UUID>, JpaSpecificationExecutor<LdpResource> {

    /**
     * Finds a resource by path.
     *
     * @param path the normalised path
     * @return the resource, if any
     */
    Optional<LdpResource> findByPath(String path);

    /**
     * Tells whether a resource exists at a path.
     *
     * @param path the normalised path
     * @return {@code true} when it exists
     */
    boolean existsByPath(String path);

    /**
     * Lists the paths of the resources directly contained by a container, in path order.
     *
     * @param parentPath the container path
     * @return the child paths
     */
    @Query("select r.path from LdpResource r where r.parentPath = :parentPath order by r.path")
    List<String> findChildPaths(@Param("parentPath") String parentPath);

    /**
     * Lists the resources directly contained by a container, in path order.
     *
     * @param parentPath the container path
     * @return the children
     */
    List<LdpResource> findByParentPathOrderByPath(String parentPath);

    /**
     * Lists every resource below a path prefix (the prefix must end with {@code /}).
     *
     * @param prefix the path prefix
     * @return the descendants
     */
    List<LdpResource> findByPathStartingWith(String prefix);

    /**
     * Lists the containers whose membership resource is one of the given IRIs.
     *
     * @param membershipResources server-relative or absolute IRIs
     * @return the matching containers
     */
    List<LdpResource> findByMembershipResourceIn(Collection<String> membershipResources);

    /**
     * Lists the containers whose membership resource starts with the given prefix; used to find
     * membership resources that are fragments ({@code /a#it}) of a resource.
     *
     * @param prefix the IRI prefix
     * @return the matching containers
     */
    List<LdpResource> findByMembershipResourceStartingWith(String prefix);

}
