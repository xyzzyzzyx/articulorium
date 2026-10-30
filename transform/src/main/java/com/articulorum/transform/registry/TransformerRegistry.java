package com.articulorum.transform.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.articulorum.transform.config.TransformProperties;
import com.articulorum.transform.spi.TransformationException;
import com.articulorum.transform.spi.Transformer;
import com.articulorum.transform.spi.UnknownTransformerException;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Registry of every {@link Transformer} bean, and the list of transformers that are active for
 * resource changes.
 *
 * <p>The active list comes from {@code articulorum.transform.transformers}; naming a transformer
 * that is not registered fails start-up, so misconfiguration is detected immediately.</p>
 */
@Slf4j
@Component
public class TransformerRegistry {

    private final Map<String, Transformer> transformers = new LinkedHashMap<>();

    private final List<Transformer> active = new ArrayList<>();

    /**
     * Creates the registry.
     *
     * @param candidates the transformer beans, in their declared order
     * @param properties transform settings
     * @throws TransformationException when two transformers share a name or an active
     *                                 transformer is not registered
     */
    public TransformerRegistry(final ObjectProvider<Transformer> candidates, final TransformProperties properties) {
        candidates.orderedStream().forEach(this::register);
        for (final String name : properties.getTransformers()) {
            active.add(find(name).orElseThrow(() -> new TransformationException(
                "articulorum.transform.transformers names unknown transformer '" + name + "'; registered: "
                    + transformers.keySet())));
        }
        log.info("Transformers registered: {}; active: {}", transformers.keySet(), properties.getTransformers());
    }

    private void register(final Transformer transformer) {
        final Transformer previous = transformers.putIfAbsent(transformer.name(), transformer);
        if (previous != null) {
            throw new TransformationException("Two transformers are named '" + transformer.name() + "': "
                + previous.getClass().getName() + " and " + transformer.getClass().getName());
        }
    }

    /**
     * Looks a transformer up by name.
     *
     * @param name the transformer name
     * @return the transformer, if registered
     */
    public Optional<Transformer> find(final String name) {
        return Optional.ofNullable(transformers.get(name));
    }

    /**
     * Returns a transformer by name.
     *
     * @param name the transformer name
     * @return the transformer
     * @throws UnknownTransformerException when it is not registered
     */
    public Transformer get(final String name) {
        return find(name).orElseThrow(() -> new UnknownTransformerException(name));
    }

    /**
     * Lists every registered transformer.
     *
     * @return the transformers in registration order
     */
    public Collection<Transformer> all() {
        return List.copyOf(transformers.values());
    }

    /**
     * Lists the transformers that run for resource changes.
     *
     * @return the active transformers, in order
     */
    public List<Transformer> active() {
        return List.copyOf(active);
    }

    /**
     * Tells whether a transformer runs for resource changes.
     *
     * @param transformer the transformer
     * @return {@code true} when it is active
     */
    public boolean isActive(final Transformer transformer) {
        return active.contains(transformer);
    }

}
