package com.articulorum.platform.webac;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.articulorum.domain.rdf.ResourcePaths;
import com.articulorum.domain.vocabulary.Acl;
import com.articulorum.platform.web.RequestTargets;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Tells clients who they are and what they may do: {@code GET /.well-known/webac?resource=<path>}.
 *
 * <p>The user interface uses it to show the signed-in agent, to offer only the actions the agent
 * may perform, and (for agents with {@code acl:Control}) to show and edit the effective access
 * control list of a resource.</p>
 */
@RestController
@Tag(name = "Web Access Control", description = "The requesting agent and its access modes")
public class WebAcController {

    /** Path of the endpoint. */
    public static final String PATH = "/.well-known/webac";

    /**
     * The access of the requesting agent to a resource.
     *
     * @param agent          the requesting agent
     * @param enforced       whether access control is enforced
     * @param resource       the server-relative IRI of the resource access was computed for
     * @param aclResource    the server-relative IRI of the resource whose ACL applies, {@code null}
     *                       when the built-in root ACL applies
     * @param inherited      whether the ACL is inherited from an ancestor
     * @param userModes      the agent's modes ({@code read}, {@code write}, {@code append},
     *                       {@code control})
     * @param publicModes    everyone's modes
     * @param authorizations the effective authorizations; only for agents with {@code acl:Control}
     */
    public record AccessInfo(AgentInfo agent, boolean enforced, String resource, String aclResource,
            boolean inherited, List<String> userModes, List<String> publicModes, List<Authorization> authorizations) {
    }

    /**
     * The requesting agent.
     *
     * @param webId         its identifier, {@code null} when anonymous
     * @param groups        groups its credentials put it in
     * @param authenticated whether it presented valid credentials
     */
    public record AgentInfo(String webId, Set<String> groups, boolean authenticated) {
    }

    private final WebAcProperties properties;

    private final AgentResolver agents;

    private final AccessControlService access;

    /**
     * Creates the controller.
     *
     * @param properties WebAC settings
     * @param agents     resolves the requesting agent
     * @param access     decides access
     */
    public WebAcController(final WebAcProperties properties, final AgentResolver agents,
            final AccessControlService access) {
        this.properties = properties;
        this.agents = agents;
        this.access = access;
    }

    /**
     * Describes the requesting agent's access to a resource.
     *
     * @param request  the request
     * @param resource the resource path (absolute IRIs below this server and relative IRIs are
     *                 accepted too); the root when omitted
     * @return the access information, or 401 when presented credentials are invalid
     */
    @GetMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "webacAccess", summary = "The requesting agent and its access to a resource")
    public ResponseEntity<AccessInfo> access(final HttpServletRequest request,
            @Parameter(description = "Resource path or IRI") @RequestParam(name = "resource", required = false)
            final String resource) {
        final Agent agent;
        try {
            agent = agents.resolve(request);
        } catch (AuthenticationFailedException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        final String path = toPath(resource, RequestTargets.base(request));
        final AgentInfo agentInfo = new AgentInfo(agent.webId(), agent.groups(), agent.authenticated());
        if (!properties.isEnabled()) {
            final List<String> all = modeNames(Set.copyOf(Acl.MODES));
            return ResponseEntity.ok(new AccessInfo(agentInfo, false, ResourcePaths.toRelativeIri(path), null, false,
                all, all, List.of()));
        }
        final AccessControlService.EffectiveAcl effective = access.effective(path);
        final Set<String> user = access.modes(agent, effective);
        final Set<String> everyone = access.modes(Agent.ANONYMOUS, effective);
        return ResponseEntity.ok(new AccessInfo(agentInfo, true, ResourcePaths.toRelativeIri(effective.resourcePath()),
            effective.aclPath() == null ? null : ResourcePaths.toRelativeIri(effective.aclPath()),
            effective.inherited(), modeNames(user), modeNames(everyone),
            user.contains(Acl.CONTROL) ? effective.authorizations() : List.of()));
    }

    /**
     * Turns a resource parameter into a path.
     *
     * @param resource the parameter (path, relative IRI or absolute IRI below the base)
     * @param base     the server base
     * @return the normalised path
     */
    static String toPath(final String resource, final String base) {
        if (resource == null || resource.isBlank()) {
            return "";
        }
        String value = resource.trim();
        final String normalizedBase = base.endsWith("/") ? base : base + "/";
        if (value.startsWith(normalizedBase)) {
            value = value.substring(normalizedBase.length());
        }
        return ResourcePaths.normalize(value);
    }

    private static List<String> modeNames(final Set<String> modes) {
        final List<String> names = new ArrayList<>();
        for (final String token : AccessControlService.wacAllowTokens(modes).split(" ")) {
            if (!token.isBlank()) {
                names.add(token);
            }
        }
        return names;
    }

}
