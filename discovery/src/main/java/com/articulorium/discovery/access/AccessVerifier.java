package com.articulorium.discovery.access;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import com.articulorium.discovery.config.DiscoveryClientConfiguration;
import com.articulorium.discovery.config.DiscoveryProperties;
import com.articulorium.discovery.config.ForwardedHeaders;
import com.articulorium.discovery.config.PublicUrls;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Decides which resources a visitor may see, with the platform's Web Access Control: the visitor's
 * own credentials (its {@code Authorization} header, passed on unchanged; none for anonymous
 * visitors and for the server-side rendering of public pages) are presented to the platform's
 * {@code /.well-known/webac} endpoint, and a resource is shown when they grant {@code read}.
 *
 * <p>The Solr collection holds every resource regardless of its access rules, so every search hit
 * and every resource page is checked. Answers for anonymous visitors are remembered for
 * {@code articulorium.discovery.access.anonymous-cache-seconds}, which bounds how long a resource
 * stays visible after it was made private. Unreadable resources, refusals and an unavailable
 * platform all mean "not visible": nothing is shown that the platform did not allow.</p>
 */
@Component
public class AccessVerifier implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(AccessVerifier.class);

    /** Path of the platform's access information endpoint, below the LDP root. */
    static final String WEBAC_PATH = ".well-known/webac";

    private final RestClient client;

    private final String base;

    private final HttpHeaders forwarded;

    private final DiscoveryProperties.Access settings;

    private final Clock clock;

    private final Map<String, Instant> anonymousReadable = new ConcurrentHashMap<>();

    private final Map<String, Instant> anonymousHidden = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Creates the verifier.
     *
     * @param builder    the platform client builder (load balanced when configured)
     * @param properties the settings
     * @param urls       the public addresses
     */
    @Autowired
    public AccessVerifier(@Qualifier(DiscoveryClientConfiguration.SERVICES) final RestClient.Builder builder,
            final DiscoveryProperties properties, final PublicUrls urls) {
        this(builder, properties, urls, Clock.systemUTC());
    }

    /**
     * Creates the verifier with a clock.
     *
     * @param builder    the platform client builder
     * @param properties the settings
     * @param urls       the public addresses
     * @param clock      the clock of the cache
     */
    public AccessVerifier(final RestClient.Builder builder, final DiscoveryProperties properties,
            final PublicUrls urls, final Clock clock) {
        this.client = builder.clone().build();
        final String url = properties.getPlatformUrl();
        this.base = url.endsWith("/") ? url : url + "/";
        this.forwarded = ForwardedHeaders.of(URI.create(urls.platformBase()));
        this.settings = properties.getAccess();
        this.clock = clock;
    }

    /**
     * Tells whether a visitor may read a resource.
     *
     * @param path          the resource path
     * @param authorization the visitor's {@code Authorization} header, {@code null} when anonymous
     * @return {@code true} when the platform grants {@code read}
     */
    public boolean mayRead(final String path, @Nullable final String authorization) {
        if (!settings.isVerify()) {
            return true;
        }
        final boolean anonymous = !StringUtils.hasText(authorization);
        if (anonymous) {
            final Instant now = clock.instant();
            final Instant readable = anonymousReadable.get(path);
            if (readable != null && readable.isAfter(now)) {
                return true;
            }
            final Instant hidden = anonymousHidden.get(path);
            if (hidden != null && hidden.isAfter(now)) {
                return false;
            }
        }
        final boolean allowed = ask(path, authorization);
        if (anonymous && settings.getAnonymousCacheSeconds() > 0) {
            remember(path, allowed);
        }
        return allowed;
    }

    /**
     * Keeps the resources a visitor may read, checking several at once.
     *
     * @param paths         the resource paths, in order
     * @param authorization the visitor's {@code Authorization} header, {@code null} when anonymous
     * @return the readable paths, in the same order
     */
    public List<String> readable(final Collection<String> paths, @Nullable final String authorization) {
        if (!settings.isVerify() || paths.isEmpty()) {
            return List.copyOf(paths);
        }
        final Semaphore permits = new Semaphore(Math.max(1, settings.getParallelism()));
        final Map<String, Future<Boolean>> checks = new LinkedHashMap<>();
        for (final String path : paths) {
            checks.putIfAbsent(path, executor.submit(() -> {
                permits.acquire();
                try {
                    return mayRead(path, authorization);
                } finally {
                    permits.release();
                }
            }));
        }
        final List<String> readable = new ArrayList<>();
        for (final Map.Entry<String, Future<Boolean>> check : checks.entrySet()) {
            try {
                if (Boolean.TRUE.equals(check.getValue().get())) {
                    readable.add(check.getKey());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return readable;
            } catch (ExecutionException e) {
                LOG.warn("Access check of /{} failed: {}", check.getKey(), e.getCause().getMessage());
            }
        }
        return readable;
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    private boolean ask(final String path, @Nullable final String authorization) {
        final URI uri = UriComponentsBuilder.fromUriString(base + WEBAC_PATH)
            .queryParam("resource", path).encode().build().toUri();
        try {
            final JsonNode body = client.get().uri(uri)
                .headers(headers -> {
                    headers.addAll(forwarded);
                    if (StringUtils.hasText(authorization)) {
                        headers.set(HttpHeaders.AUTHORIZATION, authorization);
                    }
                })
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);
            if (body == null || !body.path("userModes").isArray()) {
                LOG.warn("The platform's access check of /{} answered no access modes", path);
                return false;
            }
            for (final JsonNode mode : body.path("userModes")) {
                if (Set.of("read", "write").contains(mode.asString())) {
                    return true;
                }
            }
            return false;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() >= 500) {
                LOG.warn("The platform answered {} to the access check of /{}", e.getStatusCode().value(), path);
            }
            return false;
        } catch (RestClientException e) {
            LOG.warn("The platform cannot be reached for the access check of /{}: {}", path, e.getMessage());
            return false;
        }
    }

    private void remember(final String path, final boolean allowed) {
        if (anonymousReadable.size() + anonymousHidden.size() >= settings.getCacheSize()) {
            final Instant now = clock.instant();
            anonymousReadable.values().removeIf(until -> !until.isAfter(now));
            anonymousHidden.values().removeIf(until -> !until.isAfter(now));
            if (anonymousReadable.size() + anonymousHidden.size() >= settings.getCacheSize()) {
                anonymousReadable.clear();
                anonymousHidden.clear();
            }
        }
        final Instant until = clock.instant().plus(Duration.ofSeconds(settings.getAnonymousCacheSeconds()));
        (allowed ? anonymousReadable : anonymousHidden).put(path, until);
        (allowed ? anonymousHidden : anonymousReadable).remove(path);
    }


}
