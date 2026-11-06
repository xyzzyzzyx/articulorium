package com.articulorium.harvest.source.dspace;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.articulorium.harvest.core.HarvestException;
import com.articulorium.harvest.core.HarvestPage;
import com.articulorium.harvest.core.HarvestSource;
import com.articulorium.harvest.core.HarvestWindow;
import com.articulorium.harvest.core.HarvestedFile;
import com.articulorium.harvest.core.HarvestedRecord;
import com.articulorium.harvest.core.RecordFailure;
import com.articulorium.harvest.core.SourceCapabilities;
import com.articulorium.harvest.core.SourceSettings;
import com.articulorium.harvest.source.DublinCore;
import com.articulorium.harvest.source.KeysetCursor;
import com.articulorium.harvest.source.SourceHttp;
import com.articulorium.harvest.source.Timestamps;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Harvests the items of a DSpace 7 or later repository through its REST API.
 *
 * <ul>
 * <li>Items are listed with the discovery search ({@code discover/search/objects?dsoType=ITEM}),
 * ordered by {@code lastModified}, optionally within one community or collection
 * ({@code scope}).</li>
 * <li>Paging is keyset paging on {@code lastModified} ({@link KeysetCursor}): items changed while a
 * harvest runs never shift the rest of the listing.</li>
 * <li>Incremental harvests add a {@code lastModified} range query.</li>
 * <li>Dublin Core fields ({@code dc.*}, {@code dcterms.*}) map to {@code dcterms:}
 * ({@link DublinCore}); the subject is the item's handle URL, or its REST URL without a handle.</li>
 * <li>Withdrawn items (visible to administrators) become deleted records. DSpace does not list
 * deleted items, so their copies stay until removed by hand.</li>
 * <li>With {@code include-files}, the bitstreams of the {@code ORIGINAL} bundle are referenced as
 * files (one more request per item).</li>
 * </ul>
 */
public class DSpaceSource implements HarvestSource {

    private static final String HAL = "application/hal+json, application/json;q=0.9";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SourceSettings settings;

    private final SourceHttp http;

    private final DSpaceOptions options;

    /**
     * Creates the source.
     *
     * @param settings the settings
     * @param http     the HTTP client
     * @param options  the DSpace options
     */
    DSpaceSource(final SourceSettings settings, final SourceHttp http, final DSpaceOptions options) {
        this.settings = settings;
        this.http = http;
        this.options = options;
    }

    @Override
    public String id() {
        return settings.id();
    }

    @Override
    public String type() {
        return settings.type();
    }

    @Override
    public SourceCapabilities capabilities() {
        return new SourceCapabilities(true, false);
    }

    @Override
    public HarvestPage fetch(final HarvestWindow window, final String cursor) {
        final KeysetCursor position = cursor == null ? KeysetCursor.start(window.from()) : KeysetCursor.decode(cursor);
        final int size = settings.pageSize();
        final StringBuilder uri = new StringBuilder(settings.base()).append("discover/search/objects?dsoType=ITEM")
            .append("&sort=").append(encode(options.sortField() + ",ASC"))
            .append("&page=").append(position.skip() / size)
            .append("&size=").append(size);
        options.scope().ifPresent(scope -> uri.append("&scope=").append(encode(scope)));
        if (position.time() != null) {
            uri.append("&query=").append(encode(options.incrementalQuery().replace("{from}", position.time().toString())));
        }
        final JsonNode body = json(http.get("search items", URI.create(uri.toString()), HAL), "search items");
        final JsonNode result = body.path("_embedded").path("searchResult");
        final JsonNode objects = result.path("_embedded").path("objects");

        final List<HarvestedRecord> records = new ArrayList<>();
        final List<RecordFailure> failures = new ArrayList<>();
        final List<Instant> times = new ArrayList<>();
        final List<String> ids = new ArrayList<>();
        Instant previous = null;
        for (final JsonNode object : objects) {
            final JsonNode item = object.path("_embedded").path("indexableObject");
            final String uuid = text(item, "uuid", text(item, "id", null));
            final Instant modified = Timestamps.parse(text(item, "lastModified", null));
            if (previous != null && modified != null && modified.isBefore(previous)) {
                throw HarvestException.fatal("Source " + id() + " does not order items by " + options.sortField()
                    + "; set the sort-field option to a discovery sort on lastModified", null);
            }
            previous = modified == null ? previous : modified;
            times.add(modified);
            ids.add(uuid);
            if (uuid == null) {
                failures.add(new RecordFailure("item " + (ids.size()) + " of page " + position.skip() / size,
                    "A search result without uuid"));
            } else if (!position.seen().contains(uuid)) {
                item(item, uuid, modified, records, failures);
            }
        }
        final JsonNode page = result.path("page");
        final boolean last = objects.size() < size
            || page.has("totalPages") && page.path("number").asInt() + 1 >= page.path("totalPages").asInt();
        final String next = last ? null : position.next(objects.size(), times, ids, true).encode();
        return new HarvestPage(records, failures, next, null);
    }

    private void item(final JsonNode item, final String uuid, final Instant modified,
            final List<HarvestedRecord> records, final List<RecordFailure> failures) {
        try {
            final String subject = subjectOf(item, uuid);
            if (item.path("withdrawn").asBoolean(false)) {
                records.add(HarvestedRecord.deleted(uuid, subject, modified));
                return;
            }
            final Model model = ModelFactory.createDefaultModel();
            metadata(item.path("metadata"), model.createResource(subject));
            HarvestedRecord record = HarvestedRecord.of(uuid, subject, modified, model);
            if (options.scope().isPresent()) {
                record = record.withSets(List.of(options.scope().get()));
            }
            if (options.includeFiles()) {
                record = record.withFiles(files(uuid));
            }
            records.add(record);
        } catch (HarvestException e) {
            if (e.isRecoverable()) {
                throw e;
            }
            failures.add(new RecordFailure(uuid, e.getMessage()));
        } catch (IllegalArgumentException e) {
            failures.add(new RecordFailure(uuid, e.getMessage()));
        }
    }

    private String subjectOf(final JsonNode item, final String uuid) {
        final String handle = text(item, "handle", null);
        if (handle != null) {
            return options.handleResolver() + handle;
        }
        final String self = text(item.path("_links").path("self"), "href", null);
        return self != null && HarvestedRecord.isAbsoluteIri(self) ? self : settings.base() + "core/items/" + uuid;
    }

    /**
     * Adds the Dublin Core fields of an item's metadata ({@code {"dc.title": [{"value": ...,
     * "language": ...}]}}).
     *
     * @param metadata the metadata object
     * @param subject  the item
     */
    static void metadata(final JsonNode metadata, final Resource subject) {
        final Model model = subject.getModel();
        for (final Map.Entry<String, JsonNode> field : metadata.properties()) {
            final String[] parts = field.getKey().split("\\.", 3);
            if (parts.length < 2) {
                continue;
            }
            DublinCore.property(parts[0], parts[1], parts.length == 3 ? parts[2] : null).ifPresent(iri -> {
                final Property property = model.createProperty(iri);
                for (final JsonNode entry : field.getValue()) {
                    final String value = text(entry, "value", null);
                    if (value == null) {
                        continue;
                    }
                    if (DublinCore.iriValued(iri) && HarvestedRecord.isAbsoluteIri(value)) {
                        subject.addProperty(property, model.createResource(value));
                        continue;
                    }
                    final String language = DublinCore.languageTag(text(entry, "language", null));
                    subject.addProperty(property, language.isEmpty()
                        ? model.createLiteral(value)
                        : model.createLiteral(value, language));
                }
            });
        }
    }

    private List<HarvestedFile> files(final String uuid) {
        final URI uri = URI.create(settings.base() + "core/items/" + encode(uuid)
            + "/bundles?size=100&embed=bitstreams/format");
        final JsonNode body = json(http.get("read the bundles of item " + uuid, uri, HAL),
            "read the bundles of item " + uuid);
        final List<HarvestedFile> files = new ArrayList<>();
        for (final JsonNode bundle : body.path("_embedded").path("bundles")) {
            if (!options.bundle().equals(text(bundle, "name", null))) {
                continue;
            }
            for (final JsonNode bitstream : bundle.path("_embedded").path("bitstreams").path("_embedded")
                    .path("bitstreams")) {
                final String id = text(bitstream, "uuid", text(bitstream, "id", null));
                final String content = text(bitstream.path("_links").path("content"), "href",
                    id == null ? null : settings.base() + "core/bitstreams/" + id + "/content");
                if (content == null || !HarvestedRecord.isAbsoluteIri(content)) {
                    continue;
                }
                final JsonNode size = bitstream.path("sizeBytes");
                files.add(new HarvestedFile(URI.create(content), text(bitstream, "name", null),
                    text(bitstream.path("_embedded").path("format"), "mimetype", null),
                    size.isNumber() ? size.asLong() : null));
            }
        }
        return files;
    }

    private JsonNode json(final byte[] body, final String what) {
        try {
            return JSON.readTree(body);
        } catch (JacksonException e) {
            throw HarvestException.recoverable("Source " + id() + " answered malformed JSON to " + what, e);
        }
    }

    private static String text(final JsonNode node, final String field, final String fallback) {
        final JsonNode value = node.path(field);
        if (value.isString() && !value.asString().isBlank()) {
            return value.asString().strip();
        }
        return fallback;
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Response-independent settings of a DSpace source.
     *
     * @param scope            the community or collection UUID to harvest, may be empty
     * @param sortField        the discovery sort on the modification time
     * @param incrementalQuery the discovery query of incremental harvests ({@code {from}} is replaced)
     * @param includeFiles     whether to reference the item's bitstreams
     * @param bundle           the bundle whose bitstreams are referenced
     * @param handleResolver   the prefix turning handles into IRIs
     */
    record DSpaceOptions(Optional<String> scope, String sortField, String incrementalQuery,
            boolean includeFiles, String bundle, String handleResolver) {

    }

}
