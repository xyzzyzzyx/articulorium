package com.articulorum.transform.spi;

import java.util.Collection;
import java.util.Map;

/**
 * A transformation of an LDP resource into a search document.
 *
 * <p>This is the extension point of the transform service: every Spring bean implementing this
 * interface is registered in the {@link com.articulorum.transform.registry.TransformerRegistry}
 * under its {@link #name() name}. The transformers listed in
 * {@code articulorum.transform.transformers} run for every resource change, and any registered
 * transformer can be tried out through the service's REST API. New transformations (other field
 * mappings, OCR text extraction, IIIF manifests, ...) are added by contributing another bean;
 * nothing else needs to change.</p>
 *
 * <p>Implementations must be thread-safe and free of side effects: the same transformer is
 * invoked concurrently for different resources, and previews must not publish anything.</p>
 */
public interface Transformer {

    /**
     * Returns the unique name of the transformer, used in configuration and URLs.
     *
     * @return a short identifier such as {@code field-mapping}
     */
    String name();

    /**
     * Describes what the transformer produces.
     *
     * @return a human readable description
     */
    String description();

    /**
     * Tells whether the transformer applies to a resource. The default applies to every resource.
     *
     * @param context the resource to transform
     * @return {@code true} when {@link #transform} should run
     */
    default boolean supports(final TransformationContext context) {
        return true;
    }

    /**
     * Transforms a resource.
     *
     * @param context the resource and its RDF representation
     * @return the document: field names mapped to their values (empty fields may be omitted)
     * @throws TransformationException when the resource cannot be transformed
     */
    Map<String, Collection<?>> transform(TransformationContext context);

}
