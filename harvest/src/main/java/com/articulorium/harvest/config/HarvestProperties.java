package com.articulorium.harvest.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

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

    /** Classification of harvested records by the AI agent (the {@code ai} service). */
    private Classification classification = new Classification();

    /** Copying the files of harvested records into the repository. */
    private Files files = new Files();

    /**
     * Copying the files of harvested records: a record placed in a collection gets the files its
     * source serves (its PDF, images ...) as PCDM files of its item, so the repository's viewers
     * (the IIIF manifest, the discovery layer) present them. Only for sources with
     * {@code copy-files}; the limits here apply to all of them.
     */
    @Getter
    @Setter
    public static class Files {

        /** Largest file copied; larger files stay referenced only. */
        private DataSize maxSize = DataSize.ofMegabytes(50);

        /** Files copied per record (the first ones the source lists). */
        private int maxPerRecord = 5;

        /** Pause before each download after the first of a harvest, out of consideration for the source. */
        private Duration delay = Duration.ofSeconds(1);

        /**
         * Media types copied ({@code application/pdf}, {@code image/*} ...); empty (the default)
         * for any type.
         */
        private List<String> mediaTypes = new ArrayList<>();

    }

    /**
     * Classification of harvested records: the AI agent suggests the collection and topology of
     * every new record; the suggestion is accepted at once ({@link #autoAccept}) or a person reviews
     * it (the harvest API's review queue) before the record is placed in the community structure.
     */
    @Getter
    @Setter
    public static class Classification {

        /** Whether new records are classified; off by default, harvests then work as without the agent. */
        private boolean enabled;

        /** Where the AI agent is reached (a Consul service name when load balanced). */
        private String aiUrl = "http://articulorium-ai/";

        /** Where the topology API is reached, to build the items of accepted records. */
        private String topologyUrl = "http://articulorium-topology/";

        /**
         * How long the harvester waits for one answer of the agent (a classification, the draft of
         * a topology) before the classification is recorded as failed (and can be retried). A model
         * on a CPU takes minutes per call and a classification is up to two calls; the agent goes
         * on working after a wait that was too short, and later records wait behind it.
         */
        private Duration timeout = Duration.ofMinutes(15);

        /**
         * Whether suggestions are accepted without a person (the default): the record is placed in
         * the suggested existing collection, or in the proposed collection (and community), created
         * below {@link #communities} when no community or collection of that name exists yet.
         * A proposed topology is drafted by the agent and saved ({@link #createTopologies}).
         * Suggestions of no placement, of structure that may not be created ({@code create-*}), or
         * below {@link #autoAcceptThreshold} wait
         * for review, as does a record whose automatic placement fails. {@code false} to review
         * every record. A source may decide for itself ({@code sources.<id>.auto-accept}).
         */
        private boolean autoAccept = true;

        /**
         * Whether automatic acceptance creates the communities the agent proposes; {@code false}
         * leaves suggestions of a new community for review.
         */
        private boolean createCommunities = true;

        /**
         * Whether automatic acceptance creates the collections the agent proposes; {@code false}
         * leaves suggestions of a new collection (and of a new community, which comes with one) for
         * review.
         */
        private boolean createCollections = true;

        /**
         * Whether automatic acceptance creates the topologies the agent proposes: the agent drafts
         * the topology, and a draft the topology API accepts is saved and used for the record's
         * item (and for later records proposing the same topology). {@code false} leaves suggestions
         * of a new topology for review, as does a draft that is not valid.
         */
        private boolean createTopologies = true;

        /**
         * Confidence from which suggestions are accepted automatically (with {@link #autoAccept});
         * unset (the default) for any confidence.
         */
        private Double autoAcceptThreshold;

        /**
         * The container of the community structure, below the LDP root (the platform's
         * {@code articulorium.ldp.communities-path}); proposed communities are created there.
         */
        private String communities = "communities";

        /** Confidence below which a suggestion is flagged as uncertain in the review queue. */
        private double reviewThreshold = 0.5;

    }

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

        /**
         * Whether an incremental harvest starts as soon as the service is ready (after a restart it
         * continues from the checkpoint, so nothing is harvested twice).
         */
        private boolean harvestOnStartup;

        /**
         * How far back an incremental harvest reaches while the source has no checkpoint yet (its
         * first harvest), for example {@code P7D}; unset to harvest every record the first time.
         * Full harvests are not limited.
         */
        private Duration initialWindow;

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

        /**
         * Whether the source's records are classified when classification is enabled; {@code false}
         * for sources whose records do not belong in collections (VIVO profiles).
         */
        private boolean classify = true;

        /**
         * Whether the agent's suggestions for this source's records are accepted without a person,
         * creating the proposed communities, collections and topologies; unset to follow
         * {@code classification.auto-accept}.
         */
        private Boolean autoAccept;

        /**
         * Whether the files of the source's records are copied into the repository when a record
         * is placed in a collection (see {@link Files}); otherwise they are referenced only. Files
         * are downloaded without the source's credentials, from the host of {@link #url} or of the
         * {@code file-url} option only.
         */
        private boolean copyFiles;

    }

}
