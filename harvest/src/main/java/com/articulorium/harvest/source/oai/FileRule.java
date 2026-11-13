package com.articulorium.harvest.source.oai;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.articulorium.harvest.core.HarvestedFile;

/**
 * Derives the files of an OAI-PMH record from its identifiers. Dublin Core records name no files,
 * but many providers serve them at an address that follows from an identifier: arXiv's
 * {@code http://arxiv.org/abs/2401.00001} has its PDF at {@code https://export.arxiv.org/pdf/2401.00001}.
 *
 * <p>Every identifier the pattern matches as a whole yields one file at the template's address, in
 * which {@code {1}}, {@code {2}} ... stand for the pattern's groups. Identifiers leading to the
 * same address yield one file.</p>
 */
final class FileRule {

    private static final Pattern GROUP = Pattern.compile("\\{(\\d)}");

    private final Pattern pattern;

    private final String template;

    private final String mediaType;

    /**
     * Creates the rule.
     *
     * @param pattern   a regular expression matching a whole identifier
     * @param template  the file's address, with {@code {n}} for the pattern's groups
     * @param mediaType the media type of the files, may be {@code null}
     * @throws IllegalArgumentException when the pattern is no regular expression, or the template
     *                                  is no absolute http(s) address or names a group the pattern lacks
     */
    FileRule(final String pattern, final String template, final String mediaType) {
        try {
            this.pattern = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("file-pattern is not a regular expression: " + e.getDescription(), e);
        }
        final Matcher groups = GROUP.matcher(template);
        while (groups.find()) {
            if (Integer.parseInt(groups.group(1)) > this.pattern.matcher("").groupCount()) {
                throw new IllegalArgumentException("file-url names the group " + groups.group()
                    + " that file-pattern does not have");
            }
        }
        final URI probe;
        try {
            probe = URI.create(GROUP.matcher(template).replaceAll("x"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("file-url is not a URL", e);
        }
        if (probe.getHost() == null || !"https".equals(probe.getScheme()) && !"http".equals(probe.getScheme())) {
            throw new IllegalArgumentException("file-url must be an absolute http(s) URL");
        }
        this.template = template;
        this.mediaType = mediaType;
    }

    /**
     * Returns the files the identifiers of a record lead to.
     *
     * @param identifiers the record's identifiers
     * @return the files, without duplicates
     */
    List<HarvestedFile> files(final List<String> identifiers) {
        final Map<String, HarvestedFile> files = new LinkedHashMap<>();
        for (final String identifier : identifiers) {
            final Matcher match = pattern.matcher(identifier.strip());
            if (!match.matches()) {
                continue;
            }
            final StringBuilder address = new StringBuilder();
            final Matcher groups = GROUP.matcher(template);
            while (groups.find()) {
                final String value = match.group(Integer.parseInt(groups.group(1)));
                groups.appendReplacement(address, Matcher.quoteReplacement(value == null ? "" : value));
            }
            groups.appendTail(address);
            final URI location;
            try {
                location = URI.create(address.toString());
            } catch (IllegalArgumentException e) {
                // an identifier with characters no URL may have: no file
                continue;
            }
            files.computeIfAbsent(location.toString(), key -> new HarvestedFile(location, name(location), mediaType, null));
        }
        return new ArrayList<>(files.values());
    }

    private String name(final URI location) {
        final String path = location.getPath() == null ? "" : location.getPath();
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.isEmpty()) {
            name = "file";
        }
        if ("application/pdf".equals(mediaType) && !name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
            name = name + ".pdf";
        }
        return name;
    }

}
