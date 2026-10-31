package com.articulorum.platform.webac;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Optional;
import java.util.Set;

import com.articulorum.platform.web.RequestTargets;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Enforces Web Access Control on every LDP request.
 *
 * <p>The filter resolves the requesting {@link Agent}, determines the mode the request needs
 * ({@link AccessModes}) and the agent's modes on the target resource
 * ({@link AccessControlService}), and answers 401 Unauthorized (anonymous agents) or 403 Forbidden
 * when the mode is not granted. Allowed requests continue with the agent as the request's
 * principal, so that {@code fedora:createdBy}/{@code fedora:lastModifiedBy} and OCFL version
 * authors record it, and every response carries a {@code WAC-Allow} header listing the modes of
 * the agent ({@code user}) and of everyone ({@code public}).</p>
 *
 * <p>Paths below {@code /.well-known} (constraints, OpenAPI, Swagger UI, WebAC information) are
 * not resources and are not filtered.</p>
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class WebAcFilter extends OncePerRequestFilter {

    /** Request attribute holding the resolved {@link Agent}. */
    public static final String AGENT_ATTRIBUTE = WebAcFilter.class.getName() + ".agent";

    /** Response header listing the granted modes. */
    public static final String WAC_ALLOW = "WAC-Allow";

    private final WebAcProperties properties;

    private final AgentResolver agents;

    private final AccessControlService access;

    /**
     * Creates the filter.
     *
     * @param properties WebAC settings
     * @param agents     resolves the requesting agent
     * @param access     decides access
     */
    public WebAcFilter(final WebAcProperties properties, final AgentResolver agents,
            final AccessControlService access) {
        this.properties = properties;
        this.agents = agents;
        this.access = access;
    }

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return true;
        }
        final String path = RequestTargets.path(request);
        return path.equals(".well-known") || path.startsWith(".well-known/") || path.startsWith("actuator")
            || path.equals("error");
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
            final FilterChain chain) throws ServletException, IOException {
        final Agent agent;
        try {
            agent = agents.resolve(request);
        } catch (AuthenticationFailedException e) {
            deny(response, HttpStatus.UNAUTHORIZED, e.getMessage());
            return;
        }
        request.setAttribute(AGENT_ATTRIBUTE, agent);
        final Optional<AccessModes.Requirement> requirement =
            AccessModes.required(request.getMethod(), RequestTargets.path(request));
        if (requirement.isPresent()) {
            final AccessControlService.EffectiveAcl effective = access.effective(requirement.get().path());
            final Set<String> granted = access.modes(agent, effective);
            if (!AccessModes.satisfies(granted, requirement.get().mode())) {
                final String mode = requirement.get().mode().substring(requirement.get().mode().indexOf('#') + 1);
                if (agent.authenticated()) {
                    deny(response, HttpStatus.FORBIDDEN, agent.webId() + " lacks acl:" + mode + " on /"
                        + effective.resourcePath());
                } else {
                    deny(response, HttpStatus.UNAUTHORIZED, "acl:" + mode + " on /" + effective.resourcePath()
                        + " requires authentication");
                }
                return;
            }
            final Set<String> publicModes = access.modes(Agent.ANONYMOUS, effective);
            response.setHeader(WAC_ALLOW, "user=\"" + AccessControlService.wacAllowTokens(granted)
                + "\",public=\"" + AccessControlService.wacAllowTokens(publicModes) + "\"");
        }
        chain.doFilter(agent.webId() == null ? request : new AgentRequest(request, agent), response);
    }

    /**
     * Answers a denied request with an RFC 9457 problem document.
     *
     * @param response the response
     * @param status   401 or 403
     * @param detail   the explanation
     * @throws IOException when the response cannot be written
     */
    static void deny(final HttpServletResponse response, final HttpStatus status, final String detail)
            throws IOException {
        response.setStatus(status.value());
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"articulorum\"");
        }
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase()
            + "\",\"status\":" + status.value() + ",\"detail\":\"" + escape(detail) + "\"}");
    }

    private static String escape(final String value) {
        if (value == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder(value.length());
        for (final char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** A request whose principal is the WebAC agent. */
    static final class AgentRequest extends HttpServletRequestWrapper {

        private final Agent agent;

        AgentRequest(final HttpServletRequest request, final Agent agent) {
            super(request);
            this.agent = agent;
        }

        @Override
        public Principal getUserPrincipal() {
            return agent::webId;
        }

        @Override
        public String getRemoteUser() {
            return agent.webId();
        }

    }

}
