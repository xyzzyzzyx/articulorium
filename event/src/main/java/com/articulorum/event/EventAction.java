package com.articulorum.event;

/**
 * The kind of change an event reports.
 */
public enum EventAction {

    /** A resource was created. */
    CREATED,

    /** A resource's state (or its server-managed triples) changed. */
    UPDATED,

    /** A resource was deleted. */
    DELETED

}
