package com.articulorium.ai.topology;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * A topology as the topology API reads and writes it (the same JSON), used for drafts the agent
 * suggests and for the topologies it reads. Flags and counts are objects: a model (or a client)
 * may leave them out, and the topology API takes the defaults.
 *
 * @param id          the identifier (a lower-case slug)
 * @param name        the display name
 * @param description what the topology is for
 * @param builtIn     whether it ships with the topology service
 * @param root        the root node
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TopologyDraft(
        @JsonPropertyDescription("Identifier: lower-case slug of a-z, 0-9, - and _, for example scrapbook") String id,
        @JsonPropertyDescription("Display name, for example Scrapbook") String name,
        @JsonPropertyDescription("One sentence: what material the topology is for") String description,
        @JsonPropertyDescription("Always false for drafts") Boolean builtIn,
        @JsonPropertyDescription("The root node") Node root) {

    /**
     * A level of a topology.
     *
     * @param key      unique key (slug)
     * @param label    label
     * @param pcdmType {@code Collection} or {@code Object}
     * @param types    additional {@code rdf:type} IRIs
     * @param metadata metadata fields
     * @param files    file kinds
     * @param children child rules
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Node(
            @JsonPropertyDescription("Key unique in the topology: lower-case slug, for example page") String key,
            @JsonPropertyDescription("Label, for example Page") String label,
            @JsonPropertyDescription("Collection or Object. Objects contain only objects") String pcdmType,
            @JsonPropertyDescription("Additional rdf:type IRIs (absolute), for example http://purl.org/ontology/bibo/Book") List<String> types,
            @JsonPropertyDescription("Metadata fields offered when describing resources of this node") List<Field> metadata,
            @JsonPropertyDescription("Kinds of files resources of this node hold") List<FileKind> files,
            @JsonPropertyDescription("Child levels") List<Child> children) {
    }

    /**
     * A child rule.
     *
     * @param min         minimum number of children
     * @param max         maximum number, -1 for unlimited
     * @param initial     how many a build creates
     * @param slugPattern name pattern of the children ({n} is the position)
     * @param node        the child node
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Child(
            @JsonPropertyDescription("Minimum number of children, 0 or more") Integer min,
            @JsonPropertyDescription("Maximum number of children, -1 for unlimited") Integer max,
            @JsonPropertyDescription("How many children a build creates") Integer initial,
            @JsonPropertyDescription("Name pattern of the children, {n} is the position, for example page-{n}") String slugPattern,
            @JsonPropertyDescription("The child node") Node node) {
    }

    /**
     * A metadata field.
     *
     * @param predicate    predicate IRI
     * @param label        label
     * @param required     whether a value is expected
     * @param repeatable   whether several values are allowed
     * @param defaultValue default literal, may be {@code null}
     * @param datatype     datatype IRI, {@code null} for plain strings
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Field(
            @JsonPropertyDescription("Predicate IRI (absolute), for example http://purl.org/dc/terms/title") String predicate,
            @JsonPropertyDescription("Label, for example Title") String label,
            Boolean required,
            Boolean repeatable,
            @JsonPropertyDescription("Default value, usually omitted") String defaultValue,
            @JsonPropertyDescription("Datatype IRI, omitted for plain text") String datatype) {
    }

    /**
     * A kind of file.
     *
     * @param use        PCDM use IRI
     * @param label      label
     * @param mediaTypes expected media types
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileKind(
            @JsonPropertyDescription("PCDM use IRI, for example http://pcdm.org/use#OriginalFile") String use,
            @JsonPropertyDescription("Label, for example Master image") String label,
            @JsonPropertyDescription("Expected media types, for example image/tiff") List<String> mediaTypes) {
    }

}
