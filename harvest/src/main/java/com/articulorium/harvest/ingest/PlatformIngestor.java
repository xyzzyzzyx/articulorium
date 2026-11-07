package com.articulorium.harvest.ingest;

import java.io.StringWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.articulorium.domain.vocabulary.Articulorium;
import com.articulorium.domain.vocabulary.Fedora;
import com.articulorium.domain.vocabulary.Ldp;
import com.articulorium.domain.vocabulary.Premis;
import com.articulorium.domain.vocabulary.Rdf;
import com.articulorium.harvest.core.HarvestedFile;
import com.articulorium.harvest.core.HarvestedRecord;
import com.articulorium.harvest.core.IngestAction;
import com.articulorium.harvest.core.RecordIngestor;
import com.articulorium.harvest.source.DublinCore;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;

/**
 * Ingests harvested records into the platform: one basic container per record at
 * {@code <container>/<source>/<slug>} ({@link RecordPaths}), replaced with {@code PUT} on every
 * harvest and deleted when the source reports the record deleted.
 *
 * <p>The record's metadata is written as harvested, except that its {@link HarvestedRecord#subject()
 * subject} is renamed to the copy's IRI (other subjects, such as VIVO positions or hash IRIs, keep
 * theirs) and server-managed {@code ldp:} and {@code fedora:} statements are dropped. Provenance is
 * added: {@code art:HarvestedRecord}, the source, the identifier, the source's datestamp, when it was
 * harvested, {@code prov:wasDerivedFrom} the original (for http(s) subjects), its sets and its files
 * (referenced where the source keeps them). Titles are copied to {@code rdfs:label}, which the user
 * interface and search display, when the record has no label.</p>
 */
public class PlatformIngestor implements RecordIngestor {

    /** W3C PROV-O: the copy was derived from the original. */
    static final String PROV_WAS_DERIVED_FROM = "http://www.w3.org/ns/prov#wasDerivedFrom";

    private static final String XSD_DATE_TIME = Rdf.XSD_NAMESPACE + "dateTime";

    private final PlatformClient platform;

    private final RecordPaths paths;

    private final Clock clock;

    /**
     * Creates the ingestor.
     *
     * @param platform the platform client
     * @param paths    where records go
     * @param clock    the clock ({@code art:harvestedAt})
     */
    public PlatformIngestor(final PlatformClient platform, final RecordPaths paths, final Clock clock) {
        this.platform = platform;
        this.paths = paths;
        this.clock = clock;
    }

    @Override
    public void begin(final String sourceId) {
        // once per harvest: the containers may have been deleted since the last one
        container(paths.root(), Articulorium.HARVEST_ROOT, "Harvested records", null);
        container(paths.source(sourceId), Articulorium.HARVEST_SOURCE_CONTAINER, sourceId, sourceId);
    }

    @Override
    public IngestAction ingest(final String sourceId, final HarvestedRecord record) {
        final String path = paths.record(sourceId, record.identifier());
        if (record.deleted()) {
            return platform.delete(path) ? IngestAction.DELETED : IngestAction.SKIPPED;
        }
        final boolean created = platform.put(path, body(sourceId, record, platform.iri(path)), Ldp.BASIC_CONTAINER,
            false);
        return created ? IngestAction.CREATED : IngestAction.UPDATED;
    }

    /**
     * Builds the N-Triples of a record's copy.
     *
     * @param sourceId the source
     * @param record   the record
     * @param iri      the IRI of the copy
     * @return the N-Triples
     */
    public String body(final String sourceId, final HarvestedRecord record, final String iri) {
        final Model out = ModelFactory.createDefaultModel();
        final Resource copy = out.createResource(iri);
        for (final Statement statement : record.metadata().listStatements().toList()) {
            if (serverManaged(statement)) {
                continue;
            }
            out.add(rename(out, statement.getSubject(), record.subject(), copy).asResource(),
                out.createProperty(statement.getPredicate().getURI()),
                rename(out, statement.getObject(), record.subject(), copy));
        }
        labelFromTitles(copy);
        copy.addProperty(out.createProperty(Rdf.NAMESPACE + "type"), out.createResource(Articulorium.HARVESTED_RECORD));
        copy.addProperty(out.createProperty(Articulorium.HARVEST_SOURCE), sourceId);
        copy.addProperty(out.createProperty(Articulorium.HARVEST_IDENTIFIER), record.identifier());
        if (record.datestamp() != null) {
            copy.addProperty(out.createProperty(Articulorium.HARVEST_DATESTAMP), dateTime(out, record.datestamp()));
        }
        copy.addProperty(out.createProperty(Articulorium.HARVESTED_AT), dateTime(out, clock.instant()));
        if (record.subject().startsWith("http://") || record.subject().startsWith("https://")) {
            copy.addProperty(out.createProperty(PROV_WAS_DERIVED_FROM), out.createResource(record.subject()));
        }
        record.sets().forEach(set -> copy.addProperty(out.createProperty(Articulorium.HARVEST_SET), set));
        for (final HarvestedFile file : record.files()) {
            final Resource location = out.createResource(file.location().toString());
            copy.addProperty(out.createProperty(Articulorium.HARVEST_FILE), location);
            if (file.name() != null) {
                location.addProperty(out.createProperty(Premis.FILENAME), file.name());
            }
            if (file.mediaType() != null) {
                location.addProperty(out.createProperty(Premis.HAS_MIME_TYPE), file.mediaType());
            }
            if (file.size() != null) {
                location.addProperty(out.createProperty(Premis.HAS_SIZE),
                    out.createTypedLiteral(Long.toString(file.size()), XSDDatatype.XSDlong));
            }
        }
        final StringWriter writer = new StringWriter();
        RDFDataMgr.write(writer, out, Lang.NTRIPLES);
        return writer.toString();
    }

    private void container(final String path, final String type, final String title, final String sourceId) {
        final Model model = ModelFactory.createDefaultModel();
        final Resource container = model.createResource(platform.iri(path));
        container.addProperty(model.createProperty(Rdf.NAMESPACE + "type"), model.createResource(type));
        container.addProperty(model.createProperty(DublinCore.DCTERMS + "title"), title);
        container.addProperty(model.createProperty(Rdf.RDFS_LABEL), title);
        if (sourceId != null) {
            container.addProperty(model.createProperty(Articulorium.HARVEST_SOURCE), sourceId);
        }
        final StringWriter writer = new StringWriter();
        RDFDataMgr.write(writer, model, Lang.NTRIPLES);
        // If-None-Match: * leaves an existing container (and its checkpoint) alone, also when two
        // harvests prepare it at the same time
        platform.put(path, writer.toString(), Ldp.BASIC_CONTAINER, true);
    }

    /**
     * Tells whether a statement is one the platform manages itself and refuses or ignores in a body.
     *
     * @param statement the statement
     * @return {@code true} for {@code ldp:} and {@code fedora:} predicates and types
     */
    public static boolean serverManaged(final Statement statement) {
        final String predicate = statement.getPredicate().getURI();
        if (predicate.startsWith(Ldp.NAMESPACE) || predicate.startsWith(Fedora.NAMESPACE)) {
            return true;
        }
        if ((Rdf.NAMESPACE + "type").equals(predicate) && statement.getObject().isURIResource()) {
            final String type = statement.getObject().asResource().getURI();
            return type.startsWith(Ldp.NAMESPACE) || type.startsWith(Fedora.NAMESPACE);
        }
        return false;
    }

    private static RDFNode rename(final Model out, final RDFNode node, final String subject, final Resource copy) {
        if (node.isURIResource()) {
            return subject.equals(node.asResource().getURI()) ? copy : out.createResource(node.asResource().getURI());
        }
        if (node.isAnon()) {
            return out.createResource(node.asResource().getId());
        }
        return node;
    }

    private static void labelFromTitles(final Resource copy) {
        final Model model = copy.getModel();
        final Property label = model.createProperty(Rdf.RDFS_LABEL);
        if (copy.hasProperty(label)) {
            return;
        }
        final List<RDFNode> titles = new ArrayList<>();
        for (final String title : List.of(DublinCore.DCTERMS + "title", DublinCore.DC_ELEMENTS + "title")) {
            copy.listProperties(model.createProperty(title)).forEachRemaining(statement -> {
                if (statement.getObject().isLiteral()) {
                    titles.add(statement.getObject());
                }
            });
        }
        titles.forEach(title -> copy.addProperty(label, title));
    }

    private static Literal dateTime(final Model model, final Instant instant) {
        return model.createTypedLiteral(instant.toString(), XSD_DATE_TIME);
    }

}
