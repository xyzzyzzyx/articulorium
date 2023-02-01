package com.articulorium.harvest.source.fedora;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.articulorium.domain.vocabulary.Fedora;
import com.articulorium.domain.vocabulary.Ldp;
import com.articulorium.domain.vocabulary.Premis;
import com.articulorium.harvest.core.HarvestException;
import com.articulorium.harvest.core.HarvestPage;
import com.articulorium.harvest.core.HarvestSource;
import com.articulorium.harvest.core.HarvestWindow;
import com.articulorium.harvest.core.HarvestedFile;
import com.articulorium.harvest.core.HarvestedRecord;
import com.articulorium.harvest.core.RecordFailure;
import com.articulorium.harvest.core.SourceCapabilities;
import com.articulorium.harvest.core.SourceSettings;
import com.articulorium.harvest.source.KeysetCursor;
import com.articulorium.harvest.source.SourceHttp;
import com.articulorium.harvest.source.Timestamps;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RiotException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Harvests the resources of a Fedora Commons 6 repository (or another Articulorium) below a root
 * container.
 *
 * <ul>
 * <li>Resources are listed with the Fedora search API ({@code fcr:search}), ordered by modification
 * time, with keyset paging ({@link KeysetCursor}). Incremental harvests add a {@code modified>=}
 * condition.</li>
 * <li>Every RDF source becomes a record with its RDF (server-managed triples omitted); with
 * {@code include-binaries}, every binary becomes a record whose subject is its description, with
 * the binary referenced as a file (its technical metadata, such as size and digest, stays with the
 * file).</li>
 * <li>Pages ask for {@code page-size} plus the records skipped at the cursor's time, so they stay
 * full where an offset inside a page answers only the rest of it (as the platform's search does).</li>
 * <li>A resource the search lists but that is gone when read becomes a deleted record. Fedora does
 * not list deleted resources otherwise, so their copies stay until removed by hand.</li>
 * </ul>
 */
public class FedoraSource implements HarvestSource {

    private static final String N_TRIPLES = "application/n-triples";

    private static final String PREFER = "return=representation; omit=\"" + Fedora.NAMESPACE + "ServerManaged "
        + Ldp.PREFER_CONTAINMENT + " " + Ldp.PREFER_MEMBERSHIP + "\"";

    private static final String INFO_FEDORA = "info:fedora/";

    /** Technical metadata of binaries (Fedora's and the platform's). */
    private static final Set<String> TECHNICAL = Set.of(Premis.FILENAME, Premis.HAS_MIME_TYPE, Premis.HAS_SIZE,
        Premis.HAS_MESSAGE_DIGEST);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SourceSettings settings;

    private final SourceHttp http;

    private final String root;

    private final Optional<String> rdfType;

    private final boolean includeBinaries;

    /**
     * Creates the source.
     *
     * @param settings        the settings (url: the LDP base)
     * @param http            the HTTP client
     * @param root            the container to harvest below, relative to the base ({@code ""} for all)
     * @param rdfType         only harvest resources of this type, may be empty
     * @param includeBinaries whether binaries become records
     */
    FedoraSource(final SourceSettings settings, final SourceHttp http, final String root,
            final Optional<String> rdfType, final boolean includeBinaries) {
        this.settings = settings;
        this.http = http;
        this.root = root;
        this.rdfType = rdfType;
        this.includeBinaries = includeBinaries;
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
        return new SourceCapabilities(true, false);
    }

    @Override
    public HarvestPage fetch(final HarvestWindow window, final String cursor) {
        final KeysetCursor position = cursor == null ? KeysetCursor.start(window.from()) : KeysetCursor.decode(cursor);
        final StringBuilder uri = new StringBuilder(settings.base())
            .append("fcr:search?order_by=modified&order=asc")
            // records already read at the cursor's time are skipped with the offset; asking for that many
            // more keeps pages full where an offset inside a page answers only the rest of that page
            .append("&max_results=").append(settings.pageSize() + position.skip())
            .append("&offset=").append(position.skip());
        if (!root.isEmpty()) {
            condition(uri, "fedora_id=" + INFO_FEDORA + escape(root) + "/*");
        }
        if (position.time() != null) {
            condition(uri, "modified>=" + position.time());
        }
        rdfType.ifPresent(type -> condition(uri, "rdf_type=" + escape(type)));
        final JsonNode body = json(http.get("search resources", URI.create(uri.toString()), "application/json"));
        final JsonNode items = body.path("items");

        final List<HarvestedRecord> records = new ArrayList<>();
        final List<RecordFailure> failures = new ArrayList<>();
        final List<Instant> times = new ArrayList<>();
        final List<String> ids = new ArrayList<>();
        Instant previous = null;
        for (final JsonNode item : items) {
            final String fedoraId = item.path("fedora_id").asString(null);
            final Instant modified = Timestamps.parse(item.path("modified"));
            if (previous != null && modified != null && modified.isBefore(previous)) {
                throw HarvestException.fatal("Source " + id() + " does not order search results by modification time",
                    null);
            }
            previous = modified == null ? previous : modified;
            times.add(modified);
            ids.add(fedoraId);
            resource(fedoraId, modified, types(item), records, failures);
        }
        final long total = body.path("pagination").path("totalResults").asLong(-1);
        // offsets inside a page may answer the rest of that page only, so the end is told by the total
        final boolean last = items.isEmpty() || (total >= 0
            ? position.skip() + items.size() >= total
            : items.size() < settings.pageSize() + position.skip());
        final String next = last ? null : position.next(items.size(), times, ids, false).encode();
        return new HarvestPage(records, failures, next, null);
    }

    private void resource(final String fedoraId, final Instant modified, final Set<String> types,
            final List<HarvestedRecord> records, final List<RecordFailure> failures) {
        if (isBase(fedoraId)) {
            // the repository's root container is not a record
            return;
        }
        final String iri = iriOf(fedoraId);
        if (iri == null) {
            failures.add(new RecordFailure(String.valueOf(fedoraId), "A search result outside " + settings.base()));
            return;
        }
        if (types.contains(Fedora.NON_RDF_SOURCE_DESCRIPTION)) {
            return;
        }
        final String identifier = iri.substring(settings.base().length());
        try {
            if (types.contains(Ldp.NON_RDF_SOURCE)) {
                if (includeBinaries) {
                    records.add(binary(identifier, iri, modified));
                }
                return;
            }
            records.add(rdf(iri, "read " + identifier).map(model -> HarvestedRecord.of(identifier, iri, modified, model))
                .orElseGet(() -> HarvestedRecord.deleted(identifier, iri, modified)));
        } catch (HarvestException e) {
            if (e.isRecoverable()) {
                throw e;
            }
            failures.add(new RecordFailure(identifier, e.getMessage()));
        }
    }

    private HarvestedRecord binary(final String identifier, final String iri, final Instant modified) {
        final ResponseEntity<Void> head;
        try {
            head = http.call("read binary " + identifier, client -> client.head().uri(URI.create(iri))
                .retrieve().toBodilessEntity());
        } catch (HarvestException e) {
            if (e.getCause() instanceof HttpClientErrorException.NotFound
                    || e.getCause() instanceof HttpClientErrorException.Gone) {
                return HarvestedRecord.deleted(identifier, iri, modified);
            }
            throw e;
        }
        final String description = describedBy(head.getHeaders(), iri);
        final Model described = description == null ? ModelFactory.createDefaultModel()
            : rdf(description, "read the description of " + identifier).orElseGet(ModelFactory::createDefaultModel);
        final Resource binary = described.createResource(iri);
        final String name = literal(binary, Premis.FILENAME).orElse(iri.substring(iri.lastIndexOf('/') + 1));
        final String mediaType = literal(binary, Premis.HAS_MIME_TYPE).orElseGet(() ->
            head.getHeaders().getContentType() == null ? null : head.getHeaders().getContentType().toString());
        final Long size = literal(binary, Premis.HAS_SIZE).map(Long::valueOf).orElseGet(() ->
            head.getHeaders().getContentLength() >= 0 ? head.getHeaders().getContentLength() : null);
        final List<HarvestedFile> files = List.of(new HarvestedFile(URI.create(iri), name, mediaType, size));
        if (description == null) {
            return HarvestedRecord.of(identifier, iri, modified, described).withFiles(files);
        }
        // the record is the description: what was said about the binary moves to it, except the
        // technical metadata, which stays with the file it describes
        final Model model = ModelFactory.createDefaultModel();
        final Resource record = model.createResource(description);
        for (final Statement statement : described.listStatements().toList()) {
            final boolean technical = TECHNICAL.contains(statement.getPredicate().getURI());
            model.add(statement.getSubject().equals(binary) && !technical ? record : statement.getSubject(),
                statement.getPredicate(), statement.getObject());
        }
        return HarvestedRecord.of(identifier, description, modified, model).withFiles(files);
    }

    private Optional<Model> rdf(final String iri, final String what) {
        final byte[] body;
        try {
            body = http.call(what, client -> client.get().uri(URI.create(iri))
                .header(HttpHeaders.ACCEPT, N_TRIPLES)
                .header("Prefer", PREFER)
                .retrieve().body(byte[].class));
        } catch (HarvestException e) {
            if (e.getCause() instanceof HttpClientErrorException.NotFound
                    || e.getCause() instanceof HttpClientErrorException.Gone) {
                return Optional.empty();
            }
            throw e;
        }
        final Model model = ModelFactory.createDefaultModel();
        if (body != null) {
            try {
                RDFDataMgr.read(model, new ByteArrayInputStream(body), iri, Lang.NTRIPLES);
            } catch (RiotException e) {
                throw HarvestException.fatal("Source " + id() + " answered unreadable N-Triples for " + iri + ": "
                    + e.getMessage(), e);
            }
        }
        return Optional.of(model);
    }

    private String iriOf(final String fedoraId) {
        if (fedoraId == null) {
            return null;
        }
        final String base = settings.base();
        if (fedoraId.startsWith(INFO_FEDORA)) {
            return base + fedoraId.substring(INFO_FEDORA.length());
        }
        if (fedoraId.startsWith(base) && fedoraId.length() > base.length()) {
            return fedoraId;
        }
        return null;
    }

    private boolean isBase(final String fedoraId) {
        return fedoraId != null && (fedoraId.equals(settings.base()) || fedoraId.equals(INFO_FEDORA)
            || fedoraId.equals(INFO_FEDORA.substring(0, INFO_FEDORA.length() - 1))
            || (fedoraId + "/").equals(settings.base()));
    }

    private static String describedBy(final HttpHeaders headers, final String iri) {
        for (final String link : headers.getOrEmpty(HttpHeaders.LINK)) {
            for (final String value : link.split(",(?=\\s*<)")) {
                if (value.matches("(?s)\\s*<[^>]+>.*;\\s*rel=\"?describedby\"?.*")) {
                    final String target = value.substring(value.indexOf('<') + 1, value.indexOf('>'));
                    return URI.create(iri).resolve(target).toString();
                }
            }
        }
        return null;
    }

    private static Optional<String> literal(final Resource subject, final String property) {
        final Statement statement = subject.getProperty(subject.getModel().createProperty(property));
        return statement != null && statement.getObject().isLiteral()
            ? Optional.of(statement.getObject().asLiteral().getLexicalForm())
            : Optional.empty();
    }

    private static Set<String> types(final JsonNode item) {
        final Set<String> types = new HashSet<>();
        item.path("rdf_type").forEach(type -> types.add(type.asString()));
        return types;
    }

    private JsonNode json(final byte[] body) {
        try {
            return JSON.readTree(body);
        } catch (JacksonException e) {
            throw HarvestException.recoverable("Source " + id() + " answered a malformed search result", e);
        }
    }

    private static void condition(final StringBuilder uri, final String condition) {
        uri.append("&condition=").append(URLEncoder.encode(condition, StandardCharsets.UTF_8));
    }

    private static String escape(final String value) {
        return value.replace("\\", "\\\\").replace("*", "\\*");
    }

}
