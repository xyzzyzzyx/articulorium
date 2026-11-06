package com.articulorium.harvest.source.dspace;

import java.util.Set;

import com.articulorium.harvest.core.HarvestSource;
import com.articulorium.harvest.core.HarvestSourceFactory;
import com.articulorium.harvest.core.SourceSettings;
import com.articulorium.harvest.source.SourceHttp;
import com.articulorium.harvest.source.SourceOptions;
import com.articulorium.harvest.source.SourceRestClients;

import org.springframework.stereotype.Component;

/**
 * Creates {@code dspace} sources: DSpace 7 or later, through the REST API.
 *
 * <p>The url is the REST API root ({@code https://demo.dspace.org/server/api}). Options:
 * {@code scope} (UUID of a community or collection), {@code include-files} (reference the bitstreams
 * of the {@code bundle} bundle, default {@code ORIGINAL}; default {@code false}), {@code sort-field}
 * (discovery sort on the modification time, default {@code lastModified}), {@code incremental-query}
 * (default {@code lastModified:[{from} TO *]}) and {@code handle-resolver} (default
 * {@code https://hdl.handle.net/}). A token is sent as {@code Authorization: Bearer} (a DSpace JWT);
 * without credentials only public items are harvested.</p>
 */
@Component
public class DSpaceSourceFactory implements HarvestSourceFactory {

    /** The type name. */
    public static final String TYPE = "dspace";

    private static final Set<String> OPTIONS = Set.of("scope", "include-files", "bundle", "sort-field",
        "incremental-query", "handle-resolver");

    private final SourceRestClients clients;

    /**
     * Creates the factory.
     *
     * @param clients the HTTP clients
     */
    public DSpaceSourceFactory(final SourceRestClients clients) {
        this.clients = clients;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public HarvestSource create(final SourceSettings settings) {
        SourceOptions.requireKnown(settings, OPTIONS);
        final String query = settings.option("incremental-query", "lastModified:[{from} TO *]");
        if (!query.contains("{from}")) {
            throw new IllegalArgumentException("Source " + settings.id() + ": incremental-query must contain {from}");
        }
        final String resolver = settings.option("handle-resolver", "https://hdl.handle.net/");
        final DSpaceSource.DSpaceOptions options = new DSpaceSource.DSpaceOptions(settings.option("scope"),
            settings.option("sort-field", "lastModified"), query, settings.flag("include-files", false),
            settings.option("bundle", "ORIGINAL"), resolver.endsWith("/") ? resolver : resolver + "/");
        return new DSpaceSource(settings, SourceHttp.of(clients.builder(), settings, true), options);
    }

}
