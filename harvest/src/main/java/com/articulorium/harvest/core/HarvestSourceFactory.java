package com.articulorium.harvest.core;

/**
 * Creates the sources of one type from their configuration. This is the extension point for new
 * kinds of repositories: implement a {@link HarvestSource} and a factory for it, and register the
 * factory (in the harvest service, as a Spring bean).
 */
public interface HarvestSourceFactory {

    /**
     * Returns the type this factory creates, as named in configuration ({@code oai-pmh},
     * {@code dspace}, ...).
     *
     * @return the type
     */
    String type();

    /**
     * Creates a source.
     *
     * @param settings the source's configuration
     * @return the source
     * @throws IllegalArgumentException when the configuration is invalid (unknown or missing options)
     */
    HarvestSource create(SourceSettings settings);

}
