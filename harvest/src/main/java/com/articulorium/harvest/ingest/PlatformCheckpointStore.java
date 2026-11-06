package com.articulorium.harvest.ingest;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import com.articulorium.domain.vocabulary.Articulorium;
import com.articulorium.domain.vocabulary.Rdf;
import com.articulorium.harvest.core.CheckpointStore;
import com.articulorium.harvest.core.HarvestException;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;

/**
 * Keeps each source's checkpoint in the platform, as {@code art:harvestCheckpoint} on the source's
 * container, so the service holds no state and any instance can continue a source's harvests.
 */
public class PlatformCheckpointStore implements CheckpointStore {

    private final PlatformClient platform;

    private final RecordPaths paths;

    /**
     * Creates the store.
     *
     * @param platform the platform client
     * @param paths    where the source containers are
     */
    public PlatformCheckpointStore(final PlatformClient platform, final RecordPaths paths) {
        this.platform = platform;
        this.paths = paths;
    }

    @Override
    public Optional<Instant> load(final String sourceId) {
        final Optional<Model> model = platform.rdf(paths.source(sourceId));
        if (model.isEmpty()) {
            return Optional.empty();
        }
        Instant latest = null;
        final StmtIterator statements = model.get().listStatements(null,
            model.get().createProperty(Articulorium.HARVEST_CHECKPOINT), (RDFNode) null);
        while (statements.hasNext()) {
            final Statement statement = statements.next();
            if (!statement.getObject().isLiteral()) {
                continue;
            }
            try {
                final Instant value = Instant.parse(statement.getObject().asLiteral().getLexicalForm());
                latest = latest == null || value.isAfter(latest) ? value : latest;
            } catch (DateTimeParseException e) {
                throw HarvestException.fatal("The checkpoint of source " + sourceId + " is not a date-time: "
                    + statement.getObject(), e);
            }
        }
        return Optional.ofNullable(latest);
    }

    @Override
    public void save(final String sourceId, final Instant checkpoint) {
        final String art = "<" + Articulorium.HARVEST_CHECKPOINT + ">";
        platform.patch(paths.source(sourceId), "DELETE WHERE { <> " + art + " ?checkpoint } ;\n"
            + "INSERT DATA { <> " + art + " \"" + checkpoint + "\"^^<" + Rdf.XSD_NAMESPACE + "dateTime> }");
    }

}
