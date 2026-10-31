package com.articulorum.platform.namespace;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence of {@link NamespacePrefix}es.
 */
public interface NamespacePrefixRepository extends JpaRepository<NamespacePrefix, UUID> {

    /**
     * Tells whether a prefix is already registered.
     *
     * @param prefix the prefix
     * @return {@code true} when it exists
     */
    boolean existsByPrefix(String prefix);

    /**
     * Tells whether a namespace is already registered.
     *
     * @param namespace the namespace IRI
     * @return {@code true} when it exists
     */
    boolean existsByNamespace(String namespace);

}
