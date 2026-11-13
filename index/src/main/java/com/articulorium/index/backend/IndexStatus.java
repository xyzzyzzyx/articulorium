package com.articulorium.index.backend;

/**
 * The condition of a search index.
 *
 * @param available whether the index answers
 * @param status    what the backend reports about itself, {@code OK} when it is healthy
 * @param documents the number of indexed documents, {@code -1} when unknown
 */
public record IndexStatus(boolean available, String status, long documents) {

    /** Status of a healthy index. */
    public static final String OK = "OK";

}
