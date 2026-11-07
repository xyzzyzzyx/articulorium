package com.articulorium.ai.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import lombok.Getter;
import lombok.Setter;

/**
 * Settings of the AI agent ({@code articulorium.ai.*}). The chat model itself is configured with
 * Spring AI's own settings ({@code spring.ai.model.chat}, {@code spring.ai.ollama.*},
 * {@code spring.ai.openai.*}).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "articulorium.ai")
public class AiProperties {

    /** Whether the agent answers at all; off by default (the agent is optional). */
    private boolean enabled;

    /** Where the platform's LDP root is reached; with {@link #loadBalanced} the host is a Consul service. */
    private String platformUrl = "http://articulorium-platform/";

    /** Where the topology API is reached. */
    private String topologyUrl = "http://articulorium-topology/";

    /** Whether the URLs above name Consul services resolved by the load balancer. */
    private boolean loadBalanced = true;

    /** Container (below the LDP root) of the community structure. */
    private String communities = "communities";

    /** Container (below the LDP root) of custom topologies; callers need write access to it for topology drafts. */
    private String topologies = "topologies";

    /** Model calls. */
    private Model model = new Model();

    /** Topology suggestions. */
    private Topology topology = new Topology();

    /** Metadata of binaries. */
    private Media media = new Media();

    /** Topic models of communities and collections. */
    private Topics topics = new Topics();

    /** Suggestions for academic profiles. */
    private Profiles profiles = new Profiles();

    /** Classification of harvested records. */
    private Classification classification = new Classification();

    /** Settings of model calls. */
    @Getter
    @Setter
    public static class Model {

        /** Model calls running at once; further calls wait up to {@link #queueTimeout}. */
        private int maxConcurrency = 2;

        /** How long a call waits for a free slot before it is refused as busy. */
        private Duration queueTimeout = Duration.ofSeconds(30);

    }

    /** Settings shared by every capability. */
    @Getter
    @Setter
    public static class Capability {

        /** Whether the capability is offered (when the agent is enabled). */
        private boolean enabled = true;

    }

    /** Settings of topology suggestions. */
    @Getter
    @Setter
    public static class Topology extends Capability {

        /** Children (with their files and media types) of a sample container shown to the model. */
        private int maxSampleResources = 40;

        /** Longest description of the material accepted. */
        private int maxDescriptionLength = 4000;

    }

    /** Settings of media metadata. */
    @Getter
    @Setter
    public static class Media extends Capability {

        /** Largest binary read; larger ones get no technical metadata and are not shown to the model. */
        private DataSize maxBytes = DataSize.ofMegabytes(50);

        /** Largest image sent to the model (vision); larger images are described from their metadata only. */
        private DataSize maxImageBytes = DataSize.ofMegabytes(10);

        /** Whether images are sent to the model (needs a vision model); otherwise only their metadata is. */
        private boolean imageInput = true;

        /** Pages of a PDF whose text is read. */
        private int maxPdfPages = 20;

        /** Characters of extracted text shown to the model. */
        private int maxTextLength = 12000;

    }

    /** Settings of topic models. */
    @Getter
    @Setter
    public static class Topics extends Capability {

        /** Items read for one topic model (breadth first through sub-communities and collections). */
        private int maxDocuments = 100;

        /** Characters of each item's text shown to the model. */
        private int maxDocumentLength = 600;

        /** Largest number of topics. */
        private int maxTopics = 8;

    }

    /** Settings of suggestions for academic (VIVO) profiles. */
    @Getter
    @Setter
    public static class Profiles extends Capability {

        /** Research areas suggested at most. */
        private int maxResearchAreas = 8;

        /** Keywords suggested at most. */
        private int maxKeywords = 12;

        /** Positions, degrees and publications of the profile shown to the model, each. */
        private int maxEntries = 50;

        /** Longest overview suggested (the profile editor accepts up to 10000 characters). */
        private int maxOverviewLength = 2000;

    }

    /** Settings of the classification of harvested records. */
    @Getter
    @Setter
    public static class Classification extends Capability {

        /** Collections offered to the model as candidates. */
        private int maxCandidates = 200;

        /** How long the community structure read for one caller is reused. */
        private Duration catalogTtl = Duration.ofMinutes(1);

        /** Characters of a record's text shown to the model. */
        private int maxRecordLength = 2000;

    }

}
