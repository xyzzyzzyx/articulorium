package com.articulorium.harvest.source.vivo;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.articulorium.harvest.core.HarvestSource;
import com.articulorium.harvest.core.HarvestSourceFactory;
import com.articulorium.harvest.core.HarvestedRecord;
import com.articulorium.harvest.core.SourceCredentials;
import com.articulorium.harvest.core.SourceSettings;
import com.articulorium.harvest.source.SourceHttp;
import com.articulorium.harvest.source.SourceOptions;
import com.articulorium.harvest.source.SourceRestClients;

import org.springframework.stereotype.Component;

/**
 * Creates {@code vivo} sources: VIVO 1.10 or later through the SPARQL Query API.
 *
 * <p>The url is the VIVO base ({@code https://vivo.example.edu/vivo/}). Options: {@code classes}
 * (comma-separated class IRIs, default {@code foaf:Person}), {@code endpoint} (the query API relative
 * to the base or absolute, default {@code api/sparqlQuery}) and {@code context} (comma-separated
 * predicates leading to an individual's context, see {@link #DEFAULT_CONTEXT}).</p>
 */
@Component
public class VivoSourceFactory implements HarvestSourceFactory {

    /** The type name. */
    public static final String TYPE = "vivo";

    /** People. */
    static final String FOAF_PERSON = "http://xmlns.com/foaf/0.1/Person";

    /**
     * Context predicates: relationships (positions, authorships, advising), contact information
     * (vCard), roles, participations (educational processes) and research areas.
     */
    static final List<String> DEFAULT_CONTEXT = List.of(
        "http://vivoweb.org/ontology/core#relatedBy",
        "http://purl.obolibrary.org/obo/ARG_2000028",
        "http://purl.obolibrary.org/obo/RO_0000053",
        "http://purl.obolibrary.org/obo/RO_0000056",
        "http://vivoweb.org/ontology/core#hasResearchArea");

    private static final Set<String> OPTIONS = Set.of("classes", "endpoint", "context");

    private final SourceRestClients clients;

    /**
     * Creates the factory.
     *
     * @param clients the HTTP clients
     */
    public VivoSourceFactory(final SourceRestClients clients) {
        this.clients = clients;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public HarvestSource create(final SourceSettings settings) {
        SourceOptions.requireKnown(settings, OPTIONS);
        final URI endpoint = URI.create(settings.base()).resolve(settings.option("endpoint", "api/sparqlQuery"));
        final List<String> classes = iris(settings, "classes", List.of(FOAF_PERSON));
        final List<String> context = iris(settings, "context", DEFAULT_CONTEXT);
        // the API takes the account as form parameters; only a token goes into the Authorization header
        final SourceSettings headerCredentials = settings.credentials().hasToken()
            ? settings
            : new SourceSettings(settings.id(), settings.type(), settings.url(), settings.pageSize(),
                SourceCredentials.NONE, settings.options());
        return new VivoSource(settings, SourceHttp.of(clients.builder(), headerCredentials, true), endpoint, classes,
            context);
    }

    private static List<String> iris(final SourceSettings settings, final String option, final List<String> fallback) {
        final List<String> iris = settings.option(option)
            .map(value -> Arrays.stream(value.split(",")).map(String::trim).filter(iri -> !iri.isEmpty()).toList())
            .orElse(fallback);
        if (iris.isEmpty()) {
            throw new IllegalArgumentException("Source " + settings.id() + ": " + option + " must not be empty");
        }
        for (final String iri : iris) {
            // ontology terms are http(s) IRIs; this also catches prefixed names such as foaf:Person
            if (!HarvestedRecord.isAbsoluteIri(iri) || !(iri.startsWith("http://") || iri.startsWith("https://"))) {
                throw new IllegalArgumentException("Source " + settings.id() + ": " + option + " holds " + iri
                    + ", which is not a full http(s) IRI");
            }
        }
        return iris;
    }

}
