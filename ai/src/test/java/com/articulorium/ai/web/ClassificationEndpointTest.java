package com.articulorium.ai.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.articulorium.ai.classification.Classification;
import com.articulorium.ai.classification.ClassificationRequest;
import com.articulorium.ai.classification.Classifier;
import com.articulorium.ai.classification.StructureCatalog;
import com.articulorium.ai.support.Agents;

import org.junit.jupiter.api.Test;

/**
 * The classification endpoint passes {@code Cache-Control: no-cache} on: the caller has just
 * created communities or collections, and the structure is read again.
 */
class ClassificationEndpointTest {

    private static final ClassificationRequest RECORD = new ClassificationRequest("harvested/repo", "repo", "x",
        List.of("A title"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    @Test
    void readsTheStructureAgainWhenTheCallerChangedIt() {
        final Agents agents = new Agents();
        final List<Boolean> fresh = new ArrayList<>();
        final Classifier classifier = new Classifier(agents.model, agents.access,
            new StructureCatalog(agents.reader, agents.topologies, agents.properties, java.time.Clock.systemUTC()),
            agents.properties) {

            @Override
            public Classification classify(final ClassificationRequest request, final String authorization,
                    final Locale locale, final boolean freshStructure) {
                fresh.add(freshStructure);
                return null;
            }
        };
        final AgentController controller = new AgentController(agents.properties, null, null, null, null, null,
            classifier, null);

        controller.classify(RECORD, "Bearer h", Locale.ENGLISH, null);
        controller.classify(RECORD, "Bearer h", Locale.ENGLISH, "max-age=0");
        controller.classify(RECORD, "Bearer h", Locale.ENGLISH, "No-Cache");

        assertThat(fresh).containsExactly(false, false, true);
    }

}
