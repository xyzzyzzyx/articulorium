package com.articulorium.ai.classification;

import java.util.List;

/**
 * A harvested record to classify, as the harvest service describes it.
 *
 * @param context      the container the caller ingests into (the source's container of harvested
 *                     records); the caller needs write access to it
 * @param source       the harvest source's identifier
 * @param identifier   the record's identifier in its source
 * @param titles       its titles
 * @param descriptions its descriptions and abstracts
 * @param subjects     its subjects and keywords
 * @param creators     its creators and contributors
 * @param dates        its dates
 * @param genres       its Dublin Core types (thesis, article, photograph ...)
 * @param types        its {@code rdf:type} IRIs
 * @param sets         the sets the source puts it in (collections, communities, OAI sets)
 * @param mediaTypes   the media types of its files
 */
public record ClassificationRequest(String context, String source, String identifier, List<String> titles,
        List<String> descriptions, List<String> subjects, List<String> creators, List<String> dates,
        List<String> genres, List<String> types, List<String> sets, List<String> mediaTypes) {

    /**
     * Creates the request with empty lists instead of {@code null}.
     *
     * @param context      container
     * @param source       source
     * @param identifier   identifier
     * @param titles       titles
     * @param descriptions descriptions
     * @param subjects     subjects
     * @param creators     creators
     * @param dates        dates
     * @param genres       genres
     * @param types        types
     * @param sets         sets
     * @param mediaTypes   media types
     */
    public ClassificationRequest {
        titles = copy(titles);
        descriptions = copy(descriptions);
        subjects = copy(subjects);
        creators = copy(creators);
        dates = copy(dates);
        genres = copy(genres);
        types = copy(types);
        sets = copy(sets);
        mediaTypes = copy(mediaTypes);
    }

    private static List<String> copy(final List<String> values) {
        return values == null ? List.of() : values.stream().filter(value -> value != null && !value.isBlank()).toList();
    }

}
