package com.articulorum.event;

import java.util.ArrayList;
import java.util.List;

import com.articulorum.domain.LdpResource;
import com.articulorum.domain.Triple;

import com.fasterxml.jackson.annotation.JsonCreator;

import org.springframework.cloud.bus.event.Destination;
import org.springframework.cloud.bus.event.RemoteApplicationEvent;

/**
 * Bus event published by the platform whenever an LDP resource is created, updated or deleted.
 *
 * <p>Besides the persisted {@link LdpResource}, the event carries the resource's complete RDF
 * representation as {@link #getTriples() triples} (client-managed and server-managed triples such
 * as {@code ldp:contains} and membership triples) and the {@link #getBaseUri() base URI} needed to
 * resolve the server-relative IRIs those triples contain. Consumers therefore never have to call
 * back into the platform to index a change. For {@link EventAction#DELETED} the triple list is
 * empty.</p>
 */
public class ResourceEvent extends RemoteApplicationEvent {

    private static final long serialVersionUID = 325987656882558338L;

    private LdpResource resource;

    private EventAction action;

    private String baseUri;

    private List<Triple> triples = new ArrayList<>();

    /**
     * Creates an empty event; required for JSON deserialisation by the bus. Marked as the JSON
     * creator because Jackson 3 would otherwise pick the parameterised constructor (parameter
     * names are compiled in) and fail on the null event source.
     */
    @JsonCreator
    public ResourceEvent() {
        super();
    }

    /**
     * Creates an event.
     *
     * @param source        the object publishing the event
     * @param originService the bus id of the publishing service
     * @param destination   the services that should receive the event
     * @param resource      the affected resource
     * @param action        what happened to the resource
     * @param baseUri       the server base used to resolve relative IRIs
     * @param triples       the resource's complete RDF representation
     */
    public ResourceEvent(final Object source, final String originService, final Destination destination,
            final LdpResource resource, final EventAction action, final String baseUri, final List<Triple> triples) {
        super(source, originService, destination);
        this.resource = resource;
        this.action = action;
        this.baseUri = baseUri;
        this.triples = triples == null ? new ArrayList<>() : new ArrayList<>(triples);
    }

    /** @return the affected resource */
    public LdpResource getResource() {
        return resource;
    }

    /** @param resource the affected resource */
    public void setResource(final LdpResource resource) {
        this.resource = resource;
    }

    /** @return what happened to the resource */
    public EventAction getAction() {
        return action;
    }

    /** @param action what happened to the resource */
    public void setAction(final EventAction action) {
        this.action = action;
    }

    /** @return the server base used to resolve relative IRIs */
    public String getBaseUri() {
        return baseUri;
    }

    /** @param baseUri the server base used to resolve relative IRIs */
    public void setBaseUri(final String baseUri) {
        this.baseUri = baseUri;
    }

    /** @return the resource's complete RDF representation */
    public List<Triple> getTriples() {
        return triples;
    }

    /** @param triples the resource's complete RDF representation */
    public void setTriples(final List<Triple> triples) {
        this.triples = triples == null ? new ArrayList<>() : triples;
    }

}
