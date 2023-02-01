package com.articulorium.ai.classification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.articulorium.ai.config.AiProperties;
import com.articulorium.ai.model.Prompts;
import com.articulorium.ai.platform.Descriptions;
import com.articulorium.ai.platform.PlatformReader;
import com.articulorium.ai.topology.TopologyClient;
import com.articulorium.ai.topology.TopologySummary;
import com.articulorium.domain.rdf.ResourcePaths;
import com.articulorium.domain.vocabulary.Articulorium;

import org.apache.jena.rdf.model.Model;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The community structure (communities, sub-communities and collections, with their titles,
 * descriptions and subjects) and the topologies, as one caller may read them: what a harvested
 * record is classified against.
 *
 * <p>Reading the structure takes one request per community and collection, so a catalog is kept for
 * {@code articulorium.ai.classification.catalog-ttl} per caller (keyed by a hash of its credentials,
 * never the credentials themselves): a harvest classifies many records in a row with the same
 * credentials.</p>
 */
@Component
public class StructureCatalog {

    private final PlatformReader platform;

    private final TopologyClient topologies;

    private final AiProperties properties;

    private final Clock clock;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * Creates the catalog.
     *
     * @param platform   the repository
     * @param topologies the topology API
     * @param properties the agent settings
     * @param clock      the clock
     */
    public StructureCatalog(final PlatformReader platform, final TopologyClient topologies,
            final AiProperties properties, final Clock clock) {
        this.platform = platform;
        this.topologies = topologies;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Returns the structure and topologies a caller may read.
     *
     * @param authorization the caller's {@code Authorization} header
     * @return the catalog
     */
    public Catalog catalog(@Nullable final String authorization) {
        final String key = key(authorization);
        final Instant now = clock.instant();
        final Cached cached = cache.get(key);
        if (cached != null && now.isBefore(cached.expires())) {
            return cached.catalog();
        }
        final Catalog catalog = new Catalog(structure(authorization), topologies.list(authorization));
        cache.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expires()));
        cache.put(key, new Cached(catalog, now.plus(properties.getClassification().getCatalogTtl())));
        return catalog;
    }

    private List<Entry> structure(@Nullable final String authorization) {
        final List<Entry> entries = new ArrayList<>();
        final String rootPath = ResourcePaths.normalize(properties.getCommunities());
        final Optional<Model> root = platform.rdf(rootPath, authorization);
        if (root.isEmpty()) {
            return entries;
        }
        final int maxCollections = Math.max(1, properties.getClassification().getMaxCandidates());
        final int maxReads = maxCollections * 4;
        int reads = 0;
        int collections = 0;
        int communities = 0;
        final Deque<Visit> queue = new ArrayDeque<>();
        queue.add(new Visit(rootPath, root.get(), null, ""));
        while (!queue.isEmpty() && reads < maxReads && collections < maxCollections) {
            final Visit visit = queue.poll();
            for (final String child : Descriptions.children(visit.model(), platform.iri(visit.path()))) {
                if (reads++ >= maxReads || collections >= maxCollections) {
                    break;
                }
                final Optional<String> childPath = platform.pathOf(child);
                if (childPath.isEmpty()) {
                    continue;
                }
                final Optional<Model> rdf = platform.rdf(childPath.get(), authorization);
                if (rdf.isEmpty()) {
                    continue;
                }
                final Model model = rdf.get();
                final boolean community = Descriptions.hasType(model, child, Articulorium.COMMUNITY);
                final boolean collection = Descriptions.hasType(model, child, Articulorium.COLLECTION);
                if (!community && !collection) {
                    continue;
                }
                final String title = Descriptions.title(model, child);
                final String label = title.isEmpty() ? ResourcePaths.nameOf(childPath.get()) : title;
                final String trail = visit.trail().isEmpty() ? label : visit.trail() + " > " + label;
                final String description = String.join(" ", Descriptions.descriptions(model, child));
                final Entry entry = new Entry(community ? "m" + ++communities : "c" + ++collections,
                    community ? Kind.COMMUNITY : Kind.COLLECTION, childPath.get(), label, trail,
                    Prompts.clip(description, 300), Descriptions.subjects(model, child).stream().limit(12).toList(),
                    visit.parent());
                entries.add(entry);
                if (community) {
                    queue.add(new Visit(childPath.get(), model, entry.path(), trail));
                }
            }
        }
        return entries;
    }

    private static String key(@Nullable final String authorization) {
        if (authorization == null) {
            return "anonymous";
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(authorization.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Community or collection. */
    public enum Kind {

        /** A community or sub-community. */
        COMMUNITY,

        /** A collection. */
        COLLECTION

    }

    /**
     * A community or collection.
     *
     * @param id          its short id in prompts ({@code m1} for communities, {@code c1} for collections)
     * @param kind        community or collection
     * @param path        its path
     * @param title       its title
     * @param trail       its titles from the top, for example {@code Library > Theses}
     * @param description its description
     * @param subjects    its subjects (topics)
     * @param parent      the path of its community, {@code null} at the top
     */
    public record Entry(String id, Kind kind, String path, String title, String trail, String description,
            List<String> subjects, @Nullable String parent) {
    }

    /**
     * What a record is classified against.
     *
     * @param structure  the communities and collections
     * @param topologies the topologies
     */
    public record Catalog(List<Entry> structure, List<TopologySummary> topologies) {

        /**
         * Finds an entry by its prompt id.
         *
         * @param id   the id
         * @param kind the kind expected
         * @return the entry, or empty
         */
        public Optional<Entry> find(final String id, final Kind kind) {
            return id == null ? Optional.empty()
                : structure.stream().filter(entry -> entry.kind() == kind && entry.id().equals(id.strip())).findFirst();
        }

    }

    private record Visit(String path, Model model, @Nullable String parent, String trail) {
    }

    private record Cached(Catalog catalog, Instant expires) {
    }

}
