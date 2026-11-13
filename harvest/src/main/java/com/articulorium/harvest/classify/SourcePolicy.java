package com.articulorium.harvest.classify;

import com.articulorium.harvest.config.HarvestProperties;

/**
 * What classification does with the records of one source: the classification settings, with the
 * source's own choices where it makes them.
 *
 * @param classify            whether the source's records are classified at all
 * @param autoAccept          whether suggestions are accepted without a person
 * @param autoAcceptThreshold the confidence from which they are, {@code null} for any
 * @param createCommunities   whether automatic acceptance creates proposed communities
 * @param createCollections   whether automatic acceptance creates proposed collections
 * @param createTopologies    whether automatic acceptance creates proposed topologies
 */
public record SourcePolicy(boolean classify, boolean autoAccept, Double autoAcceptThreshold,
        boolean createCommunities, boolean createCollections, boolean createTopologies) {

    /**
     * Returns the policy of a source.
     *
     * @param properties the harvest settings
     * @param sourceId   the source
     * @return its policy; that of the classification settings for a source that is not configured
     */
    public static SourcePolicy of(final HarvestProperties properties, final String sourceId) {
        final HarvestProperties.Classification settings = properties.getClassification();
        final HarvestProperties.Source source = properties.getSources().get(sourceId);
        final boolean autoAccept = source != null && source.getAutoAccept() != null ? source.getAutoAccept()
            : settings.isAutoAccept();
        return new SourcePolicy(source == null || source.isClassify(), autoAccept, settings.getAutoAcceptThreshold(),
            settings.isCreateCommunities(), settings.isCreateCollections(), settings.isCreateTopologies());
    }

    /**
     * Describes the policy for the log.
     *
     * @return for example {@code accepted automatically (creating communities, collections, topologies)}
     */
    public String describe() {
        if (!classify) {
            return "not classified";
        }
        if (!autoAccept) {
            return "reviewed by a person";
        }
        final java.util.List<String> created = new java.util.ArrayList<>();
        if (createCommunities && createCollections) {
            created.add("communities");
        }
        if (createCollections) {
            created.add("collections");
        }
        if (createTopologies) {
            created.add("topologies");
        }
        return "accepted automatically" + (autoAcceptThreshold == null ? "" : " from confidence " + autoAcceptThreshold)
            + (created.isEmpty() ? " (existing collections and topologies only)"
                : " (creating " + String.join(", ", created) + ")");
    }

}
