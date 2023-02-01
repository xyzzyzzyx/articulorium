package com.articulorium.ai.profiles;

/**
 * What to suggest for.
 *
 * @param path the profile (a resource whose subject is a {@code foaf:Person})
 */
public record ProfileSuggestionRequest(String path) {
}
