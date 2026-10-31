package com.articulorum.event;

import org.springframework.cloud.bus.event.Destination;
import org.springframework.cloud.bus.event.PathDestinationFactory;

/**
 * Factory for Spring Cloud Bus destinations used by Articulorum events.
 */
public final class EventDestinations {

    private static final PathDestinationFactory FACTORY = new PathDestinationFactory();

    private EventDestinations() {
        // utility class
    }

    /**
     * Returns a destination that matches every service on the bus.
     *
     * @return the broadcast destination ({@code **})
     */
    public static Destination all() {
        return FACTORY.getDestination(null);
    }

}
