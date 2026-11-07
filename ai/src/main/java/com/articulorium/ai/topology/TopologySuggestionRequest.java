package com.articulorium.ai.topology;

/**
 * What the caller wants a topology for.
 *
 * @param description the material in the caller's words, for example "scrapbooks of pages with
 *                    pasted clippings and photographs"; may be empty when {@code sample} is given
 * @param sample      path of a container that holds an example of the material, may be {@code null}
 * @param context     path of the container the caller works in (a collection where the topology
 *                    will be built); the caller needs write access to it, or, without it, to the
 *                    container of custom topologies
 */
public record TopologySuggestionRequest(String description, String sample, String context) {
}
