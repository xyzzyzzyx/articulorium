package com.articulorium.oai.catalog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.articulorium.domain.rdf.ResourcePaths;
import com.articulorium.domain.vocabulary.Articulorium;
import com.articulorium.oai.config.OaiProperties;
import com.articulorium.oai.platform.Conditions;
import com.articulorium.oai.platform.PlatformClient;
import com.articulorium.oai.platform.PlatformView;
import com.articulorium.oai.platform.SearchHit;
import com.articulorium.oai.platform.SearchPage;
import com.articulorium.oai.protocol.SetSpecs;

import org.springframework.stereotype.Component;

/**
 * What the repository exposes over OAI-PMH, read from the platform's search API.
 *
 * <ul>
 *   <li><b>Records</b> are the resources typed {@code articulorium.oai.record-type}
 *       ({@code pcdm:Object} by default), except those nested in another record (the pages of a
 *       book) unless {@code include-nested} is set. A record's datestamp is its modification
 *       time.</li>
 *   <li><b>Sets</b> are the communities ({@code art:Community}) and collections
 *       ({@code art:Collection}) below the communities container; a record belongs to every
 *       community and collection it lies in, so harvesting a community also harvests its
 *       sub-communities and collections.</li>
 * </ul>
 *
 * <p>Lists are ordered by modification time and path and paged with {@link Cursor}s. The platform
 * leaves out results the harvester may not read, so a search page may hold fewer hits than it
 * covers; the cursor arithmetic below accounts for that.</p>
 */
@Component
public class RecordCatalog {

    /** Searches read to fill a part of a list once it holds at least one record. */
    static final int FILL_READS = 10;

    private final PlatformClient platform;

    private final OaiProperties properties;

    private final String communitiesPath;

    /**
     * Creates the catalog.
     *
     * @param platform   the platform client
     * @param properties OAI-PMH settings
     */
    public RecordCatalog(final PlatformClient platform, final OaiProperties properties) {
        this.platform = platform;
        this.properties = properties;
        this.communitiesPath = ResourcePaths.normalize(properties.getCommunitiesPath());
    }

    /**
     * Returns the modification time of the earliest record, a lower bound of every datestamp.
     *
     * @param view the harvester's view
     * @return the time, or empty when there are no records
     */
    public Optional<Instant> earliest(final PlatformView view) {
        final List<String> conditions = List.of(Conditions.type(properties.getRecordType()));
        final int size = Math.max(1, properties.getPageSize());
        int offset = 0;
        // the earliest resources may be unreadable for this harvester: read past them
        for (int reads = 0; reads < FILL_READS; reads++) {
            final SearchPage page = platform.search(view, conditions, size, offset);
            if (!page.hits().isEmpty()) {
                return Optional.of(page.hits().getFirst().modified());
            }
            offset += page.covered(offset);
            if (offset >= page.total()) {
                break;
            }
        }
        return Optional.empty();
    }

    /**
     * Finds one record.
     *
     * @param view the harvester's view
     * @param path the resource path
     * @return the record, or empty when the resource does not exist, may not be read or is not a
     *         record
     */
    public Optional<CatalogRecord> find(final PlatformView view, final String path) {
        final Lookups lookups = new Lookups(view);
        return lookups.get(path)
            .filter(hit -> hit.hasType(properties.getRecordType()))
            .flatMap(hit -> toRecord(hit, lookups));
    }

    /**
     * Lists one part of the selected records.
     *
     * @param view      the harvester's view
     * @param selection which records
     * @param cursor    where to continue
     * @return the part; never empty unless the selection is exhausted
     */
    public RecordList list(final PlatformView view, final Selection selection, final Cursor cursor) {
        final List<String> conditions = new ArrayList<>();
        conditions.add(Conditions.type(properties.getRecordType()));
        if (selection.setPath() != null) {
            conditions.add(Conditions.below(selection.setPath()));
        }
        if (selection.from() != null) {
            conditions.add(Conditions.modifiedFrom(selection.from()));
        }
        if (selection.untilExclusive() != null) {
            conditions.add(Conditions.modifiedBefore(selection.untilExclusive()));
        }
        final int size = Math.max(1, properties.getPageSize());
        final Lookups lookups = new Lookups(view);
        final List<CatalogRecord> records = new ArrayList<>();
        Cursor position = cursor;
        boolean more = true;
        int reads = 0;
        // read on until the part is full: search pages may hold nested or unreadable resources.
        // An empty part is never answered while more results follow; a partly filled one is after
        // FILL_READS reads, to bound the time one response takes.
        while (more && records.size() < size && (records.isEmpty() || reads < FILL_READS)) {
            reads++;
            final Step step = step(view, conditions, position, size - records.size(), lookups);
            records.addAll(step.records());
            more = step.more();
            position = step.next();
        }
        // look ahead before promising more: when every remaining result is nested or unreadable,
        // a resumption token would only lead to an empty answer
        Cursor ahead = position;
        for (int probes = 0; more && !records.isEmpty() && probes < FILL_READS; probes++) {
            final Step step = step(view, conditions, ahead, size, lookups);
            if (!step.records().isEmpty()) {
                break;
            }
            more = step.more();
            ahead = step.next();
        }
        return new RecordList(records, more ? position : null);
    }

    /**
     * Reads one search page at a cursor.
     *
     * @param view       the harvester's view
     * @param conditions the selection's conditions
     * @param position   where to read
     * @param max        the page size
     * @param lookups    ancestors looked up so far
     * @return the records of the page, whether results follow and where they continue
     */
    private Step step(final PlatformView view, final List<String> conditions, final Cursor position, final int max,
            final Lookups lookups) {
        final List<String> query = new ArrayList<>(conditions);
        if (position.anchor() != null) {
            query.add(Conditions.modifiedFrom(position.anchor()));
        }
        final SearchPage page = platform.search(view, query, max, position.offset());
        final int covered = page.covered(position.offset());
        final boolean more = position.offset() + covered < page.total();
        final List<CatalogRecord> records = new ArrayList<>();
        for (final SearchHit hit : page.hits()) {
            toRecord(hit, lookups).ifPresent(records::add);
        }
        final Cursor next = !more ? position : advance(position, page.hits(), covered, (time, path) -> {
            final List<String> ties = new ArrayList<>(conditions);
            ties.add(Conditions.modifiedAt(time));
            ties.add(Conditions.pathAtMost(path));
            return platform.search(view, ties, 1, 0).total();
        });
        return new Step(records, more, next);
    }

    /**
     * One search page read.
     *
     * @param records the records it held
     * @param more    whether results follow
     * @param next    where they continue
     */
    private record Step(List<CatalogRecord> records, boolean more, Cursor next) {
    }

    /**
     * Counts the selected resources modified at an instant whose path sorts at or before a path,
     * readable or not.
     */
    @FunctionalInterface
    interface TieCounter {

        /**
         * Counts.
         *
         * @param time the modification time
         * @param path the path
         * @return the number of resources
         */
        long count(Instant time, String path);

    }

    /**
     * Lists every set, ordered by path.
     *
     * @param view the harvester's view
     * @return the sets (at most {@code max-sets})
     */
    public List<CatalogSet> sets(final PlatformView view) {
        final Map<String, CatalogSet> sets = new LinkedHashMap<>();
        for (final String type : List.of(Articulorium.COMMUNITY, Articulorium.COLLECTION)) {
            final List<String> conditions = List.of(Conditions.type(type), Conditions.below(communitiesPath));
            final int size = Math.max(1, properties.getPageSize());
            int offset = 0;
            SearchPage page;
            do {
                page = platform.search(view, conditions, size, offset);
                for (final SearchHit hit : page.hits()) {
                    sets.putIfAbsent(hit.path(), new CatalogSet(hit.path(), specOf(hit.path())));
                }
                offset += page.covered(offset);
            } while (offset < page.total() && sets.size() < properties.getMaxSets());
        }
        return sets.values().stream()
            .sorted(Comparator.comparing(CatalogSet::path))
            .limit(properties.getMaxSets())
            .toList();
    }

    /**
     * Finds the set with a setSpec.
     *
     * @param view the harvester's view
     * @param path the path the setSpec stands for
     * @return the set, or empty when no readable community or collection has the path
     */
    public Optional<CatalogSet> set(final PlatformView view, final String path) {
        return new Lookups(view).get(path)
            .filter(this::isSet)
            .map(hit -> new CatalogSet(hit.path(), specOf(hit.path())));
    }

    /**
     * Returns the path a setSpec stands for.
     *
     * @param relativePath the path relative to the communities container
     * @return the resource path
     */
    public String setPath(final String relativePath) {
        final String relative = ResourcePaths.normalize(relativePath);
        return communitiesPath.isEmpty() ? relative : communitiesPath + "/" + relative;
    }

    /**
     * Moves a cursor past a search page.
     *
     * <p>When no hit is readable, or the last readable hit has the anchor's own time, the whole page
     * is counted against the same anchor. Otherwise the list continues at the last readable hit's
     * time, past every selected resource with that time up to and including that hit; those are
     * counted (readable or not) because access control may hide some of them. Results after the
     * last readable hit are all unreadable, so reading them again answers nothing: no record is
     * repeated or skipped.</p>
     *
     * @param cursor  the cursor the page was read at
     * @param hits    the readable hits of the page
     * @param covered the number of results the page covers
     * @param ties    counts the selected resources with a time up to a path
     * @return the cursor after the page
     */
    static Cursor advance(final Cursor cursor, final List<SearchHit> hits, final int covered, final TieCounter ties) {
        if (hits.isEmpty()) {
            return new Cursor(cursor.anchor(), cursor.offset() + covered);
        }
        final SearchHit last = hits.getLast();
        if (last.modified().equals(cursor.anchor())) {
            return new Cursor(cursor.anchor(), cursor.offset() + covered);
        }
        return new Cursor(last.modified(), Math.toIntExact(ties.count(last.modified(), last.path())));
    }

    private Optional<CatalogRecord> toRecord(final SearchHit hit, final Lookups lookups) {
        final List<String> setSpecs = new ArrayList<>();
        String ancestor = ResourcePaths.parentOf(hit.path()).orElse("");
        final List<String> ancestors = new ArrayList<>();
        while (!ancestor.isEmpty()) {
            ancestors.addFirst(ancestor);
            ancestor = ResourcePaths.parentOf(ancestor).orElse("");
        }
        for (final String path : ancestors) {
            final boolean inSets = isBelowCommunities(path);
            if (!inSets && properties.isIncludeNested()) {
                continue;
            }
            final Optional<SearchHit> found = lookups.get(path);
            if (found.isEmpty()) {
                continue;
            }
            if (!properties.isIncludeNested() && found.get().hasType(properties.getRecordType())) {
                return Optional.empty();
            }
            if (inSets && isSet(found.get())) {
                setSpecs.add(specOf(path));
            }
        }
        return Optional.of(new CatalogRecord(hit.path(), hit.modified(), setSpecs));
    }

    private boolean isSet(final SearchHit hit) {
        return isBelowCommunities(hit.path())
            && (hit.hasType(Articulorium.COMMUNITY) || hit.hasType(Articulorium.COLLECTION));
    }

    private boolean isBelowCommunities(final String path) {
        return !path.equals(communitiesPath) && ResourcePaths.isSelfOrDescendant(path, communitiesPath);
    }

    private String specOf(final String path) {
        return SetSpecs.encode(communitiesPath.isEmpty() ? path : path.substring(communitiesPath.length() + 1));
    }

    /**
     * Resources looked up by path during one request.
     */
    private final class Lookups {

        private final PlatformView view;

        private final Map<String, Optional<SearchHit>> cache = new HashMap<>();

        Lookups(final PlatformView view) {
            this.view = view;
        }

        Optional<SearchHit> get(final String path) {
            return cache.computeIfAbsent(path, key -> platform.search(view, List.of(Conditions.path(key)), 1, 0)
                .hits().stream()
                .filter(hit -> hit.path().equals(key))
                .findFirst());
        }

    }

}
