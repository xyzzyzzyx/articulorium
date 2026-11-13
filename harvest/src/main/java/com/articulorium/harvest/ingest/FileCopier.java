package com.articulorium.harvest.ingest;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import com.articulorium.domain.rdf.ResourcePaths;
import com.articulorium.domain.vocabulary.Articulorium;
import com.articulorium.domain.vocabulary.Ldp;
import com.articulorium.domain.vocabulary.Pcdm;
import com.articulorium.domain.vocabulary.Premis;
import com.articulorium.harvest.config.HarvestProperties;
import com.articulorium.harvest.core.HarvestException;
import com.articulorium.harvest.source.DublinCore;
import com.articulorium.harvest.source.SourceHttp;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Copies the files of a harvested record into its item, so the repository holds what it presents:
 * the IIIF manifest of the item paints its images and PDF, and the discovery layer shows them in
 * its viewer.
 *
 * <p>The files are the ones the record references ({@code art:harvestFile}, with their names and
 * media types). They go to the item's {@code files} container, an LDP direct container that makes
 * each of them a {@code pcdm:hasFile} of the item (created when the item has none, exactly as the
 * topology service creates it). A file is named after its location, so a file copied before is
 * not copied again, and a copy that failed is tried again the next time the record is harvested.</p>
 *
 * <p>Copying never fails a harvest or a placement: a file that cannot be had (missing, too large,
 * not the announced kind, a host the source does not vouch for) is logged and counted
 * ({@code harvest.files}), and stays referenced. Files are downloaded without credentials, from
 * the hosts the source's settings name only: file addresses come from the source's metadata, which
 * must not be able to make the harvester fetch from anywhere else (the deployment's own network).</p>
 */
public class FileCopier {

    private static final Logger LOG = LoggerFactory.getLogger(FileCopier.class);

    /** Name of the container of an item's PCDM files (the topology service's). */
    public static final String FILES = "files";

    private static final String OCTET_STREAM = "application/octet-stream";

    private final RestClient client;

    private final HarvestProperties.Files settings;

    private final Function<String, Set<String>> hosts;

    private final Consumer<Duration> pause;

    private final MeterRegistry meters;

    private final java.util.concurrent.atomic.AtomicBoolean downloaded = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Creates the copier.
     *
     * @param client   downloads the files (no credentials)
     * @param settings the limits
     * @param hosts    the hosts files of a source may be downloaded from (lower case); empty for a
     *                 source whose files are not copied
     * @param pause    waits between downloads
     * @param meters   the meter registry
     */
    public FileCopier(final RestClient client, final HarvestProperties.Files settings,
            final Function<String, Set<String>> hosts, final Consumer<Duration> pause, final MeterRegistry meters) {
        this.client = client;
        this.settings = settings;
        this.hosts = hosts;
        this.pause = pause;
        this.meters = meters;
    }

    /**
     * Returns the hosts the files of a source may be downloaded from: the host of the source and
     * that of its {@code file-url} option; none when the source does not copy files.
     *
     * @param properties the harvest settings
     * @param sourceId   the source
     * @return the hosts, in lower case
     */
    public static Set<String> hostsOf(final HarvestProperties properties, final String sourceId) {
        final HarvestProperties.Source source = properties.getSources().get(sourceId);
        final Set<String> hosts = new HashSet<>();
        if (source == null || !source.isCopyFiles()) {
            return hosts;
        }
        for (final String address : new String[] {source.getUrl(), source.getOptions().get("file-url")}) {
            if (address == null) {
                continue;
            }
            try {
                final String host = URI.create(address.replaceAll("\\{\\d}", "x").trim()).getHost();
                if (host != null) {
                    hosts.add(host.toLowerCase(Locale.ROOT));
                }
            } catch (IllegalArgumentException e) {
                // the source's own validation reports an address that is no URL
            }
        }
        return hosts;
    }

    /**
     * Copies the files an item references and does not hold yet.
     *
     * @param platform the platform, with the credentials to write with
     * @param sourceId the source of the item's record
     * @param item     the item's path
     * @return how many files were copied
     */
    public int copy(final PlatformClient platform, final String sourceId, final String item) {
        final Set<String> allowed = hosts.apply(sourceId);
        if (allowed.isEmpty()) {
            return 0;
        }
        try {
            return copy(platform, sourceId, item, allowed);
        } catch (HarvestException e) {
            count(sourceId, "failed");
            LOG.warn("The files of /{} could not be copied: {}", item, e.getMessage());
            return 0;
        }
    }

    private int copy(final PlatformClient platform, final String sourceId, final String item, final Set<String> allowed) {
        final Optional<Model> model = platform.rdf(item);
        if (model.isEmpty()) {
            return 0;
        }
        final List<Reference> references = references(model.get(), platform.iri(item));
        if (references.isEmpty()) {
            return 0;
        }
        final String container = ResourcePaths.child(item, FILES);
        final Set<String> held = held(platform, container);
        int copied = 0;
        boolean prepared = held != null;
        for (final Reference reference : references.stream().limit(Math.max(0, settings.getMaxPerRecord())).toList()) {
            final String name = name(reference);
            if (held != null && held.contains(name)) {
                continue;
            }
            final Optional<Download> download = download(sourceId, reference, allowed);
            if (download.isEmpty()) {
                continue;
            }
            if (!prepared) {
                platform.put(container, filesContainer(platform, item, container), Ldp.DIRECT_CONTAINER, true);
                prepared = true;
            }
            final String stored = platform.createBinary(container, name, download.get().content(),
                download.get().mediaType(), reference.filename() == null ? name : reference.filename());
            count(sourceId, "copied");
            copied++;
            LOG.info("Copied {} ({} bytes, {}) of {} to /{}", reference.location(), download.get().content().length,
                download.get().mediaType(), sourceId, stored);
        }
        return copied;
    }

    /** The names in the item's files container; {@code null} when the item has no such container. */
    private static Set<String> held(final PlatformClient platform, final String container) {
        final Optional<Model> model = platform.rdf(container);
        if (model.isEmpty()) {
            return null;
        }
        final Set<String> names = new HashSet<>();
        model.get().listObjectsOfProperty(model.get().createProperty(Ldp.CONTAINS)).forEachRemaining(node -> {
            if (node.isURIResource()) {
                final String iri = node.asResource().getURI();
                names.add(iri.substring(iri.lastIndexOf('/') + 1));
            }
        });
        return names;
    }

    private static String filesContainer(final PlatformClient platform, final String item, final String container) {
        final String subject = "<" + platform.iri(container) + "> ";
        return subject + "<" + Ldp.MEMBERSHIP_RESOURCE + "> <" + platform.iri(item) + "> .\n"
            + subject + "<" + Ldp.HAS_MEMBER_RELATION + "> <" + Pcdm.HAS_FILE + "> .\n"
            + subject + "<" + DublinCore.DCTERMS + "title> \"Files\" .\n";
    }

    private static List<Reference> references(final Model model, final String iri) {
        final List<Reference> references = new ArrayList<>();
        for (final Statement statement : model.createResource(iri)
                .listProperties(model.createProperty(Articulorium.HARVEST_FILE)).toList()) {
            if (!statement.getObject().isURIResource()) {
                continue;
            }
            final Resource file = statement.getObject().asResource();
            references.add(new Reference(file.getURI(), literal(file, Premis.FILENAME), literal(file, Premis.HAS_MIME_TYPE)));
        }
        references.sort(java.util.Comparator.comparing(Reference::location));
        return references;
    }

    private static String literal(final Resource subject, final String predicate) {
        final Statement statement = subject.getProperty(subject.getModel().createProperty(predicate));
        return statement != null && statement.getObject().isLiteral() ? statement.getObject().asLiteral().getLexicalForm()
            : null;
    }

    private Optional<Download> download(final String sourceId, final Reference reference, final Set<String> allowed) {
        final URI location;
        try {
            location = URI.create(reference.location());
        } catch (IllegalArgumentException e) {
            return skip(sourceId, reference, "refused", "its address is no URL");
        }
        final String scheme = location.getScheme() == null ? "" : location.getScheme().toLowerCase(Locale.ROOT);
        final String host = location.getHost() == null ? "" : location.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme) || !allowed.contains(host)) {
            return skip(sourceId, reference, "refused", "it is not on a host of the source (" + allowed + ")");
        }
        if (reference.mediaType() != null && !wanted(reference.mediaType())) {
            return skip(sourceId, reference, "skipped", "its media type " + reference.mediaType() + " is not copied");
        }
        if (downloaded.getAndSet(true)) {
            pause.accept(settings.getDelay());
        }
        final long limit = settings.getMaxSize().toBytes();
        try {
            return client.get().uri(location).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    return skip(sourceId, reference, "failed", "the source answered " + response.getStatusCode().value());
                }
                if (response.getHeaders().getContentLength() > limit) {
                    return skip(sourceId, reference, "too-large", "it has " + response.getHeaders().getContentLength()
                        + " bytes, more than " + settings.getMaxSize());
                }
                final String served = mediaType(response.getHeaders());
                final String type = reference.mediaType() != null ? reference.mediaType() : served;
                if (reference.mediaType() != null && served != null && served.startsWith("text/html")
                        && !reference.mediaType().startsWith("text/")) {
                    // an error or landing page served with 200 instead of the file
                    return skip(sourceId, reference, "failed", "the source answered a web page instead of "
                        + reference.mediaType());
                }
                if (!wanted(type)) {
                    return skip(sourceId, reference, "skipped", "its media type " + type + " is not copied");
                }
                final byte[] content = read(response.getBody(), limit);
                if (content == null) {
                    return skip(sourceId, reference, "too-large", "it has more than " + settings.getMaxSize());
                }
                if (content.length == 0) {
                    return skip(sourceId, reference, "failed", "it is empty");
                }
                return Optional.of(new Download(content, type));
            });
        } catch (RestClientException e) {
            return skip(sourceId, reference, "failed", SourceHttp.describe(e.getMostSpecificCause()));
        }
    }

    private static byte[] read(final java.io.InputStream body, final long limit) throws IOException {
        final byte[] content = body.readNBytes((int) Math.min(Integer.MAX_VALUE - 8L, limit + 1));
        return content.length > limit ? null : content;
    }

    private static String mediaType(final HttpHeaders headers) {
        final MediaType type;
        try {
            type = headers.getContentType();
        } catch (IllegalArgumentException e) {
            return null;
        }
        return type == null ? null : (type.getType() + "/" + type.getSubtype()).toLowerCase(Locale.ROOT);
    }

    private boolean wanted(final String mediaType) {
        if (settings.getMediaTypes().isEmpty()) {
            return true;
        }
        final String type = mediaType == null ? OCTET_STREAM : mediaType.toLowerCase(Locale.ROOT);
        return settings.getMediaTypes().stream().map(pattern -> pattern.trim().toLowerCase(Locale.ROOT))
            .anyMatch(pattern -> pattern.equals(type) || pattern.endsWith("/*")
                && type.startsWith(pattern.substring(0, pattern.length() - 1)));
    }

    private Optional<Download> skip(final String sourceId, final Reference reference, final String outcome,
            final String why) {
        count(sourceId, outcome);
        LOG.warn("The file {} of {} is not copied: {}", reference.location(), sourceId, why);
        return Optional.empty();
    }

    /**
     * Names a file in the files container: its file name (or the end of its address) as a safe
     * resource name, with a hash of the address so two files of one record never share a name and
     * a file is recognised when its record is harvested again.
     */
    static String name(final Reference reference) {
        String base = reference.filename();
        if (base == null || base.isBlank()) {
            final String path = URI.create(reference.location()).getPath();
            base = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
        }
        String extension = "";
        final int dot = base.lastIndexOf('.');
        // an extension has a letter: the end of "2611.17233" is part of an identifier
        if (dot > 0 && base.length() - dot <= 6 && base.substring(dot + 1).matches("[0-9]*[A-Za-z][A-Za-z0-9]*")) {
            extension = base.substring(dot).toLowerCase(Locale.ROOT);
            base = base.substring(0, dot);
        }
        String slug = Normalizer.normalize(base, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 60) {
            slug = slug.substring(0, 60).replaceAll("-+$", "");
        }
        return (slug.isEmpty() ? "file" : slug) + "-" + hash(reference.location()) + extension;
    }

    private static String hash(final String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)), 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void count(final String sourceId, final String outcome) {
        meters.counter("harvest.files", "source", sourceId, "outcome", outcome).increment();
    }

    /**
     * A file a record references.
     *
     * @param location  where the source serves it
     * @param filename  its name, may be {@code null}
     * @param mediaType its media type, may be {@code null}
     */
    record Reference(String location, String filename, String mediaType) {
    }

    private record Download(byte[] content, String mediaType) {
    }

}
