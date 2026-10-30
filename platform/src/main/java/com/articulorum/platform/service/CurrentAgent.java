package com.articulorum.platform.service;

import java.security.Principal;

import com.articulorum.platform.config.LdpProperties;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Determines the agent on whose behalf the current request changes resources, recorded as
 * {@code fedora:createdBy} and {@code fedora:lastModifiedBy} and as the author of OCFL versions.
 *
 * <p>The authenticated principal of the request wins. Without authentication (the platform does
 * not authenticate by itself; a gateway or proxy may) the configured
 * {@code articulorum.ldp.default-agent} is used, mirroring Fedora's anonymous user.</p>
 */
@Component
public class CurrentAgent {

    private final LdpProperties properties;

    /**
     * Creates the component.
     *
     * @param properties LDP settings
     */
    public CurrentAgent(final LdpProperties properties) {
        this.properties = properties;
    }

    /**
     * Returns the name of the current agent.
     *
     * @return the principal name, or the configured default agent
     */
    public String name() {
        final RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            final HttpServletRequest request = servlet.getRequest();
            final Principal principal = request.getUserPrincipal();
            if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
                return principal.getName();
            }
        }
        return properties.getDefaultAgent();
    }

}
