package com.articulorum.platform.service;

import java.util.LinkedHashSet;
import java.util.Set;

import com.articulorum.domain.LdpResource;

import org.springframework.http.HttpMethod;

/**
 * The resource a request path addresses.
 *
 * <p>A path normally addresses an {@link LdpResource} directly. The path
 * {@code <non-rdf-source>/}{@value LdpService#DESCRIPTION_SEGMENT} addresses the RDF description of
 * a non-RDF source instead; in that case {@code description} is {@code true} and
 * {@code resource} is the non-RDF source.</p>
 *
 * @param resource    the addressed resource (for a description: the described binary)
 * @param description whether the description of a non-RDF source is addressed
 */
public record ResolvedTarget(LdpResource resource, boolean description) {

    /**
     * Returns the server-relative IRI of the addressed resource.
     *
     * @return the IRI of the resource, or of its description
     */
    public String relativeIri() {
        return description
            ? resource.getRelativeIri() + "/" + LdpService.DESCRIPTION_SEGMENT
            : resource.getRelativeIri();
    }

    /**
     * Tells whether the target has an RDF representation (everything but a binary itself).
     *
     * @return {@code true} for RDF sources, containers and descriptions
     */
    public boolean isRdf() {
        return description || !resource.isNonRdfSource();
    }

    /**
     * Lists the HTTP methods the target supports, for the {@code Allow} header.
     *
     * @return the methods, in a stable order
     */
    public Set<HttpMethod> allowedMethods() {
        final Set<HttpMethod> methods = new LinkedHashSet<>();
        methods.add(HttpMethod.GET);
        methods.add(HttpMethod.HEAD);
        methods.add(HttpMethod.OPTIONS);
        if (description) {
            methods.add(HttpMethod.PUT);
            methods.add(HttpMethod.PATCH);
            return methods;
        }
        if (resource.isContainer()) {
            methods.add(HttpMethod.POST);
        }
        methods.add(HttpMethod.PUT);
        if (!resource.isNonRdfSource()) {
            methods.add(HttpMethod.PATCH);
        }
        if (!resource.isRoot()) {
            methods.add(HttpMethod.DELETE);
        }
        return methods;
    }

}
