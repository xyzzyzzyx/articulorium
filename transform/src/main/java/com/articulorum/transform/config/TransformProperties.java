package com.articulorum.transform.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Transform service settings ({@code articulorum.transform.*}).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "articulorum.transform")
public class TransformProperties {

    /**
     * Names of the transformers that run for every resource change, in order. Their documents
     * are merged (values of a field shared by several transformers are combined).
     */
    private List<String> transformers = new ArrayList<>(List.of("field-mapping"));

    /**
     * When set, document identifiers are minted under this base (for example
     * {@code http://platform:9000}) instead of the base the platform reported for the change.
     */
    private String baseUri;

    /** Location of the field mapping definitions used by the {@code field-mapping} transformer. */
    private String fieldMappings = "classpath:transform/field-mappings.yml";

}
