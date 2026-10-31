package com.articulorum.platform.service;

import com.articulorum.domain.LdpResource;

/**
 * Outcome of a PUT request.
 *
 * @param resource the created or replaced resource
 * @param created  {@code true} when the resource did not exist before
 */
public record PutResult(LdpResource resource, boolean created) {
}
