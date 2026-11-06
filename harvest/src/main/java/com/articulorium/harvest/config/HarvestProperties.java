package com.articulorium.harvest.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Settings of the harvest service ({@code articulorium.harvest.*}).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "articulorium.harvest")
public class HarvestProperties {

    /** Where this service reaches the platform's LDP root (a Consul service name when load balanced). */
    private String platformUrl = "http://articulorium-platform/";

    /** Whether {@link #platformUrl} names a Consul service resolved by the load balancer. */
    private boolean loadBalanced = true;

    /**
     * Container (below the LDP root) holding the harvested records: one container per source,
     * {@code <container>/<source id>/<record>}.
     */
    private String container = "harvested";

    /** How the harvester authenticates to the platform. */
    private PlatformAuth platformAuth = new PlatformAuth();

    /** How failures are retried and checkpoints moved. */
    private Policy policy = new Policy();

    /** HTTP settings of the requests to the sources. */
    private Http http = new Http();

    /** Reports kept per source (the most recent first). */
    private int history = 10;

    /** Sources harvested at the same time; further harvests wait. */
    private int concurrency = 2;

    /** The sources, by identifier. */
    private Map<String, Source> sources = new LinkedHashMap<>();

    /**
     * Credentials of the harvester towards the platform: a fixed {@code Authorization} header, or an
     * OAuth 2.0 client credentials grant (a Keycloak service account), or none when the platform's
     * access control lets anyone write the container.
     */
    @Getter
    @Setter
    @ToString(exclude = { "authorization", "clientSecret" })
    public static class PlatformAuth {

        /** A fixed {@code Authorization} header value ({@code Bearer <Consul token>}). */
        private String authorization;

        /** The token endpoint of the client credentials grant. */
        private String tokenUri;

        /** The client identifier of the client credentials grant. */
        private String clientId;

        /** The client secret of the client credentials grant. */
        private String clientSecret;

        /** The scope requested with the client credentials grant, may be empty. */
        private String scope;

    }

    /** Failure handling. */
    @Getter
    @Setter
    public static class Policy {

        /** Attempts of one page fetch or record ingest before giving up. */
        private int maxAttempts = 3;

        /** Delay before the first retry, doubled for every further retry. */
        private Duration backoff = Duration.ofSeconds(2);

        /** Longest delay between retries (also caps {@code Retry-After}). */
        private Duration maxBackoff = Duration.ofMinutes(1);

        /** Failed records after which a harvest stops (0 for no limit). */
        private int maxFailures = 100;

        /** Failed records listed in a report. */
        private int maxReportedFailures = 100;

        /** How far before a harvest's start the next incremental harvest starts. */
        private Duration overlap = Duration.ofMinutes(5);

    }

    /** HTTP settings towards the sources. */
    @Getter
    @Setter
    public static class Http {

        /** Connection timeout. */
        private Duration connectTimeout = Duration.ofSeconds(10);

        /** Read timeout of one response. */
        private Duration readTimeout = Duration.ofMinutes(2);

        /** The {@code User-Agent} sent to the sources. */
        private String userAgent = "Articulorium-Harvester/1.0 (+https://github.com/xyzzyzzyx/articulorium)";

    }

    /** One source. */
    @Getter
    @Setter
    @ToString(exclude = { "password", "token" })
    public static class Source {

        /** The type: {@code oai-pmh}, {@code dspace}, {@code fedora} or {@code vivo}. */
        private String type;

        /** The endpoint (OAI-PMH base URL, DSpace REST API, Fedora LDP base, VIVO base). */
        private String url;

        /** Whether the source may be harvested. */
        private boolean enabled = true;

        /** A cron expression (seconds first) for incremental harvests, or empty to harvest on demand only. */
        private String schedule;

        /** Records asked for per page, where the protocol lets the client choose. */
        private int pageSize = 100;

        /** User name (HTTP Basic; VIVO account e-mail). */
        private String username;

        /** Password. */
        private String password;

        /** Bearer token. */
        private String token;

        /** Type-specific options (see the module README). */
        private Map<String, String> options = new LinkedHashMap<>();

    }

}
