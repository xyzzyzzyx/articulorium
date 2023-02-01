package com.articulorium.discovery.iiif;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.articulorium.discovery.config.DiscoveryClientConfiguration;
import com.articulorium.discovery.config.DiscoveryProperties;
import com.articulorium.discovery.config.ForwardedHeaders;
import com.articulorium.discovery.config.PublicUrls;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * Summarizes the IIIF Presentation export of a resource (the transform service's
 * {@code /iiif/<path>}): whether it is a Manifest or a Collection, its thumbnail and which kinds of
 * media it presents. Resource pages use the summary for their preview image (also shared on social
 * networks) and to decide whether to open the viewer.
 *
 * <p>The export is read with the visitor's credentials and with {@code X-Forwarded-*} headers
 * describing the transform service's public base, so every URL in it is public. Summaries for
 * anonymous visitors are remembered for {@code articulorium.discovery.iiif.cache-seconds}: building
 * an export reads every resource of the object from the platform.</p>
 */
@Component
public class IiifSummaryClient {

    private static final Logger LOG = LoggerFactory.getLogger(IiifSummaryClient.class);

    private final RestClient client;

    private final String base;

    private final HttpHeaders forwarded;

    private final PublicUrls urls;

    private final DiscoveryProperties.Iiif settings;

    private final Clock clock;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * Creates the client.
     *
     * @param builder    the service client builder (load balanced when configured)
     * @param properties the settings
     * @param urls       the public addresses
     */
    @Autowired
    public IiifSummaryClient(@Qualifier(DiscoveryClientConfiguration.SERVICES) final RestClient.Builder builder,
            final DiscoveryProperties properties, final PublicUrls urls) {
        this(builder, properties, urls, Clock.systemUTC());
    }

    /**
     * Creates the client with a clock.
     *
     * @param builder    the service client builder
     * @param properties the settings
     * @param urls       the public addresses
     * @param clock      the clock of the cache
     */
    public IiifSummaryClient(final RestClient.Builder builder, final DiscoveryProperties properties,
            final PublicUrls urls, final Clock clock) {
        this.client = builder.clone().build();
        final String url = properties.getTransformUrl();
        this.base = url.endsWith("/") ? url : url + "/";
        this.forwarded = ForwardedHeaders.of(URI.create(urls.transformBase()));
        this.urls = urls;
        this.settings = properties.getIiif();
        this.clock = clock;
    }

    /**
     * Summarizes the IIIF export of a resource.
     *
     * @param path          the resource path
     * @param authorization the visitor's {@code Authorization} header, {@code null} when anonymous
     * @return the summary, empty when the resource has no export or it cannot be read
     */
    public Optional<IiifSummary> summary(final String path, @Nullable final String authorization) {
        if (!settings.isEnabled()) {
            return Optional.empty();
        }
        final boolean anonymous = !StringUtils.hasText(authorization);
        if (anonymous) {
            final Cached cached = cache.get(path);
            if (cached != null && cached.until().isAfter(clock.instant())) {
                return Optional.ofNullable(cached.summary());
            }
        }
        final IiifSummary summary = read(path, authorization);
        if (anonymous && settings.getCacheSeconds() > 0) {
            if (cache.size() >= settings.getCacheSize()) {
                cache.clear();
            }
            cache.put(path, new Cached(summary, clock.instant().plus(Duration.ofSeconds(settings.getCacheSeconds()))));
        }
        return Optional.ofNullable(summary);
    }

    private @Nullable IiifSummary read(final String path, @Nullable final String authorization) {
        final JsonNode document;
        try {
            document = client.get()
                .uri(URI.create(base + "iiif/" + path))
                .headers(headers -> {
                    headers.addAll(forwarded);
                    if (StringUtils.hasText(authorization)) {
                        headers.set(HttpHeaders.AUTHORIZATION, authorization);
                    }
                })
                .accept(MediaType.APPLICATION_JSON, MediaType.valueOf("application/ld+json"))
                .retrieve()
                .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() >= 500) {
                LOG.warn("The transform service answered {} for the IIIF export of /{}", e.getStatusCode().value(), path);
            }
            return null;
        } catch (RestClientException e) {
            LOG.warn("The IIIF export of /{} cannot be read: {}", path, e.getMessage());
            return null;
        }
        return document == null ? null : summarize(document, urls.iiifUrl(path));
    }

    /**
     * Summarizes an IIIF Presentation 3 document.
     *
     * @param document the Manifest or Collection
     * @param fallbackId its URL, when the document has no {@code id}
     * @return the summary, {@code null} when the document is neither
     */
    static @Nullable IiifSummary summarize(final JsonNode document, final String fallbackId) {
        final String type = document.path("type").asString("");
        if (!"Manifest".equals(type) && !"Collection".equals(type)) {
            return null;
        }
        final String id = document.path("id").isString() ? document.path("id").asString() : fallbackId;
        String thumbnail = null;
        final JsonNode thumbnails = document.path("thumbnail");
        if (thumbnails.isArray() && !thumbnails.isEmpty() && thumbnails.get(0).path("id").isString()) {
            thumbnail = thumbnails.get(0).path("id").asString();
        }
        int images = 0;
        int videos = 0;
        int sounds = 0;
        int documents = 0;
        int canvases = 0;
        if ("Manifest".equals(type)) {
            for (final JsonNode canvas : document.path("items")) {
                canvases++;
                for (final JsonNode page : canvas.path("items")) {
                    for (final JsonNode annotation : page.path("items")) {
                        final JsonNode body = annotation.path("body");
                        switch (body.path("type").asString("")) {
                            case "Image" -> images++;
                            case "Video" -> videos++;
                            case "Sound" -> sounds++;
                            case "Text" -> {
                                if ("application/pdf".equals(body.path("format").asString(""))) {
                                    documents++;
                                }
                            }
                            default -> {
                                // other bodies are not counted
                            }
                        }
                    }
                }
                documents += pdfs(canvas.path("rendering"));
            }
            documents += pdfs(document.path("rendering"));
        }
        final int members = "Collection".equals(type) ? document.path("items").size() : 0;
        return new IiifSummary(id, type, thumbnail, canvases, images, videos, sounds, documents, members);
    }

    private static int pdfs(final JsonNode renderings) {
        int count = 0;
        for (final JsonNode rendering : renderings) {
            if ("application/pdf".equals(rendering.path("format").asString(""))) {
                count++;
            }
        }
        return count;
    }


    private record Cached(@Nullable IiifSummary summary, Instant until) {
    }

}
