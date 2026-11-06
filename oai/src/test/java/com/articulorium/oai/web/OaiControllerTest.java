package com.articulorium.oai.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import com.articulorium.domain.vocabulary.Articulorium;
import com.articulorium.domain.vocabulary.Pcdm;
import com.articulorium.oai.FakePlatform;
import com.articulorium.oai.OaiSchemas;
import com.articulorium.oai.platform.PlatformException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Tests the OAI-PMH endpoint over the full application context with an in-memory platform. Every
 * response is validated against the official OAI-PMH and oai_dc schemas.
 */
@SpringBootTest(properties = "articulorium.oai.page-size=2")
@AutoConfigureMockMvc
class OaiControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-05T18:30:00Z");

    private static final String BASE = "http://localhost:8000/oai";

    private static final String DCTERMS = "http://purl.org/dc/terms/";

    private static final String ID = "oai:articulorium.org:";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FakePlatform platform;

    @Autowired
    private MeterRegistry meters;

    /** The in-memory platform and a fixed clock. */
    @TestConfiguration
    static class Fakes {

        @Bean
        @Primary
        FakePlatform fakePlatform() {
            return new FakePlatform();
        }

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

    }

    @BeforeEach
    void setUp() {
        platform.fail(null);
        for (final String path : List.of("communities", "communities/arts", "communities/arts/paintings",
            "communities/science", "communities/arts/paintings/mona-lisa",
            "communities/arts/paintings/mona-lisa/members", "communities/arts/paintings/mona-lisa/members/detail",
            "communities/arts/david", "loose", "secret")) {
            platform.remove(path);
        }
        platform.put("communities", at("2026-01-01T00:00:00Z"), Map.of(), Articulorium.COMMUNITY_ROOT);
        platform.put("communities/arts", at("2026-01-01T00:00:00Z"), Map.of(DCTERMS + "title", "Arts"),
            Articulorium.COMMUNITY);
        platform.put("communities/arts/paintings", at("2026-01-01T00:00:00Z"),
            Map.of(DCTERMS + "title", "Paintings"), Articulorium.COLLECTION, Pcdm.COLLECTION);
        platform.put("communities/science", at("2026-01-01T00:00:00Z"), Map.of(), Articulorium.COMMUNITY);
        platform.put("communities/arts/paintings/mona-lisa", at("2026-03-01T10:00:00.250Z"),
            Map.of(DCTERMS + "creator", "Leonardo da Vinci", DCTERMS + "date", "1503"), Pcdm.OBJECT);
        platform.describe("communities/arts/paintings/mona-lisa", item -> item
            .addProperty(item.getModel().createProperty(DCTERMS + "title"), "Mona Lisa", "en")
            .addProperty(item.getModel().createProperty(DCTERMS + "title"), "La Gioconda", "it")
            .addProperty(item.getModel().createProperty(DCTERMS + "description"), "Oil on poplar \u0001panel"));
        platform.put("communities/arts/paintings/mona-lisa/members", at("2026-03-01T10:00:01Z"), Map.of());
        platform.put("communities/arts/paintings/mona-lisa/members/detail", at("2026-03-01T10:00:02Z"),
            Map.of(DCTERMS + "title", "Detail"), Pcdm.OBJECT);
        platform.put("communities/arts/david", at("2026-04-01T00:00:00Z"), Map.of(DCTERMS + "title", "David"),
            Pcdm.OBJECT);
        platform.put("loose", at("2026-05-01T00:00:00Z"), Map.of(DCTERMS + "title", "Loose"), Pcdm.OBJECT);
        platform.put("secret", at("2026-02-01T00:00:00Z"), Map.of(DCTERMS + "title", "Secret"), Pcdm.OBJECT);
        platform.hide("secret");
    }

    @Test
    void identifiesTheRepository() throws Exception {
        final Xml xml = oai(get("/").param("verb", "Identify"));

        assertThat(xml.text("/oai:OAI-PMH/oai:responseDate")).isEqualTo("2026-11-05T18:30:00Z");
        assertThat(xml.text("/oai:OAI-PMH/oai:request")).isEqualTo(BASE);
        assertThat(xml.text("/oai:OAI-PMH/oai:request/@verb")).isEqualTo("Identify");
        assertThat(xml.text("//oai:Identify/oai:repositoryName")).isEqualTo("Articulorium");
        assertThat(xml.text("//oai:Identify/oai:baseURL")).isEqualTo(BASE);
        assertThat(xml.text("//oai:Identify/oai:protocolVersion")).isEqualTo("2.0");
        assertThat(xml.text("//oai:Identify/oai:adminEmail")).isEqualTo("admin@example.org");
        // the hidden record is earlier, but this harvester may not read it
        assertThat(xml.text("//oai:Identify/oai:earliestDatestamp")).isEqualTo("2026-03-01T10:00:00Z");
        assertThat(xml.text("//oai:Identify/oai:deletedRecord")).isEqualTo("no");
        assertThat(xml.text("//oai:Identify/oai:granularity")).isEqualTo("YYYY-MM-DDThh:mm:ssZ");
    }

    @Test
    void listsMetadataFormats() throws Exception {
        final Xml all = oai(get("/").param("verb", "ListMetadataFormats"));
        assertThat(all.texts("//oai:metadataFormat/oai:metadataPrefix")).containsExactly("oai_dc");
        assertThat(all.text("//oai:metadataFormat/oai:metadataNamespace"))
            .isEqualTo("http://www.openarchives.org/OAI/2.0/oai_dc/");

        final Xml one = oai(get("/").param("verb", "ListMetadataFormats").param("identifier", ID + "loose"));
        assertThat(one.texts("//oai:metadataPrefix")).containsExactly("oai_dc");
        assertError(oai(get("/").param("verb", "ListMetadataFormats").param("identifier", ID + "nope")),
            "idDoesNotExist");
    }

    @Test
    void listsCommunitiesAndCollectionsAsSetsInParts() throws Exception {
        final Xml first = oai(get("/").param("verb", "ListSets"));
        assertThat(first.texts("//oai:set/oai:setSpec")).containsExactly("arts", "arts:paintings");
        assertThat(first.texts("//oai:set/oai:setName")).containsExactly("Arts", "Paintings");
        assertThat(first.text("//oai:resumptionToken/@cursor")).isEqualTo("0");

        final Xml second = oai(get("/").param("verb", "ListSets")
            .param("resumptionToken", first.text("//oai:resumptionToken")));
        assertThat(second.text("/oai:OAI-PMH/oai:request/@resumptionToken")).isNotBlank();
        // a set without a title is named after its path
        assertThat(second.texts("//oai:set/oai:setName")).containsExactly("science");
        assertThat(second.text("//oai:resumptionToken")).isEmpty();
        assertThat(second.text("//oai:resumptionToken/@cursor")).isEqualTo("2");
    }

    @Test
    void getsARecordAsDublinCore() throws Exception {
        final Xml xml = oai(get("/").param("verb", "GetRecord").param("metadataPrefix", "oai_dc")
            .param("identifier", ID + "communities/arts/paintings/mona-lisa"));

        assertThat(xml.text("//oai:header/oai:identifier")).isEqualTo(ID + "communities/arts/paintings/mona-lisa");
        assertThat(xml.text("//oai:header/oai:datestamp")).isEqualTo("2026-03-01T10:00:00Z");
        assertThat(xml.texts("//oai:header/oai:setSpec")).containsExactly("arts", "arts:paintings");
        assertThat(xml.texts("//dc:title")).containsExactly("La Gioconda", "Mona Lisa");
        assertThat(xml.texts("//dc:title/@xml:lang")).containsExactly("it", "en");
        assertThat(xml.texts("//dc:creator")).containsExactly("Leonardo da Vinci");
        assertThat(xml.texts("//dc:date")).containsExactly("1503");
        // characters XML cannot carry are dropped
        assertThat(xml.texts("//dc:description")).containsExactly("Oil on poplar panel");
        assertThat(xml.texts("//dc:identifier")).containsExactly(
            "http://localhost:8000/ui/browse/communities/arts/paintings/mona-lisa",
            "http://localhost:8000/platform/communities/arts/paintings/mona-lisa");
    }

    @Test
    void refusesUnknownNestedHiddenAndForeignItemsAndOtherFormats() throws Exception {
        for (final String identifier : List.of(ID + "nope", ID + "communities/arts/paintings/mona-lisa/members/detail",
            ID + "secret", ID + "communities/arts", "oai:other.org:loose", "loose")) {
            assertError(oai(get("/").param("verb", "GetRecord").param("metadataPrefix", "oai_dc")
                .param("identifier", identifier)), "idDoesNotExist");
        }
        final Xml format = oai(get("/").param("verb", "GetRecord").param("metadataPrefix", "marc21")
            .param("identifier", ID + "loose"));
        assertError(format, "cannotDisseminateFormat");
        // well-formed requests echo their arguments even when they fail
        assertThat(format.text("/oai:OAI-PMH/oai:request/@metadataPrefix")).isEqualTo("marc21");
    }

    @Test
    void harvestsEveryRecordWithResumptionTokens() throws Exception {
        final List<String> identifiers = new ArrayList<>();
        final List<String> cursors = new ArrayList<>();
        Xml xml = oai(get("/").param("verb", "ListRecords").param("metadataPrefix", "oai_dc"));
        while (true) {
            identifiers.addAll(xml.texts("//oai:record/oai:header/oai:identifier"));
            cursors.add(xml.text("//oai:resumptionToken/@cursor"));
            final String token = xml.text("//oai:resumptionToken");
            if (token.isEmpty()) {
                break;
            }
            xml = oai(get("/").param("verb", "ListRecords").param("resumptionToken", token));
        }

        assertThat(identifiers).containsExactly(ID + "communities/arts/paintings/mona-lisa",
            ID + "communities/arts/david", ID + "loose");
        assertThat(cursors).containsExactly("0", "2");
        assertThat(xml.texts("//dc:title")).containsExactly("Loose");
    }

    @Test
    void endsAListWhenOnlyUnreadableRecordsRemain() throws Exception {
        // the last two records are unreadable: the part holding David and Loose ends the list
        platform.put("later-secret", at("2026-06-01T00:00:00Z"), Map.of(DCTERMS + "title", "Later"), Pcdm.OBJECT);
        platform.hide("later-secret");
        try {
            final Xml first = oai(get("/").param("verb", "ListIdentifiers").param("metadataPrefix", "oai_dc")
                .param("from", "2026-04-01"));

            assertThat(first.texts("//oai:header/oai:identifier"))
                .containsExactly(ID + "communities/arts/david", ID + "loose");
            assertThat(first.count("//oai:resumptionToken")).isZero();
        } finally {
            platform.remove("later-secret");
        }
    }

    @Test
    void listsIdentifiersSelectively() throws Exception {
        assertThat(oai(get("/").param("verb", "ListIdentifiers").param("metadataPrefix", "oai_dc")
            .param("set", "arts:paintings")).texts("//oai:header/oai:identifier"))
            .containsExactly(ID + "communities/arts/paintings/mona-lisa");
        final Xml arts = oai(get("/").param("verb", "ListIdentifiers").param("metadataPrefix", "oai_dc")
            .param("set", "arts"));
        assertThat(arts.texts("//oai:header/oai:identifier")).containsExactly(
            ID + "communities/arts/paintings/mona-lisa", ID + "communities/arts/david");
        // every match fits in one part: no token at all
        assertThat(arts.count("//oai:resumptionToken")).isZero();
        // from and until are inclusive, at either granularity
        assertThat(oai(get("/").param("verb", "ListIdentifiers").param("metadataPrefix", "oai_dc")
            .param("from", "2026-03-01T10:00:00Z").param("until", "2026-04-01T00:00:00Z"))
            .texts("//oai:header/oai:identifier"))
            .containsExactly(ID + "communities/arts/paintings/mona-lisa", ID + "communities/arts/david");
        assertThat(oai(get("/").param("verb", "ListIdentifiers").param("metadataPrefix", "oai_dc")
            .param("from", "2026-04-02").param("until", "2026-05-01")).texts("//oai:header/oai:identifier"))
            .containsExactly(ID + "loose");
        assertError(oai(get("/").param("verb", "ListIdentifiers").param("metadataPrefix", "oai_dc")
            .param("from", "2027-01-01")), "noRecordsMatch");
        assertError(oai(get("/").param("verb", "ListIdentifiers").param("metadataPrefix", "oai_dc")
            .param("set", "music")), "noRecordsMatch");
        assertError(oai(get("/").param("verb", "ListRecords").param("metadataPrefix", "mods")), "cannotDisseminateFormat");
    }

    @Test
    void answersProtocolErrors() throws Exception {
        final Xml missing = oai(get("/"));
        assertError(missing, "badVerb");
        assertThat(missing.count("/oai:OAI-PMH/oai:request/@*")).isZero();
        assertThat(missing.text("/oai:OAI-PMH/oai:request")).isEqualTo(BASE);
        assertError(oai(get("/").param("verb", "Explode")), "badVerb");
        assertError(oai(get("/").param("verb", "Identify").param("verb", "Identify")), "badVerb");

        final Xml illegal = oai(get("/").param("verb", "ListRecords").param("metadataPrefix", "oai_dc")
            .param("from", "yesterday"));
        assertError(illegal, "badArgument");
        assertThat(illegal.count("/oai:OAI-PMH/oai:request/@*")).isZero();
        assertError(oai(get("/").param("verb", "ListRecords").param("metadataPrefix", "oai_dc")
            .param("from", "2026-01-02").param("until", "2026-01-01")), "badArgument");
        assertError(oai(get("/").param("verb", "ListRecords").param("metadataPrefix", "oai_dc")
            .param("from", "2026-01-01").param("until", "2026-01-02T00:00:00Z")), "badArgument");
        assertError(oai(get("/").param("verb", "ListRecords")), "badArgument");
        assertError(oai(get("/").param("verb", "GetRecord").param("identifier", ID + "loose")), "badArgument");
        assertError(oai(get("/").param("verb", "Identify").param("set", "arts")), "badArgument");
        assertError(oai(get("/").param("verb", "ListSets").param("set", "a b")), "badArgument");
        assertError(oai(get("/").param("verb", "ListRecords").param("metadataPrefix", "oai_dc").param("set", "a b")),
            "badArgument");
        assertError(oai(get("/").param("verb", "ListRecords").param("resumptionToken", "garbage")),
            "badResumptionToken");
        assertError(oai(get("/").param("verb", "ListSets").param("resumptionToken", "garbage")),
            "badResumptionToken");
    }

    @Test
    void acceptsFormEncodedPosts() throws Exception {
        final Xml xml = oai(post("/").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .content("verb=GetRecord&metadataPrefix=oai_dc&identifier=" + ID + "loose"));

        assertThat(xml.text("//dc:title")).isEqualTo("Loose");
    }

    @Test
    void answers503WhenThePlatformIsUnavailableAndCountsRequests() throws Exception {
        final double before = count("Identify", "ok");
        oai(get("/").param("verb", "Identify"));
        assertThat(count("Identify", "ok")).isEqualTo(before + 1);

        platform.fail(new PlatformException(502, "down", null));
        mvc.perform(get("/").param("verb", "ListRecords").param("metadataPrefix", "oai_dc"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Retry-After", "60"));
    }

    private double count(final String verb, final String outcome) {
        final io.micrometer.core.instrument.Counter counter = meters.find("oai.requests").tag("verb", verb)
            .tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private Xml oai(final MockHttpServletRequestBuilder request) throws Exception {
        final byte[] body = mvc.perform(request
                .header("X-Forwarded-Proto", "http")
                .header("X-Forwarded-Host", "localhost")
                .header("X-Forwarded-Port", "8000")
                .header("X-Forwarded-Prefix", "/oai"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/xml"))
            .andReturn().getResponse().getContentAsByteArray();
        OaiSchemas.validate(body);
        return new Xml(body);
    }

    private static void assertError(final Xml xml, final String code) throws Exception {
        assertThat(xml.text("/oai:OAI-PMH/oai:error/@code")).isEqualTo(code);
        assertThat(xml.text("/oai:OAI-PMH/oai:error")).isNotBlank();
    }

    private static Instant at(final String instant) {
        return Instant.parse(instant);
    }

    /** A parsed response queried with XPath. */
    private static final class Xml {

        private static final Map<String, String> NAMESPACES = Map.of(
            "oai", "http://www.openarchives.org/OAI/2.0/",
            "oai_dc", "http://www.openarchives.org/OAI/2.0/oai_dc/",
            "dc", "http://purl.org/dc/elements/1.1/",
            "xml", XMLConstants.XML_NS_URI);

        private final Document document;

        private final XPath xpath;

        Xml(final byte[] body) throws Exception {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
            xpath = XPathFactory.newInstance().newXPath();
            xpath.setNamespaceContext(new NamespaceContext() {
                @Override
                public String getNamespaceURI(final String prefix) {
                    return NAMESPACES.getOrDefault(prefix, XMLConstants.NULL_NS_URI);
                }

                @Override
                public String getPrefix(final String namespace) {
                    return null;
                }

                @Override
                public Iterator<String> getPrefixes(final String namespace) {
                    return null;
                }
            });
        }

        String text(final String expression) throws Exception {
            return xpath.evaluate(expression, document);
        }

        List<String> texts(final String expression) throws Exception {
            final NodeList nodes = (NodeList) xpath.evaluate(expression, document, XPathConstants.NODESET);
            final List<String> values = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                values.add(nodes.item(i).getTextContent());
            }
            return values;
        }

        int count(final String expression) throws Exception {
            return ((NodeList) xpath.evaluate(expression, document, XPathConstants.NODESET)).getLength();
        }

    }

}
