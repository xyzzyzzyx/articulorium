package com.articulorium.ai.classification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * The classification a harvested record brings along: the discipline its source's set stands for
 * (arXiv's set {@code cs:cs:DL} is in Computer Science), or the one a subject written as
 * "Discipline - Topic" names, and the topics its subjects name (arXiv's categories: "Digital
 * Libraries", "Computation and Language"), in the source's order (the primary one first).
 *
 * <p>A small model often fails to name a discipline and topic; a source's own classification is
 * curated and stable, so the classifier places a record by it when it can, and the same records
 * meet in the same collections.</p>
 *
 * @param field      the field of the discipline, as configured, or {@code null} when unknown
 * @param discipline the discipline, or {@code null} when the record names none
 * @param source     where the discipline comes from, for the rationale ({@code set cs:cs:DL})
 * @param topics     the topics the record's subjects name, without codes, placeholders, fields and
 *                   the discipline itself
 */
record RecordClassification(@Nullable String field, @Nullable String discipline, @Nullable String source,
        List<String> topics) {

    /** The most topics taken from one record's subjects. */
    private static final int MAX_TOPICS = 5;

    /**
     * Reads a record's own classification.
     *
     * @param request        the record
     * @param fields         the fields, as configured
     * @param setDisciplines the disciplines of sets: {@code spec=Field > Discipline} entries, a
     *                       spec standing for itself and the sets below it ({@code cs} for
     *                       {@code cs:cs:DL})
     * @return its classification, empty when it names neither a discipline nor a topic
     */
    static Optional<RecordClassification> of(final ClassificationRequest request, final List<String> fields,
            final List<String> setDisciplines) {
        String field = null;
        String discipline = null;
        String source = null;
        final Map<String, String> topics = new LinkedHashMap<>();
        // a subject written as "Discipline - Topic" names both
        for (final String subject : request.subjects()) {
            final int dash = subject == null ? -1 : subject.indexOf(" - ");
            if (dash > 0 && discipline == null) {
                final String named = subject.substring(0, dash).strip();
                if (isName(named) && Classifier.matchField(named, fields) == null) {
                    discipline = named;
                    source = "subject \"" + subject.strip() + "\"";
                }
            }
        }
        if (discipline == null) {
            final Map<String, String> bySpec = parse(setDisciplines);
            for (final String set : request.sets()) {
                final String[] levels = set == null ? new String[0] : lookUp(set, bySpec);
                if (levels.length > 0) {
                    field = levels.length > 1 ? Classifier.matchField(levels[0], fields) : null;
                    discipline = levels[levels.length - 1];
                    source = "set " + set.strip();
                    break;
                }
            }
        }
        final String disciplineKey = discipline == null ? null : StructureNames.key(discipline);
        for (final String subject : request.subjects()) {
            if (subject == null) {
                continue;
            }
            final int dash = subject.indexOf(" - ");
            final String topic = (dash > 0 ? subject.substring(dash + 3) : subject).strip();
            final String key = StructureNames.key(topic);
            if (isName(topic) && Classifier.matchField(topic, fields) == null && !key.equals(disciplineKey)
                    && !StructureNames.namesOneRecord(topic, request.titles()) && topics.size() < MAX_TOPICS) {
                topics.putIfAbsent(key, topic);
            }
        }
        if (discipline == null && topics.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RecordClassification(field, discipline, source, List.copyOf(topics.values())));
    }

    /** Whether a part of a subject is a name: no code (H.3.7), no placeholder ("None"), not empty. */
    private static boolean isName(final String value) {
        return !value.isBlank() && !StructureNames.isCode(value) && !StructureNames.isPlaceholder(value)
            && !StructureNames.key(value).isEmpty();
    }

    /** The levels of the discipline of a set: its own entry, or else that of the nearest set above it. */
    private static String[] lookUp(final String set, final Map<String, String> bySpec) {
        String spec = set.strip().toLowerCase(Locale.ROOT);
        while (!spec.isEmpty()) {
            final String levels = bySpec.get(spec);
            if (levels != null) {
                return Classifier.levelsOf(levels).toArray(String[]::new);
            }
            final int colon = spec.lastIndexOf(':');
            spec = colon < 0 ? "" : spec.substring(0, colon);
        }
        return new String[0];
    }

    /** {@code spec=Field > Discipline} entries by lower-case spec; malformed entries are left out. */
    private static Map<String, String> parse(final List<String> entries) {
        final Map<String, String> bySpec = new LinkedHashMap<>();
        for (final String entry : entries == null ? List.<String>of() : entries) {
            final int equals = entry == null ? -1 : entry.indexOf('=');
            if (equals > 0 && !entry.substring(equals + 1).isBlank()) {
                bySpec.put(entry.substring(0, equals).strip().toLowerCase(Locale.ROOT), entry.substring(equals + 1).strip());
            }
        }
        return bySpec;
    }

}
