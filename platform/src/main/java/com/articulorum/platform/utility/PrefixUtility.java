package com.articulorum.platform.utility;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;

import com.articulorum.domain.Schema;

public class PrefixUtility {

    // key -> namespace, value -> prefix
    private final static Map<String, String> prefixes = new ConcurrentHashMap<String, String>();

    private PrefixUtility() {

    }

    public static void putPrefix(Schema schema) {
        prefixes.put(schema.getNamespace(), schema.getPrefix());
    }

    public static void removePrefix(Schema schema) {
        prefixes.remove(schema.getNamespace());
    }

    public static Optional<Entry<String, String>> find(String iri) {
        return prefixes.entrySet().stream().filter(entry -> iri.startsWith(entry.getKey())).findAny();
    }

    public static Stream<Schema> readNamespaceCSV(InputStream is) throws IOException {
        final CsvSchema csvSchema = CsvSchema.emptySchema().withHeader();
        final CsvMapper mapper = new CsvMapper();
        return mapper.readerFor(Schema.class)
            .with(csvSchema)
            .readValues(is)
            .readAll()
            .stream()
            .map(PrefixUtility::toSchema);
    }

    private static Schema toSchema(Object schema) {
        return (Schema) schema;
    }

}