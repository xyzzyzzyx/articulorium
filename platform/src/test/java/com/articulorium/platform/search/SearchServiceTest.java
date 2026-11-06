package com.articulorium.platform.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

/**
 * Tests the ordering rules of {@link SearchService}.
 */
class SearchServiceTest {

    @Test
    void ordersByTheFieldThenByPathSoTiesAreStable() {
        assertThat(orders(SearchService.sortOf(SearchField.MODIFIED, true)))
            .containsExactly(new Sort.Order(Sort.Direction.ASC, "modified"), new Sort.Order(Sort.Direction.ASC, "path"));
        assertThat(orders(SearchService.sortOf(SearchField.CONTENT_SIZE, false)))
            .containsExactly(new Sort.Order(Sort.Direction.DESC, "binary.size"),
                new Sort.Order(Sort.Direction.DESC, "path"));
    }

    @Test
    void ordersByPathAloneWhenThePathIsTheField() {
        assertThat(orders(SearchService.sortOf(SearchField.FEDORA_ID, true)))
            .containsExactly(new Sort.Order(Sort.Direction.ASC, "path"));
    }

    private static List<Sort.Order> orders(final Sort sort) {
        return sort.stream().toList();
    }

}
