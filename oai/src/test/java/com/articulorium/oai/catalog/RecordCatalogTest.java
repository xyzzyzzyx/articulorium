package com.articulorium.oai.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.articulorium.domain.vocabulary.Articulorium;
import com.articulorium.domain.vocabulary.Pcdm;
import com.articulorium.oai.FakePlatform;
import com.articulorium.oai.config.OaiProperties;
import com.articulorium.oai.platform.PlatformView;
import com.articulorium.oai.platform.SearchHit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link RecordCatalog} against an in-memory platform.
 */
class RecordCatalogTest {

    private static final PlatformView VIEW = new PlatformView("http://localhost:8000/platform/", null);

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private FakePlatform platform;

    private OaiProperties properties;

    private RecordCatalog catalog;

    @BeforeEach
    void setUp() {
        platform = new FakePlatform();
        properties = new OaiProperties();
        properties.setPageSize(3);
        catalog = new RecordCatalog(platform, properties);
        platform.put("communities", T0, Map.of(), Articulorium.COMMUNITY_ROOT);
        platform.put("communities/arts", T0, Map.of(), Articulorium.COMMUNITY);
        platform.put("communities/arts/paintings", T0, Map.of(), Articulorium.COLLECTION, Pcdm.COLLECTION);
        platform.put("communities/science", T0, Map.of(), Articulorium.COMMUNITY);
    }

    @Test
    void exposesTopLevelObjectsWithTheirSetsAndHidesNestedOnes() {
        object("communities/arts/paintings/mona-lisa", 10);
        platform.put("communities/arts/paintings/mona-lisa/members", at(11), Map.of());
        object("communities/arts/paintings/mona-lisa/members/detail", 12);
        object("loose", 13);

        assertThat(harvest(Selection.ALL)).extracting(CatalogRecord::path)
            .containsExactly("communities/arts/paintings/mona-lisa", "loose");
        assertThat(catalog.find(VIEW, "communities/arts/paintings/mona-lisa")).hasValueSatisfying(
            record -> assertThat(record.setSpecs()).containsExactly("arts", "arts:paintings"));
        assertThat(catalog.find(VIEW, "loose")).hasValueSatisfying(record -> assertThat(record.setSpecs()).isEmpty());
        assertThat(catalog.find(VIEW, "communities/arts/paintings/mona-lisa/members/detail")).isEmpty();
        assertThat(catalog.find(VIEW, "communities/arts")).as("not a record type").isEmpty();
        assertThat(catalog.find(VIEW, "nope")).isEmpty();

        properties.setIncludeNested(true);
        assertThat(harvest(Selection.ALL)).extracting(CatalogRecord::path)
            .containsExactly("communities/arts/paintings/mona-lisa",
                "communities/arts/paintings/mona-lisa/members/detail", "loose");
    }

    @Test
    void pagesThroughTiesHiddenRecordsAndOffsetsInsidePlatformPages() {
        // seven records modified in the same instant, two of them unreadable, then three more
        for (int i = 1; i <= 7; i++) {
            object("tie-" + i, 5);
        }
        platform.hide("tie-2");
        platform.hide("tie-6");
        object("later-1", 6);
        object("later-2", 6);
        object("last", 7);
        platform.hide("last");

        final List<CatalogRecord> records = harvest(Selection.ALL);

        assertThat(records).extracting(CatalogRecord::path).doesNotHaveDuplicates()
            .containsExactly("tie-1", "tie-3", "tie-4", "tie-5", "tie-7", "later-1", "later-2");
    }

    @Test
    void promisesNoMoreWhenOnlyUnreadableOrNestedResultsRemain() {
        object("a", 1);
        object("b", 2);
        object("c", 3);
        object("c/members/page", 4);
        object("d", 5);
        platform.hide("d");

        final RecordList first = catalog.list(VIEW, Selection.ALL, Cursor.START);

        assertThat(first.records()).extracting(CatalogRecord::path).containsExactly("a", "b", "c");
        assertThat(first.next()).as("nothing readable follows").isNull();
    }

    @Test
    void countsHiddenTiesBeforeTheLastHitExactly() {
        // the platform page holds [t1, t2 (hidden), t3]; the next one must start after t3
        object("t1", 5);
        object("t2", 5);
        object("t3", 5);
        object("t4", 5);
        platform.hide("t2");

        assertThat(harvest(Selection.ALL)).extracting(CatalogRecord::path).containsExactly("t1", "t3", "t4");
        assertThat(platform.searches()).anySatisfy(conditions ->
            assertThat(conditions).contains("modified=" + at(5), "fedora_id<=info:fedora/t3"));
    }

    @Test
    void neverSkipsRecordsWhenTheRepositoryChangesDuringAHarvest() {
        for (int i = 1; i <= 9; i++) {
            object(String.format("r%02d", i), i);
        }
        final Set<String> seen = new LinkedHashSet<>();
        Cursor cursor = Cursor.START;
        boolean changed = false;
        do {
            final RecordList part = catalog.list(VIEW, Selection.ALL, cursor);
            part.records().forEach(record -> seen.add(record.path()));
            if (!changed) {
                // after the first part: an answered record and an unanswered one change, one goes away
                platform.touch("r01", at(100));
                platform.touch("r05", at(101));
                platform.remove("r06");
                object("r10", 102);
                changed = true;
            }
            cursor = part.next();
        } while (cursor != null);

        assertThat(seen).contains("r01", "r02", "r03", "r04", "r05", "r07", "r08", "r09", "r10");
    }

    @Test
    void selectsBySetAndModificationTime() {
        object("communities/arts/paintings/mona-lisa", 10);
        object("communities/arts/sculpture", 20);
        object("communities/science/telescope", 30);
        object("loose", 40);

        assertThat(harvest(new Selection("communities/arts", null, null))).extracting(CatalogRecord::path)
            .containsExactly("communities/arts/paintings/mona-lisa", "communities/arts/sculpture");
        assertThat(harvest(new Selection("communities/arts/paintings", null, null))).extracting(CatalogRecord::path)
            .containsExactly("communities/arts/paintings/mona-lisa");
        assertThat(harvest(new Selection(null, at(20), at(40)))).extracting(CatalogRecord::path)
            .containsExactly("communities/arts/sculpture", "communities/science/telescope");
        assertThat(harvest(new Selection(null, at(41), null))).isEmpty();
    }

    @Test
    void readsOnPastPagesOfNestedRecords() {
        object("book", 1);
        for (int i = 1; i <= 8; i++) {
            object("book/members/page-" + i, 1 + i);
        }
        object("pamphlet", 50);

        final RecordList part = catalog.list(VIEW, Selection.ALL, Cursor.START);

        assertThat(part.records()).extracting(CatalogRecord::path).containsExactly("book", "pamphlet");
        assertThat(part.next()).isNull();
    }

    @Test
    void boundsTheReadsSpentFillingAPart() {
        properties.setPageSize(2);
        object("book", 1);
        for (int i = 1; i <= 30; i++) {
            object(String.format("book/members/page-%02d", i), 1 + i);
        }
        object("pamphlet", 50);

        final RecordList first = catalog.list(VIEW, Selection.ALL, Cursor.START);

        assertThat(first.records()).extracting(CatalogRecord::path).containsExactly("book");
        assertThat(first.next()).isNotNull();
        assertThat(harvest(Selection.ALL)).extracting(CatalogRecord::path).containsExactly("book", "pamphlet");
    }

    @Test
    void honoursThePlatformsCapOnPageSizes() {
        properties.setPageSize(5);
        platform.capResults(2);
        for (int i = 1; i <= 7; i++) {
            object("r" + i, i);
        }

        assertThat(harvest(Selection.ALL)).hasSize(7);
    }

    @Test
    void listsCommunitiesAndCollectionsAsSets() {
        platform.put("elsewhere", T0, Map.of(), Articulorium.COLLECTION);

        assertThat(catalog.sets(VIEW)).containsExactly(
            new CatalogSet("communities/arts", "arts"),
            new CatalogSet("communities/arts/paintings", "arts:paintings"),
            new CatalogSet("communities/science", "science"));
        assertThat(catalog.set(VIEW, "communities/arts/paintings")).isPresent();
        assertThat(catalog.set(VIEW, "communities")).isEmpty();
        assertThat(catalog.set(VIEW, "elsewhere")).isEmpty();
        assertThat(catalog.setPath("arts/paintings")).isEqualTo("communities/arts/paintings");
    }

    @Test
    void reportsTheEarliestDatestamp() {
        assertThat(catalog.earliest(VIEW)).isEmpty();
        object("b", 9);
        object("a", 3);
        assertThat(catalog.earliest(VIEW)).hasValue(at(3));
    }

    @Test
    void advancesCursorsPastWhatAPageCovered() {
        final SearchHit a = hit("a", 5);
        final SearchHit b = hit("b", 7);
        final SearchHit c = hit("c", 7);
        final RecordCatalog.TieCounter none = (time, path) -> {
            throw new AssertionError("no count expected");
        };
        // nothing readable: the page is skipped as a whole
        assertThat(RecordCatalog.advance(new Cursor(at(1), 2), List.of(), 3, none)).isEqualTo(new Cursor(at(1), 5));
        // continue at the last time, past every resource with that time up to the last hit
        assertThat(RecordCatalog.advance(Cursor.START, List.of(a, b, c), 3, (time, path) -> {
            assertThat(time).isEqualTo(at(7));
            assertThat(path).isEqualTo("c");
            return 3;
        })).isEqualTo(new Cursor(at(7), 3));
        // a page whose last hit has the anchor's time is counted whole
        assertThat(RecordCatalog.advance(new Cursor(at(7), 2), List.of(c), 3, none)).isEqualTo(new Cursor(at(7), 5));
    }

    private List<CatalogRecord> harvest(final Selection selection) {
        final List<CatalogRecord> records = new ArrayList<>();
        Cursor cursor = Cursor.START;
        int parts = 0;
        do {
            final RecordList part = catalog.list(VIEW, selection, cursor);
            records.addAll(part.records());
            cursor = part.next();
            assertThat(++parts).as("harvest terminates").isLessThan(100);
        } while (cursor != null);
        return records;
    }

    private void object(final String path, final int seconds) {
        platform.put(path, at(seconds), Map.of(), Pcdm.OBJECT);
    }

    private static SearchHit hit(final String path, final int seconds) {
        return new SearchHit(path, at(seconds), Set.of(Pcdm.OBJECT));
    }

    private static Instant at(final int seconds) {
        return T0.plusSeconds(seconds);
    }

}
