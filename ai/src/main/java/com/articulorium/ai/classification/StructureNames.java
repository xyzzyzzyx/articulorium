package com.articulorium.ai.classification;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collection;
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

    /** What a model writes for "no name": never the name of a community or collection. */
    private static final Set<String> PLACEHOLDERS = Set.of("none", "null", "nil", "nan", "na", "unknown", "other",
        "others", "misc", "miscellaneous", "tbd", "undefined", "empty", "notapplicable", "nodiscipline",
        "nocommunity", "nocollection", "new", "untitled");

    /** Words that do not tell names apart. */
    private static final Set<String> STOP_WORDS = Set.of("a", "an", "the", "and", "of", "for", "in", "on", "to");

    /** "New collection for ...", "A collection of records about ...": an announcement before a name. */
    private static final Pattern ANNOUNCED = Pattern.compile(
        "^\\s*(?:an?\\s+)?(?:new\\s+)?(?:collection|community|topic)\\s+(?:for|of|on|about)\\s+"
            + "(?:records\\s+(?:related\\s+to|about|on)\\s+)?(?:the\\s+)?", Pattern.CASE_INSENSITIVE);

    /** Classification codes: {@code H.3.7}, {@code cs.DL}, {@code physics.soc-ph}, {@code 68T50}, {@code cs:cs:DL}. */
    private static final Pattern CODE = Pattern.compile(
        "^(?:[A-Z](?:\\.\\d+)+|[a-z-]+\\.[A-Za-z-]{2,}|\\d{2}[A-Z]\\d{2}|[A-Z]?\\d+(?:\\.\\d+)*"
            // OAI set specs: cs:cs:DL, physics:astro-ph
            + "|[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)+)$");

    /** "New Community:", "Collection -", "Sub-community:" ...: a label before a name, not part of it. */
    private static final Pattern LABEL = Pattern.compile(
        "^\\s*(?:(?:an?|the)\\s+)?(?:new\\s+)?(?:sub[-\\s]?)?(?:community|collection|discipline|field|category)\\s*[:\\-–—]\\s*",
        Pattern.CASE_INSENSITIVE);

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

    /**
     * The plain name of a proposed title: without a label ("New Community: Digital Libraries"), an
     * announcement ("New collection for AI in Scientific Writing"), surrounding quotes or trailing
     * punctuation.
     *
     * @param title the title
     * @return the name, possibly empty
     */
    static String clean(final String title) {
        if (title == null) {
            return "";
        }
        String name = title.strip();
        String previous;
        do {
            previous = name;
            name = LABEL.matcher(name).replaceFirst("").strip();
            name = ANNOUNCED.matcher(name).replaceFirst("").strip();
            name = name.replaceAll("^[\"'“”‘’«»]+|[\"'“”‘’«».;,]+$", "").strip();
        } while (!name.equals(previous));
        return name;
    }

    /**
     * Tells whether a title stands for no name ("None", "N/A", "Unknown", "-").
     *
     * @param title the title
     * @return {@code true} for a placeholder or a title without letters or digits
     */
    static boolean isPlaceholder(final String title) {
        final String letters = Normalizer.normalize(clean(title), Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        return letters.isEmpty() || PLACEHOLDERS.contains(letters);
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
     * Tells whether a proposed name stands for one record rather than what records share: a
     * subtitle (a colon), more than five words besides stop words, the record's short title (before
     * its colon), or most of the record's title. A topic sharing a few words with the title
     * ("Latin American Studies" for "Latin American Studies since the 1980s: ...") is a topic.
     *
     * @param title  the proposed name
     * @param record the record's titles
     * @return {@code true} when the name stands for one record
     */
    static boolean namesOneRecord(final String title, final Collection<String> record) {
        if (title == null) {
            return false;
        }
        final String name = clean(title);
        if (name.contains(":")) {
            return true;
        }
        final List<String> words = words(name);
        if (words.size() > MAX_COLLECTION_WORDS) {
            return true;
        }
        final String key = key(name);
        for (final String recordTitle : record) {
            if (recordTitle == null) {
                continue;
            }
            final int colon = recordTitle.indexOf(':');
            if (colon > 0 && key.equals(key(recordTitle.substring(0, colon)))) {
                return true;
            }
            final List<String> recordWords = words(recordTitle);
            if (recordWords.size() >= 3) {
                final long covered = recordWords.stream().filter(words::contains).count();
                if (covered * 10 >= recordWords.size() * 7L) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> words(final String title) {
        final String key = key(title);
        return key.isEmpty() ? List.of() : List.of(key.split("-"));
    }

}
