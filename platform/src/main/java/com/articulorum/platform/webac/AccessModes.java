package com.articulorum.platform.webac;

import java.util.Optional;

import com.articulorum.domain.vocabulary.Acl;
import com.articulorum.platform.ldp.exception.LdpException;
import com.articulorum.platform.web.ExtensionRequest;

/**
 * Maps requests to the WebAC access mode they require.
 *
 * <table>
 *   <caption>Required modes</caption>
 *   <tr><th>Request</th><th>Mode</th></tr>
 *   <tr><td>GET, HEAD of a resource, its versions, mementos, fixity, inbox and outbox; search</td>
 *       <td>{@code acl:Read}</td></tr>
 *   <tr><td>POST to a container; POST of a notification to an inbox</td>
 *       <td>{@code acl:Append}</td></tr>
 *   <tr><td>PUT, PATCH, DELETE; POST to {@code fcr:versions} or {@code fcr:outbox}</td>
 *       <td>{@code acl:Write}</td></tr>
 *   <tr><td>any method on {@code fcr:acl}</td><td>{@code acl:Control}</td></tr>
 *   <tr><td>OPTIONS</td><td>none</td></tr>
 * </table>
 */
public final class AccessModes {

    /**
     * A mode required on a resource.
     *
     * @param path the path of the resource the mode is required on
     * @param mode the mode
     */
    public record Requirement(String path, String mode) {
    }

    private AccessModes() {
        // utility class
    }

    /**
     * Determines the mode a request requires.
     *
     * @param method the HTTP method
     * @param path   the normalised request path
     * @return the requirement, or empty when none applies (OPTIONS, unknown methods and paths
     *         that address nothing, which the controller answers itself)
     */
    public static Optional<Requirement> required(final String method, final String path) {
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return Optional.empty();
        }
        final Optional<ExtensionRequest> extension;
        try {
            extension = ExtensionRequest.parse(path);
        } catch (LdpException e) {
            return Optional.empty();
        }
        final boolean read = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
        final boolean post = "POST".equalsIgnoreCase(method);
        if (extension.isEmpty()) {
            if (read) {
                return Optional.of(new Requirement(path, Acl.READ));
            }
            if (post) {
                return Optional.of(new Requirement(path, Acl.APPEND));
            }
            if ("PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)
                    || "DELETE".equalsIgnoreCase(method)) {
                return Optional.of(new Requirement(path, Acl.WRITE));
            }
            return Optional.empty();
        }
        final ExtensionRequest request = extension.get();
        final String target = request.resourcePath();
        return switch (request.extension()) {
            case ACL -> Optional.of(new Requirement(target, Acl.CONTROL));
            case VERSIONS, OUTBOX -> Optional.of(new Requirement(target, post ? Acl.WRITE : Acl.READ));
            case INBOX -> Optional.of(new Requirement(target, post ? Acl.APPEND : Acl.READ));
            case MEMENTO, FIXITY, SEARCH, NOTIFICATION -> Optional.of(new Requirement(target, Acl.READ));
        };
    }

    /**
     * Tells whether granted modes satisfy a required mode ({@code acl:Write} implies
     * {@code acl:Append}).
     *
     * @param granted  the granted modes
     * @param required the required mode
     * @return {@code true} when satisfied
     */
    public static boolean satisfies(final java.util.Set<String> granted, final String required) {
        return granted.contains(required) || (Acl.APPEND.equals(required) && granted.contains(Acl.WRITE));
    }

}
