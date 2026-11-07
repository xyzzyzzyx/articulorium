package com.articulorium.ai.platform;

import com.articulorium.ai.AgentException;
import com.articulorium.ai.AgentException.Reason;
import com.articulorium.domain.rdf.ResourcePaths;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Decides who may ask the agent about a resource: callers who could apply the suggestion
 * themselves, that is who have {@code write} access to the resource the suggestion changes (a
 * binary's description, a community or collection, the container of custom topologies, the
 * container of harvested records). The platform's Web Access Control decides, for the caller's own
 * credentials; the agent decides nothing itself.
 *
 * <p>This keeps model calls (which cost time and, with hosted providers, money) away from anonymous
 * callers and from readers, and means a suggestion is only shown to someone who may act on it.
 * What the agent reads while preparing a suggestion is read with the same credentials, so a caller
 * never learns, through the model, about content it may not read.</p>
 */
@Component
public class AgentAccess {

    /** The Web Access Control mode needed. */
    static final String WRITE = "write";

    private final PlatformReader platform;

    /**
     * Creates the access check.
     *
     * @param platform the platform reader
     */
    public AgentAccess(final PlatformReader platform) {
        this.platform = platform;
    }

    /**
     * Checks that the caller may change a resource.
     *
     * @param path          the resource
     * @param authorization the caller's {@code Authorization} header, may be {@code null}
     * @return the caller's access
     * @throws AgentException {@link Reason#UNAUTHENTICATED} for anonymous callers without access,
     *                        {@link Reason#FORBIDDEN} for known callers without it
     */
    public PlatformReader.Access requireWrite(final String path, @Nullable final String authorization) {
        final PlatformReader.Access access = platform.access(path, authorization);
        if (!access.modes().contains(WRITE)) {
            final String message = "AI suggestions about /" + ResourcePaths.normalize(path)
                + " need write access to it";
            throw new AgentException(access.authenticated() ? Reason.FORBIDDEN : Reason.UNAUTHENTICATED, message);
        }
        return access;
    }

}
