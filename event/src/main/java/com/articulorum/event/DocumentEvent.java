package com.articulorum.event;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;

import org.springframework.cloud.bus.event.Destination;
import org.springframework.cloud.bus.event.RemoteApplicationEvent;

/**
 * Bus event carrying a flattened search document produced by the transform service.
 *
 * <p>The document maps field names to value collections; it always contains an {@code id} field
 * holding the IRI of the resource it was derived from.</p>
 */
public class DocumentEvent extends RemoteApplicationEvent {

    private static final long serialVersionUID = -8887607843876866L;

    /** Name of the mandatory identifier field. */
    public static final String ID_FIELD = "id";

    private Map<String, Collection<?>> document = new LinkedHashMap<>();

    private EventAction action;

    /**
     * Creates an empty event; required for JSON deserialisation by the bus. Marked as the JSON
     * creator because Jackson 3 would otherwise pick the parameterised constructor (parameter
     * names are compiled in) and fail on the null event source.
     */
    @JsonCreator
    public DocumentEvent() {
        super();
    }

    /**
     * Creates an event.
     *
     * @param source        the object publishing the event
     * @param originService the bus id of the publishing service
     * @param destination   the services that should receive the event
     * @param document      the search document
     * @param action        what happened to the source resource
     */
    public DocumentEvent(final Object source, final String originService, final Destination destination,
            final Map<String, Collection<?>> document, final EventAction action) {
        super(source, originService, destination);
        this.document = document;
        this.action = action;
    }

    /** @return the search document */
    public Map<String, Collection<?>> getDocument() {
        return document;
    }

    /** @param document the search document */
    public void setDocument(final Map<String, Collection<?>> document) {
        this.document = document;
    }

    /** @return what happened to the source resource */
    public EventAction getAction() {
        return action;
    }

    /** @param action what happened to the source resource */
    public void setAction(final EventAction action) {
        this.action = action;
    }

    /**
     * Returns the document identifier: the first value of the {@value #ID_FIELD} field.
     *
     * @return the identifier, or {@code null} when the document has none
     */
    public String documentId() {
        if (document == null) {
            return null;
        }
        final Collection<?> ids = document.get(ID_FIELD);
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        final Object first = ids.iterator().next();
        return first == null ? null : first.toString();
    }

}
