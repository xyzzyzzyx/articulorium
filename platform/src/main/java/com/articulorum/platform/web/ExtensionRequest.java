package com.articulorum.platform.web;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.articulorum.domain.vocabulary.Fedora;
import com.articulorum.platform.ldp.exception.ResourceNotFoundException;

/**
 * A request addressed to a Fedora API extension rather than to an LDP resource.
 *
 * <table>
 *   <caption>Recognised extension paths</caption>
 *   <tr><th>Path</th><th>{@link Extension}</th></tr>
 *   <tr><td>{@code <resource>/fcr:versions}</td><td>{@link Extension#VERSIONS}: the version list
 *       (Memento TimeMap); POST creates a version</td></tr>
 *   <tr><td>{@code <resource>/fcr:versions/<id>}</td><td>{@link Extension#MEMENTO}: one historic
 *       version</td></tr>
 *   <tr><td>{@code <binary>/fcr:fixity}</td><td>{@link Extension#FIXITY}: a fixity check</td></tr>
 *   <tr><td>{@code /fcr:search}</td><td>{@link Extension#SEARCH}: the search API</td></tr>
 *   <tr><td>{@code <resource>/fcr:acl}</td><td>{@link Extension#ACL}: the resource's WebAC access
 *       control list</td></tr>
 *   <tr><td>{@code <resource>/fcr:inbox}</td><td>{@link Extension#INBOX}: the resource's Linked
 *       Data Notifications inbox (COAR Notify)</td></tr>
 *   <tr><td>{@code <resource>/fcr:inbox/<id>}</td><td>{@link Extension#NOTIFICATION}: one
 *       received notification</td></tr>
 *   <tr><td>{@code <resource>/fcr:outbox}</td><td>{@link Extension#OUTBOX}: notifications sent on
 *       behalf of the resource; POST sends one</td></tr>
 * </table>
 *
 * @param resourcePath the path of the resource the extension applies to (empty for the root)
 * @param extension    the extension
 * @param versionId    the memento's version identifier or the notification's identifier,
 *                     {@code null} for other extensions
 */
public record ExtensionRequest(String resourcePath, Extension extension, String versionId) {

    /** The supported extensions. */
    public enum Extension {

        /** The versions of a resource. */
        VERSIONS,

        /** One version of a resource. */
        MEMENTO,

        /** A fixity check of a non-RDF source. */
        FIXITY,

        /** The search API (root only). */
        SEARCH,

        /** The access control list of a resource. */
        ACL,

        /** The notification inbox of a resource. */
        INBOX,

        /** One notification of an inbox. */
        NOTIFICATION,

        /** The notifications sent on behalf of a resource. */
        OUTBOX

    }

    /** Segment addressing the versions of a resource. */
    public static final String VERSIONS_SEGMENT = Fedora.EXTENSION_PREFIX + "versions";

    /** Segment addressing the fixity check of a binary. */
    public static final String FIXITY_SEGMENT = Fedora.EXTENSION_PREFIX + "fixity";

    /** Segment addressing the search API. */
    public static final String SEARCH_SEGMENT = Fedora.EXTENSION_PREFIX + "search";

    /** Segment addressing the access control list of a resource. */
    public static final String ACL_SEGMENT = Fedora.EXTENSION_PREFIX + "acl";

    /** Segment addressing the notification inbox of a resource. */
    public static final String INBOX_SEGMENT = Fedora.EXTENSION_PREFIX + "inbox";

    /** Segment addressing the notification outbox of a resource. */
    public static final String OUTBOX_SEGMENT = Fedora.EXTENSION_PREFIX + "outbox";

    /**
     * Recognises an extension path.
     *
     * @param path the normalised request path (percent-encoded as sent)
     * @return the extension request, or empty when the path addresses an ordinary resource
     * @throws ResourceNotFoundException when the path uses an {@code fcr:} segment in a way that
     *                                   addresses nothing
     */
    public static Optional<ExtensionRequest> parse(final String path) {
        if (path == null || path.isEmpty()) {
            return Optional.empty();
        }
        final List<String> segments = Arrays.asList(path.split("/"));
        int index = -1;
        for (int i = 0; i < segments.size(); i++) {
            if (decode(segments.get(i)).toLowerCase().startsWith(Fedora.EXTENSION_PREFIX)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return Optional.empty();
        }
        final String segment = decode(segments.get(index));
        final String resourcePath = String.join("/", segments.subList(0, index));
        final int remaining = segments.size() - index - 1;
        if (VERSIONS_SEGMENT.equals(segment)) {
            if (remaining == 0) {
                return Optional.of(new ExtensionRequest(resourcePath, Extension.VERSIONS, null));
            }
            if (remaining == 1) {
                return Optional.of(new ExtensionRequest(resourcePath, Extension.MEMENTO,
                    decode(segments.get(index + 1))));
            }
        } else if (FIXITY_SEGMENT.equals(segment) && remaining == 0) {
            return Optional.of(new ExtensionRequest(resourcePath, Extension.FIXITY, null));
        } else if (SEARCH_SEGMENT.equals(segment) && remaining == 0 && index == 0) {
            return Optional.of(new ExtensionRequest("", Extension.SEARCH, null));
        } else if (ACL_SEGMENT.equals(segment) && remaining == 0) {
            return Optional.of(new ExtensionRequest(resourcePath, Extension.ACL, null));
        } else if (INBOX_SEGMENT.equals(segment) && remaining == 0) {
            return Optional.of(new ExtensionRequest(resourcePath, Extension.INBOX, null));
        } else if (INBOX_SEGMENT.equals(segment) && remaining == 1) {
            return Optional.of(new ExtensionRequest(resourcePath, Extension.NOTIFICATION,
                decode(segments.get(index + 1))));
        } else if (OUTBOX_SEGMENT.equals(segment) && remaining == 0) {
            return Optional.of(new ExtensionRequest(resourcePath, Extension.OUTBOX, null));
        }
        throw new ResourceNotFoundException(path);
    }

    private static String decode(final String segment) {
        try {
            return URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return segment;
        }
    }

}
