package com.articulorum.transform.service;

import static java.lang.String.join;
import static org.apache.commons.lang3.StringUtils.removeEnd;
import static org.apache.commons.lang3.StringUtils.removeStart;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.Map;

import com.articulorum.transform.config.model.LdPathConfig;
import com.github.jsonldjava.sesame.SesameJSONLDParserFactory;

import org.apache.marmotta.ldpath.LDPath;
import org.apache.marmotta.ldpath.backend.linkeddata.LDCacheBackend;
import org.apache.marmotta.ldpath.exception.LDPathParseException;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.query.resultio.BooleanQueryResultParserRegistry;
import org.openrdf.query.resultio.TupleQueryResultParserRegistry;
import org.openrdf.query.resultio.sparqlxml.SPARQLBooleanXMLParserFactory;
import org.openrdf.query.resultio.sparqlxml.SPARQLResultsXMLParserFactory;
import org.openrdf.rio.RDFParserRegistry;
import org.openrdf.rio.n3.N3ParserFactory;
import org.openrdf.rio.ntriples.NTriplesParserFactory;
import org.openrdf.rio.rdfjson.RDFJSONParserFactory;
import org.openrdf.rio.rdfxml.RDFXMLParserFactory;
import org.openrdf.rio.trig.TriGParserFactory;
import org.openrdf.rio.turtle.TurtleParserFactory;
import org.semarglproject.sesame.rdf.rdfa.SesameRDFaParserFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class LdPathService {

    private final LDPath<org.openrdf.model.Value> ldpath;

    @Autowired
    private LdPathConfig ldPathConfig;

    @Value("classpath:default.ldpath")
    private Resource defaultProgram;

    @Autowired
    public LdPathService(final LDCacheBackend backend) {
        RDFParserRegistry.getInstance().add(new RDFXMLParserFactory());
        RDFParserRegistry.getInstance().add(new NTriplesParserFactory());
        RDFParserRegistry.getInstance().add(new TurtleParserFactory());
        RDFParserRegistry.getInstance().add(new N3ParserFactory());
        RDFParserRegistry.getInstance().add(new SesameJSONLDParserFactory());
        RDFParserRegistry.getInstance().add(new RDFJSONParserFactory());
        RDFParserRegistry.getInstance().add(new SesameRDFaParserFactory());
        RDFParserRegistry.getInstance().add(new TriGParserFactory());
        BooleanQueryResultParserRegistry.getInstance().add(new SPARQLBooleanXMLParserFactory());
        TupleQueryResultParserRegistry.getInstance().add(new SPARQLResultsXMLParserFactory());
        ldpath = new LDPath<>(backend);
    }

    public Map<String, Collection<?>> programQuery(final String path) throws LDPathParseException, IOException {
        return programQuery(path, defaultProgram.getInputStream());
    }

    public Map<String, Collection<?>> programQuery(final String path, final InputStream program) throws LDPathParseException, IOException {
        String uri = join("/", removeEnd(ldPathConfig.getNamespace(), "/"), removeStart(path, "/"));
        Map<String, Collection<?>> document = ldpath.programQuery(new URIImpl(uri), new InputStreamReader(program));
        document.values().removeIf(value -> value.isEmpty());
        return document;
    }

}