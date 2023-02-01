package com.articulorium.oai.catalog;

import java.util.List;

/**
 * One part of a list of records.
 *
 * @param records the records of this part, in list order
 * @param next    where the list continues, or {@code null} when this part completes it
 */
public record RecordList(List<CatalogRecord> records, Cursor next) {

    /**
     * Copies the records.
     *
     * @param records the records
     * @param next    the continuation
     */
    public RecordList {
        records = List.copyOf(records);
    }

}
