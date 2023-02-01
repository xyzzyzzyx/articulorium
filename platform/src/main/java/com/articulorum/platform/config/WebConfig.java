package com.articulorum.platform.config;

import static com.articulorum.platform.utility.RdfMediaType.TEXT_TURTLE;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.articulorum.platform.converter.JsonLdHttpMessageConverter;
import com.articulorum.platform.converter.N3HttpMessageConverter;
import com.articulorum.platform.converter.NTriplesHttpMessageConverter;
import com.articulorum.platform.converter.RdfJsonHttpMessageConverter;
import com.articulorum.platform.converter.RdfXmlHttpMessageConverter;
import com.articulorum.platform.converter.TurtleHttpMessageConverter;
import com.articulorum.platform.utility.RdfMediaType;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        Map<String, MediaType> mediaTypes = new HashMap<>();
        mediaTypes.put("jsonld", RdfMediaType.APPLICATION_JSON_LD);
        mediaTypes.put("nt", RdfMediaType.APPLICATION_N_TRIPLES);
        mediaTypes.put("rdf", RdfMediaType.APPLICATION_RDF_XML);
        mediaTypes.put("rj", RdfMediaType.APPLICATION_RDF_JSON);
        mediaTypes.put("ttl", RdfMediaType.APPLICATION_X_TURTLE);
        mediaTypes.put("ttl", RdfMediaType.TEXT_TURTLE);
        mediaTypes.put("n3", RdfMediaType.TEXT_N3);
        mediaTypes.put("n3", RdfMediaType.TEXT_RDF_N3);
        mediaTypes.put("json", MediaType.APPLICATION_JSON);
        mediaTypes.put("xml", MediaType.APPLICATION_XML);
        configurer.defaultContentType(TEXT_TURTLE).mediaTypes(mediaTypes);
    }

    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(0, new JsonLdHttpMessageConverter());
        converters.add(0, new NTriplesHttpMessageConverter());
        converters.add(0, new RdfJsonHttpMessageConverter());
        converters.add(0, new TurtleHttpMessageConverter());
        converters.add(0, new N3HttpMessageConverter());
        converters.add(0, new RdfXmlHttpMessageConverter());
    }

}