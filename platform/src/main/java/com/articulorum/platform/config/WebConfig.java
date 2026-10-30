package com.articulorum.platform.config;

import com.articulorum.platform.rdf.RdfModelHttpMessageConverter;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC configuration of the platform.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    /**
     * Order of the static resource handler mapping. It must come before the annotated controllers
     * (order 0), because the LDP controller maps every path ({@code /**}) and would otherwise
     * shadow the Swagger UI assets served below {@code /.well-known/swagger-ui/}.
     */
    static final int RESOURCE_HANDLER_ORDER = -1;

    /**
     * Registers the RDF converter ahead of the defaults, so content negotiation for RDF responses
     * resolves to it (Turtle when the client accepts anything) while Spring's own converters stay
     * available for strings, resources, JSON and error bodies.
     *
     * @param builder the converter builder
     */
    @Override
    public void configureMessageConverters(final HttpMessageConverters.ServerBuilder builder) {
        builder.addCustomConverter(new RdfModelHttpMessageConverter());
    }

    /**
     * Moves the resource handler mapping ahead of the controllers. Spring Boot's catch-all static
     * resource mapping is disabled ({@code spring.web.resources.add-mappings=false}), so the only
     * resource handlers are springdoc's Swagger UI assets, all below the reserved
     * {@code /.well-known} path.
     *
     * @param registry the registry
     */
    @Override
    public void addResourceHandlers(final ResourceHandlerRegistry registry) {
        registry.setOrder(RESOURCE_HANDLER_ORDER);
    }

}
