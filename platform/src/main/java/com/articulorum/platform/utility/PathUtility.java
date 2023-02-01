package com.articulorum.platform.utility;

import static java.lang.String.join;

import java.util.List;

import org.springframework.web.util.UriComponents;

public class PathUtility {

    public final static String SLASH = "/";

    private PathUtility() {

    }

    public static String getPath(final UriComponents uriComponents) {
        final List<String> pathSegments = uriComponents.getPathSegments();
        return join(SLASH, pathSegments);
    }

    public static String getParentPath(final UriComponents uriComponents) {
        final List<String> pathSegments = uriComponents.getPathSegments();
        return join(SLASH, pathSegments.subList(0, pathSegments.size() - 1));
    }

}