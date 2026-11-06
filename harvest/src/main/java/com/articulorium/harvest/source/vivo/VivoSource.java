package com.articulorium.harvest.source.vivo;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.articulorium.harvest.core.HarvestException;
import com.articulorium.harvest.core.HarvestPage;
import com.articulorium.harvest.core.HarvestSource;
import com.articulorium.harvest.core.HarvestWindow;
import com.articulorium.harvest.core.HarvestedRecord;
import com.articulorium.harvest.core.RecordFailure;
import com.articulorium.harvest.core.SourceCapabilities;
import com.articulorium.harvest.core.SourceSettings;
import com.articulorium.harvest.source.SourceHttp;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.RiotException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Harvests the individuals of a VIVO instance (people by default) through its SPARQL Query API
 * ({@code api/sparqlQuery}).
 *
 * <ul>
 * <li>Individuals of the configured classes are listed with a SELECT ordered by IRI, keyset-paged
 * on the last IRI read.</li>
 * <li>Every individual becomes a record with a CONSTRUCT of its own triples, the individuals it
 * reaches through the context predicates (positions, authorships, vCards, educational processes,
 * research areas) and what those link to (organizations, documents, date-time intervals and their
 * values), so a profile arrives with its positions and publications.</li>
 * <li>VIVO keeps no modification times, so every harvest is a full harvest. An individual that was
 * listed but has no triples any more becomes a deleted record.</li>
 * <li>With a user name and password they are sent as the API's {@code email} and {@code password}
 * parameters (the account needs the SPARQL query permission); a token is sent as a bearer token
 * (an endpoint behind a proxy).</li>
 * </ul>
 */
public class VivoSource implements HarvestSource {

    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    private static final String VIVO = "http://vivoweb.org/ontology/core#";

    private static final String RDF_ACCEPT = "application/n-triples, text/turtle;q=0.9, application/rdf+xml;q=0.8";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SourceSettings settings;

    private final SourceHttp http;

    private final URI endpoint;

    private final List<String> classes;

    private final List<String> context;

    /**
     * Creates the source.
     *
     * @param settings the settings (url: the VIVO base)
     * @param http     the HTTP client
     * @param endpoint the SPARQL Query API
     * @param classes  the classes of the individuals to harvest (validated IRIs)
     * @param context  the predicates leading to context individuals (validated IRIs)
     */
    VivoSource(final SourceSettings settings, final SourceHttp http, final URI endpoint, final List<String> classes,
            final List<String> context) {
        this.settings = settings;
        this.http = http;
        this.endpoint = endpoint;
        this.classes = List.copyOf(classes);
        this.context = List.copyOf(context);
    }

    @Override
    public String id() {
        return settings.id();
    }

    @Override
    public String type() {
        return settings.type();
    }

    @Override
    public SourceCapabilities capabilities() {
        return new SourceCapabilities(false, false);
    }

    @Override
    public HarvestPage fetch(final HarvestWindow window, final String cursor) {
        final List<String> individuals = list(cursor);
        final List<HarvestedRecord> records = new ArrayList<>();
        final List<RecordFailure> failures = new ArrayList<>();
        for (final String individual : individuals) {
            if (!HarvestedRecord.isAbsoluteIri(individual)) {
                failures.add(new RecordFailure(individual, "Not an IRI that can be queried"));
                continue;
            }
            try {
                final Model model = describe(individual);
                records.add(model.isEmpty()
                    ? HarvestedRecord.deleted(individual, individual, null)
                    : HarvestedRecord.of(individual, individual, null, model));
            } catch (HarvestException e) {
                if (e.isRecoverable()) {
                    throw e;
                }
                failures.add(new RecordFailure(individual, e.getMessage()));
            }
        }
        final String next = individuals.size() < settings.pageSize() ? null : individuals.get(individuals.size() - 1);
        return new HarvestPage(records, failures, next, null);
    }

    /**
     * Builds the query listing the individuals after a cursor.
     *
     * @param after the last IRI read, or {@code null}
     * @return the SELECT query
     */
    String listQuery(final String after) {
        final String values = classes.stream().map(type -> "<" + type + ">").collect(Collectors.joining(" "));
        return "SELECT DISTINCT ?s WHERE {\n"
            + "  VALUES ?class { " + values + " }\n"
            + "  ?s <" + RDF_TYPE + "> ?class .\n"
            + "  FILTER(isIRI(?s))\n"
            + (after == null ? "" : "  FILTER(STR(?s) > \"" + escapeLiteral(after) + "\")\n")
            + "}\nORDER BY STR(?s)\nLIMIT " + settings.pageSize();
    }

    /**
     * Builds the query describing an individual with its context.
     *
     * @param individual the individual's IRI (validated)
     * @return the CONSTRUCT query
     */
    String describeQuery(final String individual) {
        final String s = "<" + individual + ">";
        final String links = context.stream().map(link -> "<" + link + ">").collect(Collectors.joining(" "));
        final String hop = "  " + s + " ?l ?c . VALUES ?l { " + links + " }\n";
        final String neighbour = hop + "  ?c ?cl ?n . FILTER(isIRI(?n) && ?n != " + s + " && ?cl != <" + RDF_TYPE
            + ">)\n";
        return "CONSTRUCT { " + s + " ?p ?o . ?c ?cp ?co . ?n ?np ?no . ?t ?tp ?to }\nWHERE {\n"
            + "  { " + s + " ?p ?o }\n"
            + "  UNION {\n" + hop + "  ?c ?cp ?co }\n"
            + "  UNION {\n" + neighbour
            + "  ?n ?np ?no . FILTER(?np NOT IN (" + links.replace(" ", ", ") + ", <" + VIVO + "relates>)) }\n"
            + "  UNION {\n" + neighbour
            + "  ?n ?nl ?t . VALUES ?nl { <" + VIVO + "start> <" + VIVO + "end> } ?t ?tp ?to }\n"
            + "}";
    }

    private List<String> list(final String after) {
        final byte[] body = query(listQuery(after), "application/sparql-results+json", "list individuals").getBody();
        final JsonNode results;
        try {
            results = JSON.readTree(body == null ? new byte[0] : body);
        } catch (JacksonException e) {
            throw HarvestException.recoverable("Source " + id() + " answered malformed SPARQL results", e);
        }
        final List<String> individuals = new ArrayList<>();
        for (final JsonNode binding : results.path("results").path("bindings")) {
            final JsonNode s = binding.path("s");
            if ("uri".equals(s.path("type").asString(null))) {
                individuals.add(s.path("value").asString());
            }
        }
        return individuals;
    }

    private Model describe(final String individual) {
        final ResponseEntity<byte[]> response = query(describeQuery(individual), RDF_ACCEPT, "describe " + individual);
        final MediaType contentType = response.getHeaders().getContentType();
        Lang lang = contentType == null ? null
            : RDFLanguages.contentTypeToLang(contentType.getType() + "/" + contentType.getSubtype());
        if (lang == null || "text/plain".equals(contentType.getType() + "/" + contentType.getSubtype())) {
            lang = Lang.NTRIPLES;
        }
        final Model model = ModelFactory.createDefaultModel();
        if (response.getBody() != null) {
            try {
                RDFDataMgr.read(model, new ByteArrayInputStream(response.getBody()), individual, lang);
            } catch (RiotException e) {
                throw HarvestException.fatal("Source " + id() + " answered unreadable RDF for " + individual + ": "
                    + e.getMessage(), e);
            }
        }
        return model;
    }

    private ResponseEntity<byte[]> query(final String query, final String accept, final String what) {
        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        if (settings.credentials().hasUsername()) {
            form.add("email", settings.credentials().username());
            form.add("password", settings.credentials().password() == null ? "" : settings.credentials().password());
        }
        form.add("query", query);
        return http.call(what, client -> client.post().uri(endpoint)
            .header(HttpHeaders.ACCEPT, accept)
            .contentType(new MediaType(MediaType.APPLICATION_FORM_URLENCODED, StandardCharsets.UTF_8))
            .body(form)
            .retrieve()
            .toEntity(byte[].class));
    }

    private static String escapeLiteral(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

}
