package com.articulorium.harvest.source;

import java.util.Set;
import java.util.TreeSet;

import com.articulorium.harvest.core.SourceSettings;

/**
 * Checks the options of a source, so a misspelt option fails at startup instead of being ignored.
 */
public final class SourceOptions {

    private SourceOptions() {
        // utility class
    }

    /**
     * Rejects options a source type does not know.
     *
     * @param settings the settings
     * @param known    the options the type knows
     * @throws IllegalArgumentException when an option is unknown
     */
    public static void requireKnown(final SourceSettings settings, final Set<String> known) {
        final Set<String> unknown = new TreeSet<>(settings.options().keySet());
        unknown.removeAll(known);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Source " + settings.id() + " (" + settings.type()
                + ") has unknown options " + unknown + "; known options are " + new TreeSet<>(known));
        }
    }

}
