package com.articulorium.harvest.core;

/**
 * What a source's protocol supports.
 *
 * @param incremental whether the source can list only the records changed since an instant; when
 *                    not, every harvest is a full harvest
 * @param deletions   whether the source reports deleted records ({@link HarvestedRecord#deleted()}),
 *                    so their ingested copies can be removed
 */
public record SourceCapabilities(boolean incremental, boolean deletions) {

}
