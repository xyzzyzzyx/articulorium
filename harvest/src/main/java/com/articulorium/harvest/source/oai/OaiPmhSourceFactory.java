package com.articulorium.harvest.source.oai;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.articulorium.harvest.core.HarvestSource;
import com.articulorium.harvest.core.HarvestSourceFactory;
import com.articulorium.harvest.core.SourceSettings;
import com.articulorium.harvest.source.SourceHttp;
import com.articulorium.harvest.source.SourceOptions;
import com.articulorium.harvest.source.SourceRestClients;

import org.springframework.stereotype.Component;

/**
 * Creates {@code oai-pmh} sources: any OAI-PMH 2.0 data provider.
 *
 * <p>Options: {@code metadata-prefix} (default {@code oai_dc}; needs an {@link OaiMetadataMapper}),
 * {@code set} (harvest one set), {@code granularity} ({@code day} or {@code second}; read with
 * Identify when unset). The url is the provider's base URL. Credentials, when set, are sent as an
 * {@code Authorization} header.</p>
 */
@Component
public class OaiPmhSourceFactory implements HarvestSourceFactory {

    /** The type name. */
    public static final String TYPE = "oai-pmh";

    private static final Set<String> OPTIONS = Set.of("metadata-prefix", "set", "granularity");

    private final SourceRestClients clients;

    private final Map<String, OaiMetadataMapper> mappers;

    /**
     * Creates the factory.
     *
     * @param clients the HTTP clients
     * @param mappers the metadata mappers
     */
    public OaiPmhSourceFactory(final SourceRestClients clients, final List<OaiMetadataMapper> mappers) {
        this.clients = clients;
        this.mappers = mappers.stream().collect(Collectors.toMap(OaiMetadataMapper::metadataPrefix,
            Function.identity()));
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public HarvestSource create(final SourceSettings settings) {
        SourceOptions.requireKnown(settings, OPTIONS);
        final String prefix = settings.option("metadata-prefix", "oai_dc");
        final OaiMetadataMapper mapper = mappers.get(prefix);
        if (mapper == null) {
            throw new IllegalArgumentException("Source " + settings.id() + ": no mapper for metadata prefix " + prefix
                + "; supported: " + new TreeSet<>(mappers.keySet()));
        }
        final OaiPmhSource.Granularity granularity = settings.option("granularity").map(value -> {
            try {
                return OaiPmhSource.Granularity.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Source " + settings.id() + ": granularity must be day or second", e);
            }
        }).orElse(null);
        return new OaiPmhSource(settings, SourceHttp.of(clients.builder(), settings, true), mapper,
            settings.option("set").orElse(null), granularity);
    }

}
