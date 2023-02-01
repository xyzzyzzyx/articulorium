package com.articulorium.oai.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.articulorium.domain.rdf.ResourcePaths;
import com.articulorium.oai.catalog.CatalogRecord;
import com.articulorium.oai.catalog.CatalogSet;
import com.articulorium.oai.catalog.Cursor;
import com.articulorium.oai.catalog.RecordCatalog;
import com.articulorium.oai.catalog.RecordList;
import com.articulorium.oai.catalog.Selection;
import com.articulorium.oai.config.OaiProperties;
import com.articulorium.oai.metadata.DublinCoreMapper;
import com.articulorium.oai.metadata.OaiDc;
import com.articulorium.oai.platform.PlatformClient;
import com.articulorium.oai.protocol.OaiArguments;
import com.articulorium.oai.protocol.OaiDates;
import com.articulorium.oai.protocol.OaiError;
import com.articulorium.oai.protocol.OaiException;
import com.articulorium.oai.protocol.OaiIdentifiers;
import com.articulorium.oai.protocol.OaiRequest;
import com.articulorium.oai.protocol.OaiVerb;
import com.articulorium.oai.protocol.ResumptionToken;
import com.articulorium.oai.protocol.SetSpecs;
import com.articulorium.oai.service.OaiResponse.Header;
import com.articulorium.oai.service.OaiResponse.MetadataFormat;
import com.articulorium.oai.service.OaiResponse.OaiRecord;
import com.articulorium.oai.service.OaiResponse.SetEntry;
import com.articulorium.oai.service.OaiResponse.Token;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.springframework.stereotype.Service;

/**
 * Answers OAI-PMH 2.0 requests: validates the verb and arguments, reads the catalog and maps
 * records to Dublin Core. Protocol errors are answers too ({@link OaiResponse#error()}); only a
 * failing platform escapes as {@link com.articulorium.oai.platform.PlatformException}.
 */
@Service
public class OaiService {

    /** The only metadata format, which every repository must support. */
    static final MetadataFormat OAI_DC = new MetadataFormat(OaiDc.PREFIX, OaiDc.SCHEMA, OaiDc.NAMESPACE);

    private static final List<String> NAME_PROPERTIES = List.of("http://purl.org/dc/terms/title",
        "http://purl.org/dc/elements/1.1/title", "http://www.w3.org/2000/01/rdf-schema#label",
        "http://schema.org/name");

    private final RecordCatalog catalog;

    private final PlatformClient platform;

    private final DublinCoreMapper mapper;

    private final OaiProperties properties;

    private final OaiIdentifiers identifiers;

    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param catalog    the records and sets
     * @param platform   the platform client (RDF of records)
     * @param mapper     the Dublin Core mapping
     * @param properties OAI-PMH settings
     * @param clock      the clock of response dates
     */
    public OaiService(final RecordCatalog catalog, final PlatformClient platform, final DublinCoreMapper mapper,
            final OaiProperties properties, final Clock clock) {
        this.catalog = catalog;
        this.platform = platform;
        this.mapper = mapper;
        this.properties = properties;
        this.identifiers = new OaiIdentifiers(properties.getRepositoryIdentifier());
        this.clock = clock;
    }

    /**
     * Answers a request.
     *
     * @param parameters the request parameters (query string or form body)
     * @param context    how the harvester sees the system
     * @return the response
     */
    public OaiResponse handle(final Map<String, String[]> parameters, final HarvestContext context) {
        final Instant now = clock.instant();
        final OaiRequest request;
        try {
            request = OaiRequest.parse(parameters);
        } catch (OaiException e) {
            return new OaiResponse(now, context.baseUrl(), null, null, e.getError(), e.getMessage(), null);
        }
        try {
            final OaiResponse.Body body = switch (request.verb()) {
                case IDENTIFY -> identify(context);
                case LIST_METADATA_FORMATS -> listMetadataFormats(request, context);
                case LIST_SETS -> listSets(request, context);
                case GET_RECORD -> getRecord(request, context);
                case LIST_IDENTIFIERS, LIST_RECORDS -> list(request, context);
            };
            return new OaiResponse(now, context.baseUrl(), request.verb(), request.arguments(), null, null, body);
        } catch (OaiException e) {
            return new OaiResponse(now, context.baseUrl(), request.verb(),
                e.getError().omitsRequestAttributes() ? null : request.arguments(), e.getError(), e.getMessage(), null);
        }
    }

    private OaiResponse.Identify identify(final HarvestContext context) {
        final Instant earliest = catalog.earliest(context.platform()).orElse(Instant.EPOCH);
        return new OaiResponse.Identify(properties.getRepositoryName(), context.baseUrl(), earliest,
            List.copyOf(properties.getAdminEmails()));
    }

    private OaiResponse.MetadataFormats listMetadataFormats(final OaiRequest request, final HarvestContext context) {
        final String identifier = request.argument(OaiArguments.IDENTIFIER);
        if (identifier != null) {
            record(identifier, context);
        }
        return new OaiResponse.MetadataFormats(List.of(OAI_DC));
    }

    private OaiResponse.Sets listSets(final OaiRequest request, final HarvestContext context) {
        final String tokenValue = request.argument(OaiArguments.RESUMPTION_TOKEN);
        final ResumptionToken token = tokenValue == null ? null : ResumptionToken.decode(tokenValue, OaiVerb.LIST_SETS);
        final int offset = token == null ? 0 : token.offset();
        final List<CatalogSet> sets = catalog.sets(context.platform());
        if (sets.isEmpty()) {
            throw new OaiException(OaiError.NO_SET_HIERARCHY, "The repository has no communities or collections");
        }
        if (offset >= sets.size()) {
            throw new OaiException(OaiError.BAD_RESUMPTION_TOKEN,
                "The resumption token points past the end of the set list: " + tokenValue);
        }
        final int end = Math.min(sets.size(), offset + Math.max(1, properties.getPageSize()));
        final List<SetEntry> entries = new ArrayList<>();
        for (final CatalogSet set : sets.subList(offset, end)) {
            final String name = platform.rdf(context.platform(), set.path())
                .flatMap(model -> name(model, context.platform().iri(set.path())))
                .orElse(ResourcePaths.nameOf(set.path()));
            entries.add(new SetEntry(set.spec(), name));
        }
        final Token next;
        if (end < sets.size()) {
            next = new Token(new ResumptionToken(OaiVerb.LIST_SETS, null, null, null, null, null, end, end).encode(),
                offset);
        } else {
            next = token == null ? null : new Token("", offset);
        }
        return new OaiResponse.Sets(entries, next);
    }

    private OaiResponse.Record getRecord(final OaiRequest request, final HarvestContext context) {
        checkFormat(request.argument(OaiArguments.METADATA_PREFIX));
        final CatalogRecord found = record(request.argument(OaiArguments.IDENTIFIER), context);
        return new OaiResponse.Record(withMetadata(found, context)
            .orElseThrow(() -> new OaiException(OaiError.ID_DOES_NOT_EXIST,
                "No such item: " + request.argument(OaiArguments.IDENTIFIER))));
    }

    private OaiResponse.Body list(final OaiRequest request, final HarvestContext context) {
        final OaiVerb verb = request.verb();
        final String tokenValue = request.argument(OaiArguments.RESUMPTION_TOKEN);
        final ResumptionToken token = tokenValue == null ? null : ResumptionToken.decode(tokenValue, verb);
        final String prefix = token == null ? request.argument(OaiArguments.METADATA_PREFIX) : token.metadataPrefix();
        final String from = token == null ? request.argument(OaiArguments.FROM) : token.from();
        final String until = token == null ? request.argument(OaiArguments.UNTIL) : token.until();
        final String set = token == null ? request.argument(OaiArguments.SET) : token.set();
        checkFormat(prefix);
        final OaiDates.Datestamp fromStamp = from == null ? null : OaiDates.parse(OaiArguments.FROM, from);
        final OaiDates.Datestamp untilStamp = until == null ? null : OaiDates.parse(OaiArguments.UNTIL, until);
        OaiDates.checkRange(fromStamp, untilStamp);
        String setPath = null;
        if (set != null) {
            setPath = catalog.setPath(SetSpecs.decode(set));
            if (token == null && catalog.set(context.platform(), setPath).isEmpty()) {
                throw new OaiException(OaiError.NO_RECORDS_MATCH, "No such set in this repository: " + set);
            }
        }
        final Selection selection = new Selection(setPath, fromStamp == null ? null : fromStamp.start(),
            untilStamp == null ? null : untilStamp.end());
        final Cursor cursor = token == null ? Cursor.START : new Cursor(token.anchor(), token.offset());
        final int answered = token == null ? 0 : token.cursor();

        final List<OaiRecord> records = new ArrayList<>();
        final List<Header> headers = new ArrayList<>();
        RecordList part = catalog.list(context.platform(), selection, cursor);
        while (true) {
            for (final CatalogRecord record : part.records()) {
                if (verb == OaiVerb.LIST_RECORDS) {
                    // a record deleted since the search is left out
                    withMetadata(record, context).ifPresent(records::add);
                } else {
                    headers.add(header(record));
                }
            }
            if (!records.isEmpty() || !headers.isEmpty() || part.next() == null) {
                break;
            }
            // every record of this part went away while it was read: a list may not be empty
            part = catalog.list(context.platform(), selection, part.next());
        }
        final int size = verb == OaiVerb.LIST_RECORDS ? records.size() : headers.size();
        if (size == 0 && part.next() == null) {
            throw new OaiException(OaiError.NO_RECORDS_MATCH, token == null
                ? "No records match the request"
                : "No further records match the request");
        }
        final Token next;
        if (part.next() != null) {
            next = new Token(new ResumptionToken(verb, prefix, set, from, until, part.next().anchor(),
                part.next().offset(), answered + size).encode(), answered);
        } else {
            next = token == null ? null : new Token("", answered);
        }
        return verb == OaiVerb.LIST_RECORDS
            ? new OaiResponse.Records(records, next)
            : new OaiResponse.Identifiers(headers, next);
    }

    private CatalogRecord record(final String identifier, final HarvestContext context) {
        return identifiers.path(identifier)
            .flatMap(path -> catalog.find(context.platform(), path))
            .orElseThrow(() -> new OaiException(OaiError.ID_DOES_NOT_EXIST, "No such item: " + identifier));
    }

    private Optional<OaiRecord> withMetadata(final CatalogRecord record, final HarvestContext context) {
        return platform.rdf(context.platform(), record.path())
            .map(model -> new OaiRecord(header(record), mapper.map(model, context.platform().iri(record.path()),
                context.landingPage(record.path()))));
    }

    private Header header(final CatalogRecord record) {
        return new Header(identifiers.identifier(record.path()), record.modified(), record.setSpecs());
    }

    private static void checkFormat(final String prefix) {
        if (!OaiDc.PREFIX.equals(prefix)) {
            throw new OaiException(OaiError.CANNOT_DISSEMINATE_FORMAT,
                "Unsupported metadata format: " + prefix + " (only " + OaiDc.PREFIX + " is available)");
        }
    }

    private static Optional<String> name(final Model model, final String iri) {
        final Resource subject = model.createResource(iri);
        for (final String property : NAME_PROPERTIES) {
            final Optional<String> name = model.listStatements(subject, model.createProperty(property), (RDFNode) null)
                .toList().stream()
                .map(Statement::getObject)
                .filter(RDFNode::isLiteral)
                .map(node -> node.asLiteral().getLexicalForm())
                .filter(value -> !value.isBlank())
                .sorted()
                .findFirst();
            if (name.isPresent()) {
                return name;
            }
        }
        return Optional.empty();
    }

}
