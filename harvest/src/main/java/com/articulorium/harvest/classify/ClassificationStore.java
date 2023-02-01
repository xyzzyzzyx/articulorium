package com.articulorium.harvest.classify;

import java.io.StringWriter;
import java.time.Instant;
import java.util.Optional;

import com.articulorium.domain.rdf.ResourcePaths;
import com.articulorium.domain.vocabulary.Articulorium;
import com.articulorium.domain.vocabulary.Ldp;
import com.articulorium.domain.vocabulary.Rdf;
import com.articulorium.harvest.ingest.PlatformClient;
import com.articulorium.harvest.source.DublinCore;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the classification of a harvested record in the platform, as an RDF source below the
 * record's copy: {@code <container>/<source>/<record>/classification}. It lives and dies with the
 * record (deleting the record deletes it), survives harvests (which replace the record's own
 * triples, not its children) and is found with the platform's search: open classifications are
 * typed {@code art:ReviewPending}.
 *
 * <p>The agent's suggestion is kept whole, as its JSON document; the status, confidence, failure,
 * placement and decision are also RDF statements of their own.</p>
 */
public class ClassificationStore {

    /** Name of a record's classification below the record. */
    public static final String NAME = "classification";

    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * Returns the path of a record's classification.
     *
     * @param record the record's path
     * @return the classification's path
     */
    public static String pathOf(final String record) {
        return ResourcePaths.child(ResourcePaths.normalize(record), NAME);
    }

    /**
     * Reads a record's classification.
     *
     * @param platform the platform, with the credentials to use
     * @param record   the record's path
     * @return the classification, or empty when the record has none
     */
    public Optional<Classification> read(final PlatformClient platform, final String record) {
        final String path = pathOf(record);
        return platform.rdf(path).map(model -> fromModel(model, model.createResource(platform.iri(path)),
            ResourcePaths.normalize(record), platform));
    }

    /**
     * Stores a record's classification, replacing what was there.
     *
     * @param platform       the platform, with the credentials to use
     * @param classification the classification
     */
    public void write(final PlatformClient platform, final Classification classification) {
        final String path = pathOf(classification.record());
        final Model model = ModelFactory.createDefaultModel();
        final Resource subject = model.createResource(platform.iri(path));
        final Property type = model.createProperty(Rdf.TYPE);
        subject.addProperty(type, model.createResource(Articulorium.CLASSIFICATION));
        if (classification.status().open()) {
            subject.addProperty(type, model.createResource(Articulorium.REVIEW_PENDING));
        }
        subject.addProperty(model.createProperty(Articulorium.CLASSIFICATION_STATUS), classification.status().name());
        subject.addProperty(model.createProperty(Articulorium.HARVEST_SOURCE), classification.source());
        subject.addProperty(model.createProperty(Articulorium.HARVEST_IDENTIFIER), classification.identifier());
        if (classification.title() != null && !classification.title().isBlank()) {
            subject.addProperty(model.createProperty(DublinCore.DCTERMS + "title"), classification.title());
        }
        subject.addProperty(model.createProperty(Rdf.RDFS_LABEL), "Classification of "
            + (classification.title() == null || classification.title().isBlank() ? classification.identifier()
                : classification.title()));
        if (classification.suggestion() != null) {
            subject.addProperty(model.createProperty(Articulorium.CLASSIFICATION_SUGGESTION),
                json.writeValueAsString(classification.suggestion()));
            subject.addProperty(model.createProperty(Articulorium.CLASSIFICATION_CONFIDENCE),
                model.createTypedLiteral(Double.toString(classification.suggestion().confidenceOrZero()),
                    XSDDatatype.XSDdecimal));
        }
        if (classification.failure() != null) {
            subject.addProperty(model.createProperty(Articulorium.CLASSIFICATION_FAILURE), classification.failure());
        }
        if (classification.classifiedAt() != null) {
            subject.addProperty(model.createProperty(Articulorium.CLASSIFIED_AT), dateTime(model,
                classification.classifiedAt()));
        }
        if (classification.placedAt() != null) {
            subject.addProperty(model.createProperty(Articulorium.PLACED_AT),
                model.createResource(platform.iri(classification.placedAt())));
        }
        if (classification.decidedBy() != null) {
            subject.addProperty(model.createProperty(Articulorium.DECIDED_BY), classification.decidedBy());
        }
        if (classification.decidedAt() != null) {
            subject.addProperty(model.createProperty(Articulorium.DECIDED_AT), dateTime(model,
                classification.decidedAt()));
        }
        final StringWriter writer = new StringWriter();
        RDFDataMgr.write(writer, model, Lang.NTRIPLES);
        platform.put(path, writer.toString(), Ldp.RDF_SOURCE, false);
    }

    private Classification fromModel(final Model model, final Resource subject, final String record,
            final PlatformClient platform) {
        final Classification.Status status = literal(subject, Articulorium.CLASSIFICATION_STATUS)
            .flatMap(ClassificationStore::status).orElse(Classification.Status.PENDING);
        Suggestion suggestion = null;
        final Optional<String> document = literal(subject, Articulorium.CLASSIFICATION_SUGGESTION);
        if (document.isPresent()) {
            try {
                suggestion = json.readValue(document.get(), Suggestion.class);
            } catch (JacksonException e) {
                suggestion = null;
            }
        }
        final String placedAt = subject.listProperties(model.createProperty(Articulorium.PLACED_AT)).toList().stream()
            .map(Statement::getObject)
            .filter(RDFNode::isURIResource)
            .map(node -> platform.pathOf(node.asResource().getURI()).orElse(null))
            .filter(path -> path != null)
            .findFirst().orElse(null);
        return new Classification(literal(subject, Articulorium.HARVEST_SOURCE).orElse(null), record,
            literal(subject, Articulorium.HARVEST_IDENTIFIER).orElse(null),
            literal(subject, DublinCore.DCTERMS + "title").orElse(null), status, suggestion,
            literal(subject, Articulorium.CLASSIFICATION_FAILURE).orElse(null), false,
            literal(subject, Articulorium.CLASSIFIED_AT).flatMap(ClassificationStore::instant).orElse(null), placedAt,
            literal(subject, Articulorium.DECIDED_BY).orElse(null),
            literal(subject, Articulorium.DECIDED_AT).flatMap(ClassificationStore::instant).orElse(null));
    }

    private static Optional<String> literal(final Resource subject, final String predicate) {
        final Statement statement = subject.getProperty(subject.getModel().createProperty(predicate));
        return statement != null && statement.getObject().isLiteral()
            ? Optional.of(statement.getObject().asLiteral().getLexicalForm())
            : Optional.empty();
    }

    private static Optional<Classification.Status> status(final String value) {
        try {
            return Optional.of(Classification.Status.valueOf(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<Instant> instant(final String value) {
        try {
            return Optional.of(Instant.parse(value));
        } catch (java.time.format.DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static org.apache.jena.rdf.model.Literal dateTime(final Model model, final Instant instant) {
        return model.createTypedLiteral(instant.toString(), Rdf.XSD_NAMESPACE + "dateTime");
    }

}
