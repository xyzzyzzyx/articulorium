package com.articulorum.event;

import java.util.Collection;
import java.util.Map;

import org.springframework.cloud.bus.event.RemoteApplicationEvent;

import lombok.Getter;
import lombok.Setter;

public class DocumentEvent extends RemoteApplicationEvent {

    private static final long serialVersionUID = -8887607843876865L;

    @Getter
    @Setter
    private Map<String, Collection<?>> document;

    @Getter
    @Setter
    private EventAction action;

    public DocumentEvent() { }

    public DocumentEvent(Object source, String originService, Map<String, Collection<?>> document, EventAction action) {
        super(source, originService);
        this.document = document;
        this.action = action;
    }

}