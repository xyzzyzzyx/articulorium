package com.articulorium.discovery.solr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;

import com.articulorium.discovery.MutableClock;
import com.articulorium.discovery.config.DiscoveryProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Tests which fields the collection is known to have, against a mocked Solr.
 */
class SolrSchemaTest {

    private static final String FIELDS = "http://solr.test/solr/articulorium/schema/fields?wt=json";

    private static final String DYNAMIC = "http://solr.test/solr/articulorium/schema/dynamicfields?wt=json";

    private MockRestServiceServer solr;

    private MutableClock clock;

    private SolrSchema schema;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        solr = MockRestServiceServer.bindTo(builder).build();
        final DiscoveryProperties properties = new DiscoveryProperties();
        properties.getSolr().setBaseUrl("http://solr.test/solr");
        clock = new MutableClock(java.time.Instant.parse("2026-11-07T12:00:00Z"));
        schema = new SolrSchema(new SolrSearchClient(builder, properties), clock);
    }

    private void expectSchema(final String fields) {
        solr.expect(once(), requestTo(FIELDS)).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"fields\": [" + fields + "]}", MediaType.APPLICATION_JSON));
        solr.expect(once(), requestTo(DYNAMIC))
            .andRespond(withSuccess("{\"dynamicFields\": [{\"name\": \"*_str\"}, {\"name\": \"*_txt_en\"}, {\"name\": \"attr_*\"}]}",
                MediaType.APPLICATION_JSON));
    }

    @Test
    void knowsExplicitAndDynamicFields() {
        expectSchema("{\"name\": \"id\"}, {\"name\": \"title\"}, {\"name\": \"_text_\"}");

        assertThat(schema.has("title")).isTrue();
        assertThat(schema.has("title_txt_en")).isTrue();
        assertThat(schema.has("creator_str")).isTrue();
        assertThat(schema.has("attr_x")).isTrue();
        assertThat(schema.has("alternative")).isFalse();
        assertThat(schema.has("title_txt_de")).isFalse();
        solr.verify();
    }

    @Test
    void readsTheSchemaAgainAfterAMinute() {
        expectSchema("{\"name\": \"title\"}");
        assertThat(schema.has("abstract")).isFalse();
        solr.verify();

        solr.reset();
        clock.advance(Duration.ofSeconds(SolrSchema.TTL_SECONDS + 1));
        expectSchema("{\"name\": \"title\"}, {\"name\": \"abstract\"}");
        assertThat(schema.has("abstract")).isTrue();
        solr.verify();
    }

    @Test
    void assumesFieldsExistWhenTheSchemaCannotBeRead() {
        solr.expect(requestTo(FIELDS)).andRespond(withServerError());

        assertThat(schema.has("anything")).isTrue();
    }

}
