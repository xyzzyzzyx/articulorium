package com.articulorium.harvest.source.fedora;

import java.util.Set;

import com.articulorium.domain.rdf.ResourcePaths;
import com.articulorium.harvest.core.HarvestSource;
import com.articulorium.harvest.core.HarvestSourceFactory;
import com.articulorium.harvest.core.SourceSettings;
import com.articulorium.harvest.source.SourceHttp;
import com.articulorium.harvest.source.SourceOptions;
import com.articulorium.harvest.source.SourceRestClients;

import org.springframework.stereotype.Component;

/**
 * Creates {@code fedora} sources: Fedora Commons 6 (or another Articulorium) through the LDP and
 * search APIs.
 *
 * <p>The url is the LDP base ({@code http://localhost:8080/rest/}). Options: {@code root} (container
 * below the base to harvest, default the whole repository), {@code rdf-type} (only resources of this
 * type, such as {@code http://pcdm.org/models#Object}) and {@code include-binaries} (default
 * {@code true}). Credentials are sent as HTTP Basic or bearer token.</p>
 */
@Component
public class FedoraSourceFactory implements HarvestSourceFactory {

    /** The type name. */
    public static final String TYPE = "fedora";

    private static final Set<String> OPTIONS = Set.of("root", "rdf-type", "include-binaries");

    private final SourceRestClients clients;

    /**
     * Creates the factory.
     *
     * @param clients the HTTP clients
     */
    public FedoraSourceFactory(final SourceRestClients clients) {
        this.clients = clients;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public HarvestSource create(final SourceSettings settings) {
        SourceOptions.requireKnown(settings, OPTIONS);
        return new FedoraSource(settings, SourceHttp.of(clients.builder(), settings, true),
            ResourcePaths.normalize(settings.option("root", "")), settings.option("rdf-type"),
            settings.flag("include-binaries", true));
    }

}
