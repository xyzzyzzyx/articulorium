package com.articulorum.transform.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.articulorum.transform.config.TransformProperties;
import com.articulorum.transform.spi.TransformationContext;
import com.articulorum.transform.spi.TransformationException;
import com.articulorum.transform.spi.Transformer;
import com.articulorum.transform.spi.UnknownTransformerException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Unit tests for {@link TransformerRegistry}.
 */
class TransformerRegistryTest {

    /** A transformer returning a fixed field. */
    record Fixed(String name) implements Transformer {
        @Override
        public String description() {
            return "fixed " + name;
        }

        @Override
        public Map<String, Collection<?>> transform(final TransformationContext context) {
            return Map.of(name, List.of(name));
        }
    }

    private static TransformerRegistry registry(final List<String> active, final Transformer... transformers) {
        final StaticListableBeanFactory factory = new StaticListableBeanFactory();
        for (int i = 0; i < transformers.length; i++) {
            factory.addBean("transformer" + i, transformers[i]);
        }
        final TransformProperties properties = new TransformProperties();
        properties.setTransformers(active);
        return new TransformerRegistry(factory.getBeanProvider(Transformer.class), properties);
    }

    /** Every bean is registered; only the configured ones are active, in configuration order. */
    @Test
    void registersBeansAndActivatesConfiguredOnes() {
        final Fixed a = new Fixed("a");
        final Fixed b = new Fixed("b");
        final Fixed c = new Fixed("c");

        final TransformerRegistry registry = registry(List.of("c", "a"), a, b, c);

        assertThat(registry.all()).containsExactlyInAnyOrder(a, b, c);
        assertThat(registry.active()).containsExactly(c, a);
        assertThat(registry.isActive(b)).isFalse();
        assertThat(registry.get("b")).isSameAs(b);
        assertThat(registry.find("z")).isEmpty();
    }

    /** Unknown names are 404-style errors on lookup and fatal in configuration. */
    @Test
    void rejectsUnknownNames() {
        final TransformerRegistry registry = registry(List.of(), new Fixed("a"));

        assertThatThrownBy(() -> registry.get("z")).isInstanceOf(UnknownTransformerException.class);
        assertThatThrownBy(() -> registry(List.of("z"), new Fixed("a")))
            .isInstanceOf(TransformationException.class)
            .hasMessageContaining("unknown transformer 'z'");
    }

    /** Two transformers may not share a name. */
    @Test
    void rejectsDuplicateNames() {
        assertThatThrownBy(() -> registry(List.of(), new Fixed("a"), new Fixed("a")))
            .isInstanceOf(TransformationException.class)
            .hasMessageContaining("Two transformers are named 'a'");
    }

}
