package com.articulorium.harvest.source.oai;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.articulorium.harvest.core.HarvestException;
import com.articulorium.harvest.core.HarvestPage;
import com.articulorium.harvest.core.HarvestSource;
import com.articulorium.harvest.core.HarvestWindow;
import com.articulorium.harvest.core.HarvestedRecord;
import com.articulorium.harvest.core.RecordFailure;
import com.articulorium.harvest.core.SourceCapabilities;
import com.articulorium.harvest.core.SourceSettings;
import com.articulorium.harvest.source.DublinCore;
import com.articulorium.harvest.source.SourceHttp;
import com.articulorium.harvest.source.Timestamps;
import com.articulorium.harvest.source.Xml;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/**
 * Harvests any OAI-PMH 2.0 data provider with {@code ListRecords}.
 *
 * <ul>
 * <li>Paging follows the provider's resumption tokens.</li>
 * <li>Incremental harvests send {@code from} at the provider's granularity (read with
 * {@code Identify} unless configured); the next checkpoint is the provider's {@code responseDate}.</li>
 * <li>Records with {@code status="deleted"} headers become deleted records.</li>
 * <li>Metadata is mapped by the {@link OaiMetadataMapper} of the configured metadata prefix; a
 * record whose metadata cannot be mapped is reported as a failure.</li>
 * </ul>
 */
public class OaiPmhSource implements HarvestSource {

    /** The OAI-PMH namespace. */
    static final String OAI = "http://www.openarchives.org/OAI/2.0/";

    private static final String XML = "application/xml, text/xml;q=0.9";

    private final SourceSettings settings;

    private final SourceHttp http;

    private final OaiMetadataMapper mapper;

    private final FileRule fileRule;

    private final String set;

    /** Read once with Identify; runs of one source follow each other on different threads. */
    private volatile Granularity granularity;

    /**
     * Creates the source.
     *
     * @param settings    the settings
     * @param http        the HTTP client
     * @param mapper      the mapper of the harvested metadata format
     * @param set         the set to harvest, may be {@code null}
     * @param granularity the provider's datestamp granularity, or {@code null} to ask with Identify
     * @param fileRule    derives the files of a record from its identifiers, may be {@code null}
     */
    OaiPmhSource(final SourceSettings settings, final SourceHttp http, final OaiMetadataMapper mapper,
            final String set, final Granularity granularity, final FileRule fileRule) {
        this.fileRule = fileRule;
        this.settings = settings;
        this.http = http;
        this.mapper = mapper;
        this.set = set;
        this.granularity = granularity;
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
        return new SourceCapabilities(true, true);
    }

    @Override
    public HarvestPage fetch(final HarvestWindow window, final String cursor) {
        final StringBuilder query = new StringBuilder("verb=ListRecords");
        if (cursor != null) {
            query.append("&resumptionToken=").append(encode(cursor));
        } else {
            query.append("&metadataPrefix=").append(encode(mapper.metadataPrefix()));
            if (set != null) {
                query.append("&set=").append(encode(set));
            }
            if (window.incremental()) {
                query.append("&from=").append(encode(granularity().format(window.from())));
            }
        }
        final Document response = request(query.toString(), "list records");
        final Instant responseDate = Timestamps.parse(Xml.text(response.getDocumentElement(), OAI, "responseDate"));
        final Element error = Xml.child(response.getDocumentElement(), OAI, "error");
        if (error != null) {
            final String code = error.getAttribute("code");
            if ("noRecordsMatch".equals(code)) {
                return new HarvestPage(List.of(), List.of(), null, responseDate);
            }
            throw HarvestException.fatal(("badResumptionToken".equals(code)
                ? "Source " + id() + " rejected its resumption token (it may have expired; the next harvest restarts): "
                : "Source " + id() + " answered the OAI-PMH error " + code + ": ") + error.getTextContent().strip(),
                null);
        }
        final Element list = Xml.child(response.getDocumentElement(), OAI, "ListRecords");
        if (list == null) {
            throw HarvestException.fatal("Source " + id() + " answered ListRecords without ListRecords", null);
        }
        final List<HarvestedRecord> records = new ArrayList<>();
        final List<RecordFailure> failures = new ArrayList<>();
        int position = 0;
        for (final Element record : Xml.children(list, OAI, "record")) {
            position++;
            try {
                records.add(record(record));
            } catch (IllegalArgumentException e) {
                final Element header = Xml.child(record, OAI, "header");
                final String identifier = header == null ? null : Xml.text(header, OAI, "identifier");
                failures.add(new RecordFailure(identifier != null ? identifier
                    : "record " + position + " of the page after " + (cursor == null ? "the start" : cursor),
                    e.getMessage()));
            }
        }
        final String token = Xml.text(list, OAI, "resumptionToken");
        return new HarvestPage(records, failures, token, responseDate);
    }

    private HarvestedRecord record(final Element record) {
        final Element header = Xml.child(record, OAI, "header");
        if (header == null) {
            throw new IllegalArgumentException("A record without header");
        }
        final String identifier = Xml.text(header, OAI, "identifier");
        if (identifier == null) {
            throw new IllegalArgumentException("A record header without identifier");
        }
        final String subject = subjectOf(identifier);
        final Instant datestamp = Timestamps.parse(Xml.text(header, OAI, "datestamp"));
        final List<String> sets = Xml.children(header, OAI, "setSpec").stream()
            .map(spec -> spec.getTextContent().strip()).filter(spec -> !spec.isEmpty()).toList();
        if ("deleted".equals(header.getAttribute("status"))) {
            return HarvestedRecord.deleted(identifier, subject, datestamp).withSets(sets);
        }
        final Element metadata = Xml.child(record, OAI, "metadata");
        final List<Element> roots = metadata == null ? List.of() : Xml.children(metadata);
        if (roots.size() != 1) {
            throw new IllegalArgumentException("Expected one metadata element, found " + roots.size());
        }
        final Model model = ModelFactory.createDefaultModel();
        mapper.map(roots.get(0), model.createResource(subject));
        final HarvestedRecord harvested = HarvestedRecord.of(identifier, subject, datestamp, model).withSets(sets);
        return fileRule == null ? harvested : harvested.withFiles(fileRule.files(identifiers(model, subject)));
    }

    private static List<String> identifiers(final Model model, final String subject) {
        final List<String> identifiers = new ArrayList<>();
        for (final String predicate : List.of(DublinCore.DCTERMS + "identifier", DublinCore.DC_ELEMENTS + "identifier")) {
            model.listObjectsOfProperty(model.createResource(subject), model.createProperty(predicate))
                .forEachRemaining(node -> {
                    if (node.isLiteral()) {
                        identifiers.add(node.asLiteral().getLexicalForm());
                    } else if (node.isURIResource()) {
                        identifiers.add(node.asResource().getURI());
                    }
                });
        }
        identifiers.sort(null);
        return identifiers;
    }

    /**
     * Returns the IRI standing for a record: its OAI identifier, which OAI-PMH requires to be a URI
     * ({@code oai:arXiv.org:2401.00001}), or a URN built from it when it is not one.
     *
     * @param identifier the OAI identifier
     * @return the IRI
     */
    String subjectOf(final String identifier) {
        return HarvestedRecord.isAbsoluteIri(identifier) ? identifier
            : "urn:articulorium:harvest:" + id() + ":" + encode(identifier);
    }

    private Granularity granularity() {
        if (granularity == null) {
            final Document identify = request("verb=Identify", "identify itself");
            final Element body = Xml.child(identify.getDocumentElement(), OAI, "Identify");
            final String value = body == null ? null : Xml.text(body, OAI, "granularity");
            granularity = value != null && value.contains("T") ? Granularity.SECOND : Granularity.DAY;
        }
        return granularity;
    }

    private Document request(final String query, final String what) {
        final URI uri = URI.create(settings.url() + (settings.url().getRawQuery() == null ? "?" : "&") + query);
        final byte[] body = http.get(what, uri, XML);
        try {
            return Xml.parse(body);
        } catch (SAXException e) {
            // a truncated or garbled transfer; a provider that always answers garbage fails after the retries
            throw HarvestException.recoverable("Source " + id() + " answered malformed XML to " + what + ": "
                + e.getMessage(), e);
        }
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * The granularity of a provider's datestamps.
     */
    enum Granularity {

        /** {@code YYYY-MM-DD}. */
        DAY {
            @Override
            String format(final Instant instant) {
                return LocalDate.ofInstant(instant, ZoneOffset.UTC).toString();
            }
        },

        /** {@code YYYY-MM-DDThh:mm:ssZ}. */
        SECOND {
            @Override
            String format(final Instant instant) {
                return instant.truncatedTo(ChronoUnit.SECONDS).toString();
            }
        };

        /**
         * Formats a {@code from} argument (rounded down, so nothing is missed).
         *
         * @param instant the instant
         * @return the argument
         */
        abstract String format(Instant instant);

    }

}
