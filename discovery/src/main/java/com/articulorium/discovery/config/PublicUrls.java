package com.articulorium.discovery.config;

import java.net.URI;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The public addresses of the discovery layer, the platform and the IIIF exports, and the mapping
 * between platform paths and the public IRIs that identify Solr documents.
 */
@Component
public class PublicUrls {

    private final String publicUrl;

    private final String platformBase;

    private final String transformBase;

    private final String repositoryUiBase;

    /**
     * Resolves the public addresses of the settings.
     *
     * @param properties the settings
     */
    public PublicUrls(final DiscoveryProperties properties) {
        this.publicUrl = slash(properties.getPublicUrl());
        this.platformBase = resolve(publicUrl, properties.getPlatformPublicUrl());
        this.transformBase = resolve(publicUrl, properties.getTransformPublicUrl());
        this.repositoryUiBase = resolve(publicUrl, properties.getLayout().getItem().getRepositoryUiUrl());
    }

    /** @return the public address of the discovery layer, ending with {@code /} */
    public String publicUrl() {
        return publicUrl;
    }

    /** @return the public LDP root of the platform, ending with {@code /} */
    public String platformBase() {
        return platformBase;
    }

    /** @return the public base of the transform service, ending with {@code /} */
    public String transformBase() {
        return transformBase;
    }

    /**
     * Resolves a link of the settings against the public address.
     *
     * @param reference an absolute URL or a relative reference, may be empty
     * @return the absolute URL, {@code null} for an empty reference
     */
    public @Nullable String absolute(@Nullable final String reference) {
        return StringUtils.hasText(reference) ? URI.create(publicUrl).resolve(reference.trim()).toString() : null;
    }

    /**
     * Returns the public IRI of a platform resource.
     *
     * @param path the resource path, without leading {@code /}
     * @return the IRI
     */
    public String iriOf(final String path) {
        return platformBase + path;
    }

    /**
     * Returns the path of a public platform IRI.
     *
     * @param iri the IRI
     * @return the path without leading or trailing {@code /}, {@code null} when the IRI is not
     *     below the platform's root
     */
    public @Nullable String pathOf(@Nullable final String iri) {
        if (iri == null || !iri.startsWith(platformBase)) {
            return null;
        }
        String path = iri.substring(platformBase.length());
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * Returns the public IIIF export of a resource.
     *
     * @param path the resource path
     * @return {@code <transform>/iiif/<path>}
     */
    public String iiifUrl(final String path) {
        return transformBase + "iiif/" + path;
    }

    /**
     * Returns the page of a resource in the repository's user interface.
     *
     * @param path the resource path
     * @return {@code <ui>/browse/<path>}
     */
    public String repositoryUiUrl(final String path) {
        return repositoryUiBase + "browse/" + path;
    }

    private static String resolve(final String base, final String reference) {
        return slash(URI.create(base).resolve(reference).toString());
    }

    private static String slash(final String url) {
        return url.endsWith("/") ? url : url + "/";
    }

}
