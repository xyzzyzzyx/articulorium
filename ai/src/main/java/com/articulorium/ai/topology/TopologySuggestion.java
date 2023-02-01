package com.articulorium.ai.topology;

import java.util.List;

/**
 * A suggested topology. Nothing is saved: an existing topology is used as it is, a draft is
 * opened in the topology editor, reviewed and saved there.
 *
 * @param recommendation whether an existing topology fits or a new one is drafted
 * @param existing       the existing topology ({@link Recommendation#EXISTING})
 * @param draft          the draft ({@link Recommendation#NEW})
 * @param errors         what the topology API would still refuse in the draft (to fix in the editor)
 * @param rationale      why, in the caller's language
 * @param confidence     how sure the model is, from 0 to 1
 */
public record TopologySuggestion(Recommendation recommendation, TopologySummary existing, TopologyDraft draft,
        List<String> errors, String rationale, double confidence) {

    /** What is recommended. */
    public enum Recommendation {

        /** An existing topology. */
        EXISTING,

        /** A new topology (a draft). */
        NEW

    }

}
