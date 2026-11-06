package com.articulorium.oai.service;

import com.articulorium.oai.platform.PlatformView;

/**
 * How one harvester sees the system.
 *
 * @param baseUrl  the repository's public base URL (the OAI-PMH endpoint)
 * @param platform the harvester's view of the platform
 * @param uiBase   public base of the user interface, ending with {@code /}
 */
public record HarvestContext(String baseUrl, PlatformView platform, String uiBase) {

    /**
     * Returns the landing page of a resource in the user interface.
     *
     * @param path the resource path
     * @return {@code <ui>/browse/<path>}
     */
    public String landingPage(final String path) {
        return uiBase + "browse/" + path;
    }

}
