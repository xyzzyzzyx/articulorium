package com.articulorium.ai.classification;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Names of communities and collections, as the classifier compares and checks them: two titles
 * name the same community when their keys are equal ({@code Digital Libraries},
 * {@code digital library} and {@code The Digital Libraries} alike); a classification code is not a
 * community; a collection named after one record, or announced as "New collection for ...", does
 * not group records.
 */
final class StructureNames {

    /** Words that do not tell names apart. */
    private static final Set<String> STOP_WORDS = Set.of("a", "an", "the", "and", "of", "for", "in", "on", "to");

    /** "New collection for ...", "A collection of records about ...": a model naming one record. */
    private static final Pattern ANNOUNCED = Pattern.compile(
        "^\\s*(?:an?\\s+)?(?:new\\s+)?collection\\s+(?:for|of|on|about)\\b", Pattern.CASE_INSENSITIVE);

    /** Classification codes: {@code H.3.7}, {@code cs.DL}, {@code physics.soc-ph}, {@code 68T50}. */
    private static final Pattern CODE = Pattern.compile(
        "^(?:[A-Z](?:\\.\\d+)+|[a-z-]+\\.[A-Za-z-]{2,}|\\d{2}[A-Z]\\d{2}|[A-Z]?\\d+(?:\\.\\d+)*)$");

    /** A word of a name: two letters at least ("AI" is a name). */
    private static final Pattern WORD = Pattern.compile("\\p{L}{2,}");

    /** More words than this (besides stop words) name a record rather than a group of records. */
    private static final int MAX_COLLECTION_WORDS = 5;

    private StructureNames() {
    }

    /**
     * The key of a title: lower-case words without accents, stop words or plural endings.
     *
     * @param title the title
     * @return the key, empty for a title without words
     */
    static String key(final String title) {
        if (title == null) {
            return "";
        }
        final String plain = Normalizer.normalize(title, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT).replace("&", " and ");
        return String.join("-", Arrays.stream(plain.split("[^\\p{L}\\p{N}]+"))
            .filter(word -> !word.isEmpty() && !STOP_WORDS.contains(word))
            .map(StructureNames::singular)
            .toList());
    }

    private static String singular(final String word) {
        if (word.length() > 4 && word.endsWith("ies")) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us")
                && !word.endsWith("is")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    /**
     * Tells whether a title is a classification code rather than a name ({@code H.3.7},
     * {@code cs.DL}): such codes come from records' subjects and sets, and are no communities.
     *
     * @param title the title
     * @return {@code true} for a code or a title without a word
     */
    static boolean isCode(final String title) {
        final String stripped = title == null ? "" : title.strip();
        return CODE.matcher(stripped).matches() || !WORD.matcher(stripped).find();
    }

    /**
     * Tells whether a proposed collection is named after one record rather than what records share:
     * announced as a new collection ("New collection for ..."), with a subtitle, with more than five
     * words besides stop words, or (with three words or more) made of the words of one of the record's titles.
     *
     * @param title  the proposed collection's title
     * @param record the record's titles
     * @return {@code true} when the title names one record
     */
    static boolean namesOneRecord(final String title, final Collection<String> record) {
        if (title == null) {
            return false;
        }
        if (ANNOUNCED.matcher(title).find() || title.contains(":")) {
            return true;
        }
        final List<String> words = words(title);
        if (words.size() > MAX_COLLECTION_WORDS) {
            return true;
        }
        if (words.size() < 3) {
            return false;
        }
        for (final String recordTitle : record) {
            final Set<String> recordWords = new HashSet<>(words(recordTitle));
            final Set<String> shared = new HashSet<>(words);
            shared.retainAll(recordWords);
            final Set<String> all = new HashSet<>(words);
            all.addAll(recordWords);
            if (recordWords.containsAll(words) || !all.isEmpty() && shared.size() * 2 >= all.size()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> words(final String title) {
        final String key = key(title);
        return key.isEmpty() ? List.of() : List.of(key.split("-"));
    }

}
