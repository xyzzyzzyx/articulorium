package com.articulorum.platform.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.articulorum.domain.LdpResource;
import com.articulorum.domain.rdf.ResourcePaths;
import com.articulorum.platform.repository.LdpResourceRepository;
import com.articulorum.platform.storage.StorageKeys;

import org.springframework.stereotype.Component;

/**
 * Determines where the history and content of a resource live in the OCFL storage layer.
 *
 * <p>A resource normally owns its OCFL object ({@link StorageKeys#objectId(String)} of its own
 * path) and keeps its files at the object's top level. A resource inside an <em>archival
 * group</em> ({@code fedora:ArchivalGroup}) instead shares the group's object: its files live in
 * its own directory below the group ({@link StorageKeys#resourceDirectory(String)}), and versions
 * are versions of the whole group.</p>
 */
@Component
public class StorageLocator {

    /**
     * The storage location of a resource.
     *
     * @param objectId     the OCFL object holding the resource's history
     * @param ownerPath    the path of the resource owning that object (the resource itself or its
     *                     archival group)
     * @param relativePath the resource's path relative to the owner, empty for the owner
     * @param group        the archival group the resource belongs to (possibly itself), or
     *                     {@code null}
     */
    public record Location(String objectId, String ownerPath, String relativePath, LdpResource group) {

        /** @return the logical path of the description snapshot */
        public String descriptionPath() {
            return StorageKeys.descriptionPath(relativePath);
        }

        /** @return the logical path of the bytes of a binary */
        public String contentPath() {
            return StorageKeys.contentPath(relativePath);
        }

        /** @return the logical path of the technical metadata snapshot */
        public String technicalPath() {
            return StorageKeys.technicalPath(relativePath);
        }

        /** @return the directory holding the resource's files (empty for the object owner) */
        public String directory() {
            return StorageKeys.resourceDirectory(relativePath);
        }

        /** @return {@code true} for a member (not the group itself) of an archival group */
        public boolean isGroupMember() {
            return group != null && !relativePath.isEmpty();
        }

        /** @return {@code true} for an archival group itself */
        public boolean isGroup() {
            return group != null && relativePath.isEmpty();
        }

    }

    private final LdpResourceRepository repository;

    /**
     * Creates the locator.
     *
     * @param repository the resource repository
     */
    public StorageLocator(final LdpResourceRepository repository) {
        this.repository = repository;
    }

    /**
     * Finds the archival group a path belongs to: the nearest archival group among the path itself
     * and its ancestors.
     *
     * @param path the resource path (it need not exist yet)
     * @return the archival group, or empty when the path is not inside one
     */
    public Optional<LdpResource> archivalGroupOf(final String path) {
        final List<String> candidates = selfAndAncestors(path);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return repository.findByPathInAndArchivalGroup(candidates, Boolean.TRUE).stream()
            .max(Comparator.comparingInt(group -> group.getPath().length()));
    }

    /**
     * Finds the archival group a resource belongs to (possibly the resource itself).
     *
     * @param resource the resource
     * @return the archival group, or empty
     */
    public Optional<LdpResource> archivalGroupOf(final LdpResource resource) {
        if (resource.isArchivalGroup()) {
            return Optional.of(resource);
        }
        return archivalGroupOf(resource.getPath());
    }

    /**
     * Determines the storage location of a resource.
     *
     * @param resource the resource; the root container cannot be located
     * @return the location
     */
    public Location locate(final LdpResource resource) {
        final Optional<LdpResource> group = archivalGroupOf(resource);
        if (group.isPresent()) {
            final String owner = group.get().getPath();
            return new Location(StorageKeys.objectId(owner), owner,
                StorageKeys.relativePath(owner, resource.getPath()), group.get());
        }
        return new Location(StorageKeys.objectId(resource.getPath()), resource.getPath(), "", null);
    }

    /**
     * Lists a path and its ancestors, excluding the root (which is never an archival group).
     *
     * @param path the path
     * @return the paths, deepest first
     */
    static List<String> selfAndAncestors(final String path) {
        final List<String> paths = new ArrayList<>();
        Optional<String> current = Optional.of(ResourcePaths.normalize(path));
        while (current.isPresent() && !current.get().isEmpty()) {
            paths.add(current.get());
            current = ResourcePaths.parentOf(current.get());
        }
        return paths;
    }

}
