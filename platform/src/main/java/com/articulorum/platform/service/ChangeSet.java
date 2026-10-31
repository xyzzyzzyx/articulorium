package com.articulorum.platform.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.articulorum.domain.LdpResource;
import com.articulorum.event.EventAction;

/**
 * Collects the resources a single request changed, so that one event per resource is published
 * once the transaction commits.
 *
 * <p>{@link EventAction#CREATED} and {@link EventAction#DELETED} take precedence over
 * {@link EventAction#UPDATED} for the same resource.</p>
 */
public class ChangeSet {

    /**
     * A recorded change.
     *
     * @param resource the changed resource
     * @param action   what happened to it
     */
    public record Change(LdpResource resource, EventAction action) {
    }

    private final Map<UUID, Change> changes = new LinkedHashMap<>();

    /** @param resource a created resource */
    public void created(final LdpResource resource) {
        changes.put(resource.getId(), new Change(resource, EventAction.CREATED));
    }

    /** @param resource an updated resource; ignored if already recorded as created or deleted */
    public void updated(final LdpResource resource) {
        changes.putIfAbsent(resource.getId(), new Change(resource, EventAction.UPDATED));
    }

    /** @param resource a deleted resource */
    public void deleted(final LdpResource resource) {
        changes.put(resource.getId(), new Change(resource, EventAction.DELETED));
    }

    /**
     * Tells whether a resource was recorded as deleted.
     *
     * @param resource the resource
     * @return {@code true} when it was deleted in this change set
     */
    public boolean isDeleted(final LdpResource resource) {
        final Change change = changes.get(resource.getId());
        return change != null && change.action() == EventAction.DELETED;
    }

    /** @return the recorded changes in recording order */
    public List<Change> changes() {
        return new ArrayList<>(changes.values());
    }

}
