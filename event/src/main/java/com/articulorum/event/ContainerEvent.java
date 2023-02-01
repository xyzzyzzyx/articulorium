package com.articulorum.event;

import com.articulorum.domain.Container;

import org.springframework.cloud.bus.event.RemoteApplicationEvent;

import lombok.Getter;
import lombok.Setter;

public class ContainerEvent extends RemoteApplicationEvent {

    private static final long serialVersionUID = 325987656882558337L;

    @Getter
    @Setter
    private Container container;

    @Getter
    @Setter
    private EventAction action;

    public ContainerEvent() { }

    public ContainerEvent(Object source, String originService, Container container, EventAction action) {
        super(source, originService);
        this.container = container;
        this.action = action;
    }

}