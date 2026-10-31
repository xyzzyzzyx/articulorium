package com.articulorum.platform.webac;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Stored access control lists.
 */
public interface AclRepository extends JpaRepository<AclEntity, UUID> {

    /**
     * Finds the ACL of a resource.
     *
     * @param path the resource path
     * @return the ACL, or empty
     */
    Optional<AclEntity> findByPath(String path);

    /**
     * Finds the ACLs of several resources.
     *
     * @param paths the resource paths
     * @return the ACLs that exist
     */
    List<AclEntity> findByPathIn(Collection<String> paths);

    /**
     * Deletes the ACLs of a resource and everything below it.
     *
     * @param path   the resource path
     * @param prefix {@code path + "/"}
     * @return the number of deleted ACLs
     */
    @Modifying
    @Query("delete from AclEntity a where a.path = :path or a.path like concat(:prefix, '%')")
    int deleteSubtree(@Param("path") String path, @Param("prefix") String prefix);

}
