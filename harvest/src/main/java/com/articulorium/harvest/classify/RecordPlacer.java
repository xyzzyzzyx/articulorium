package com.articulorium.harvest.classify;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.articulorium.domain.rdf.ResourcePaths;
import com.articulorium.domain.vocabulary.Articulorium;
import com.articulorium.domain.vocabulary.Ldp;
import com.articulorium.domain.vocabulary.Pcdm;
import com.articulorium.domain.vocabulary.Rdf;
import com.articulorium.harvest.ingest.FileCopier;
import com.articulorium.harvest.ingest.PlatformClient;
import com.articulorium.harvest.ingest.PlatformIngestor;
import com.articulorium.harvest.source.DublinCore;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;

/**
 * Places an accepted harvested record in a collection, following the repository's content model:
 *
 * <ol>
 * <li>its item is built in the collection: with the topology API (the topology's PCDM types,
 * {@code art:topology}, its members and files containers), or as a plain {@code pcdm:Object};</li>
 * <li>the classification records where the item is ({@code PLACING}), so an interrupted placement
 * resumes on the same item instead of building another;</li>
 * <li>the record's triples move to the item, which keeps its structural statements;</li>
 * <li>the copy where the record was harvested becomes a pointer to the item
 * ({@code art:HarvestPlacement}), so the record is held once and later harvests update the item;</li>
 * <li>the classification is {@code ACCEPTED};</li>
 * <li>the record's files are copied into the item (sources with {@code copy-files}:
 * {@link FileCopier}), so the IIIF manifest and the discovery layer present them.</li>
 * </ol>
 *
 * <p>Every write uses the credentials of whoever accepted the record, so the platform's Web Access
 * Control decides whether they may add items to the collection.</p>
 */
public class RecordPlacer {

    private static final Logger LOG = LoggerFactory.getLogger(RecordPlacer.class);

    /** Statements of an item that belong to its structure, not to the record, and survive updates. */
    static final Set<String> STRUCTURAL = Set.of(Rdf.TYPE, Articulorium.TOPOLOGY, Articulorium.TOPOLOGY_NODE,
        "http://schema.org/position");

    private final ClassificationStore store;

    private final TopologyClient topologies;

    private final Clock clock;

    private final FileCopier files;

    /**
     * Creates a placer that copies no files.
     *
     * @param store      the classifications
     * @param topologies the topology API
     * @param clock      the clock
     */
    public RecordPlacer(final ClassificationStore store, final TopologyClient topologies, final Clock clock) {
        this(store, topologies, clock, null);
    }

    /**
     * Creates the placer.
     *
     * @param store      the classifications
     * @param topologies the topology API
     * @param clock      the clock
     * @param files      copies the files of placed records into their items, may be {@code null}
     */
    public RecordPlacer(final ClassificationStore store, final TopologyClient topologies, final Clock clock,
            final FileCopier files) {
        this.store = store;
        this.topologies = topologies;
        this.clock = clock;
        this.files = files;
    }

    /**
     * Copies the files an item's record references and the item does not hold yet (sources with
     * {@code copy-files}). Never fails: a file that cannot be copied stays referenced.
     *
     * @param platform the platform with the credentials to write with
     * @param source   the record's source
     * @param item     the item's path
     */
    public void copyFiles(final PlatformClient platform, final String source, final String item) {
        if (files != null && source != null) {
            files.copy(platform, source, item);
        }
    }

    /**
     * Places a harvested record in a collection.
     *
     * @param platform       the platform with the credentials of whoever accepts
     * @param authorization  those credentials, for the topology API
     * @param classification the record's classification
     * @param collection     the collection's path
     * @param topology       the topology of the item, {@code null} for a plain PCDM object
     * @param decidedBy      who accepts ({@code auto} for automatic acceptance)
     * @return the accepted classification
     * @throws TopologyClient.PlacementException when the record cannot be placed (with an HTTP status)
     */
    public Classification place(final PlatformClient platform, final String authorization,
            final Classification classification, final String collection, final String topology,
            final String decidedBy) {
        final String staging = classification.record();
        final Model stagingModel = platform.rdf(staging).orElseThrow(() -> new TopologyClient.PlacementException(404,
            "The record /" + staging + " no longer exists"));
        final Resource stagingSubject = stagingModel.createResource(platform.iri(staging));
        String item = classification.placedAt();
        if (stagingSubject.hasProperty(stagingModel.createProperty(Rdf.TYPE),
                stagingModel.createResource(Articulorium.HARVEST_PLACEMENT)) && item != null) {
            // moved already: only the decision was not recorded
            final Classification recorded = accept(platform, classification, item, decidedBy);
            copyFiles(platform, classification.source(), item);
            return recorded;
        }
        final String target = ResourcePaths.normalize(collection);
        final Model collectionModel = platform.rdf(target).orElseThrow(() -> new TopologyClient.PlacementException(422,
            "The collection /" + target + " does not exist"));
        if (!collectionModel.contains(collectionModel.createResource(platform.iri(target)),
                collectionModel.createProperty(Rdf.TYPE), collectionModel.createResource(Articulorium.COLLECTION))) {
            throw new TopologyClient.PlacementException(422, "/" + target + " is not a collection");
        }
        final String title = title(stagingModel, stagingSubject, classification);
        final String slug = ResourcePaths.nameOf(staging);
        if (item == null || platform.rdf(item).isEmpty()) {
            item = existingItem(platform, target, slug, classification).orElse(null);
        }
        if (item == null) {
            if (topology != null) {
                final JsonNode definition = topologies.get(topology, authorization)
                    .orElseThrow(() -> new TopologyClient.PlacementException(422, "No topology " + topology));
                item = topologies.build(definition, target, title, slug, authorization);
            } else {
                item = platform.create(target, slug, "<> <" + Rdf.TYPE + "> <" + Pcdm.OBJECT + "> .\n<> <"
                    + DublinCore.DCTERMS + "title> " + turtleLiteral(title) + " .\n", Ldp.BASIC_CONTAINER);
            }
            store.write(platform, with(classification, Classification.Status.PLACING, item, null));
        }
        final Model moved = rename(stagingModel, platform.iri(staging), platform.iri(item));
        writeRecord(platform, item, moved);
        platform.put(staging, stub(platform, staging, item, classification, title), Ldp.BASIC_CONTAINER, false);
        LOG.info("Placed harvested record {} of {} at /{} (decided by {})", classification.identifier(),
            classification.source(), item, decidedBy);
        final Classification accepted = accept(platform, classification, item, decidedBy);
        copyFiles(platform, classification.source(), item);
        return accepted;
    }

    /**
     * Writes a record's triples to its item, keeping the item's structural statements (types,
     * topology, position).
     *
     * @param platform the platform
     * @param item     the item's path
     * @param record   the record's triples, about the item's IRI
     */
    public void writeRecord(final PlatformClient platform, final String item, final Model record) {
        final String iri = platform.iri(item);
        final Model current = platform.rdf(item).orElseThrow(() -> new TopologyClient.PlacementException(404,
            "The item /" + item + " no longer exists"));
        final Model body = ModelFactory.createDefaultModel();
        final Resource subject = current.createResource(iri);
        for (final Statement statement : current.listStatements(subject, null, (RDFNode) null).toList()) {
            if (STRUCTURAL.contains(statement.getPredicate().getURI()) && !PlatformIngestor.serverManaged(statement)) {
                body.add(statement);
            }
        }
        for (final Statement statement : record.listStatements().toList()) {
            if (!PlatformIngestor.serverManaged(statement)) {
                body.add(statement);
            }
        }
        platform.put(item, ntriples(body), Ldp.BASIC_CONTAINER, false);
    }

    /**
     * Parses N-Triples.
     *
     * @param ntriples the N-Triples
     * @return the model
     */
    public static Model parse(final String ntriples) {
        final Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, new ByteArrayInputStream(ntriples.getBytes(StandardCharsets.UTF_8)), Lang.NTRIPLES);
        return model;
    }

    private Classification accept(final PlatformClient platform, final Classification classification, final String item,
            final String decidedBy) {
        final Classification accepted = new Classification(classification.source(), classification.record(),
            classification.identifier(), classification.title(), Classification.Status.ACCEPTED,
            classification.suggestion(), null, false, classification.classifiedAt(), item, decidedBy, clock.instant());
        store.write(platform, accepted);
        return accepted;
    }

    /**
     * Finds an item left by an interrupted placement: the record's slug in the collection, holding
     * the same record.
     */
    private static Optional<String> existingItem(final PlatformClient platform, final String collection,
            final String slug, final Classification classification) {
        final String candidate = ResourcePaths.child(collection, slug);
        return platform.rdf(candidate).filter(model -> {
            final Resource subject = model.createResource(platform.iri(candidate));
            return classification.identifier() != null && subject.hasProperty(
                model.createProperty(Articulorium.HARVEST_IDENTIFIER), classification.identifier())
                && subject.hasProperty(model.createProperty(Articulorium.HARVEST_SOURCE), classification.source());
        }).map(model -> candidate);
    }

    private static Classification with(final Classification classification, final Classification.Status status,
            final String placedAt, final String decidedBy) {
        return new Classification(classification.source(), classification.record(), classification.identifier(),
            classification.title(), status, classification.suggestion(), classification.failure(), false,
            classification.classifiedAt(), placedAt, decidedBy, null);
    }

    private static Model rename(final Model model, final String from, final String to) {
        final Model out = ModelFactory.createDefaultModel();
        for (final Statement statement : model.listStatements().toList()) {
            final Resource subject = statement.getSubject().isURIResource() && from.equals(statement.getSubject().getURI())
                ? out.createResource(to) : statement.getSubject();
            final RDFNode object = statement.getObject().isURIResource()
                && from.equals(statement.getObject().asResource().getURI()) ? out.createResource(to) : statement.getObject();
            out.add(subject, statement.getPredicate(), object);
        }
        return out;
    }

    private static String stub(final PlatformClient platform, final String staging, final String item,
            final Classification classification, final String title) {
        final Model model = ModelFactory.createDefaultModel();
        final Resource subject = model.createResource(platform.iri(staging));
        subject.addProperty(model.createProperty(Rdf.TYPE), model.createResource(Articulorium.HARVEST_PLACEMENT));
        subject.addProperty(model.createProperty(Articulorium.PLACED_AT), model.createResource(platform.iri(item)));
        if (classification.source() != null) {
            subject.addProperty(model.createProperty(Articulorium.HARVEST_SOURCE), classification.source());
        }
        if (classification.identifier() != null) {
            subject.addProperty(model.createProperty(Articulorium.HARVEST_IDENTIFIER), classification.identifier());
        }
        subject.addProperty(model.createProperty(Rdf.RDFS_LABEL), title);
        return ntriples(model);
    }

    private static String title(final Model model, final Resource subject, final Classification classification) {
        for (final String predicate : List.of(DublinCore.DCTERMS + "title", Rdf.RDFS_LABEL)) {
            final Statement statement = subject.getProperty(model.createProperty(predicate));
            if (statement != null && statement.getObject().isLiteral()) {
                return statement.getObject().asLiteral().getLexicalForm();
            }
        }
        return classification.title() != null ? classification.title() : ResourcePaths.nameOf(classification.record());
    }

    private static String ntriples(final Model model) {
        final StringWriter writer = new StringWriter();
        RDFDataMgr.write(writer, model, Lang.NTRIPLES);
        return writer.toString();
    }

    static String turtleLiteral(final String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

}
