package com.articulorium.index.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.articulorium.index.backend.IndexStatus;
import com.articulorium.index.config.ElasticsearchProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link ElasticsearchIndexClient} against a mocked Elasticsearch server.
 */
class ElasticsearchIndexClientTest {

    private static final String BASE = "http://elasticsearch:9200";

    private MockRestServiceServer server;

    private ElasticsearchProperties properties;

    private ElasticsearchIndexClient client;

    @BeforeEach
    void setUp() {
        properties = new ElasticsearchProperties();
        properties.setBaseUrl(BASE);
        client = client(properties);
    }

    private ElasticsearchIndexClient client(final ElasticsearchProperties settings) {
        final RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new ElasticsearchIndexClient(builder, settings);
    }

    @Test
    void createsAMissingIndexWithItsSettingsAndMappings() {
        properties.setNumberOfReplicas(0);
        server.expect(requestTo(BASE + "/articulorium")).andExpect(method(HttpMethod.HEAD))
            .andRespond(withResourceNotFound());
        server.expect(requestTo(BASE + "/articulorium")).andExpect(method(HttpMethod.PUT))
            .andExpect(jsonPath("$.settings.index.number_of_shards").value(1))
            .andExpect(jsonPath("$.settings.index.number_of_replicas").value(0))
            .andExpect(jsonPath("$.settings.index.refresh_interval").value("1s"))
            .andExpect(jsonPath("$.mappings.date_detection").value(false))
            .andExpect(jsonPath("$.mappings.properties.id.type").value("keyword"))
            .andExpect(jsonPath("$.mappings.properties._text_.type").value("text"))
            .andExpect(jsonPath("$.mappings.dynamic_templates[0].exact_values.mapping.type").value("keyword"))
            .andRespond(withSuccess("{\"acknowledged\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.ensureIndex()).isTrue();
        server.verify();
    }

    @Test
    void keepsAnExistingIndex() {
        server.expect(requestTo(BASE + "/articulorium")).andExpect(method(HttpMethod.HEAD)).andRespond(withSuccess());

        assertThat(client.ensureIndex()).isFalse();
        server.verify();
    }

    @Test
    void toleratesAnIndexCreatedInTheMeantime() {
        server.expect(requestTo(BASE + "/articulorium")).andExpect(method(HttpMethod.HEAD))
            .andRespond(withResourceNotFound());
        server.expect(requestTo(BASE + "/articulorium")).andExpect(method(HttpMethod.PUT))
            .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"type\":\"resource_already_exists_exception\"},\"status\":400}"));

        assertThat(client.ensureIndex()).isFalse();
        server.verify();
    }

    @Test
    void reportsARefusedIndex() {
        server.expect(requestTo(BASE + "/articulorium")).andExpect(method(HttpMethod.HEAD))
            .andRespond(withResourceNotFound());
        server.expect(requestTo(BASE + "/articulorium")).andExpect(method(HttpMethod.PUT))
            .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"type\":\"illegal_argument_exception\"},\"status\":400}"));

        assertThatThrownBy(client::ensureIndex).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    void declaresMissingFieldsOnly() {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("created", "date");
        fields.put("lastModified", "date");
        fields.put("pages", "integer");
        properties.setFields(fields);
        // the answer names the concrete index, not the alias the service is configured with
        server.expect(requestTo(BASE + "/articulorium/_mapping")).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"articulorium-000001\":{\"mappings\":{\"properties\":{\"id\":{\"type\":\"keyword\"},"
                + "\"created\":{\"type\":\"date\"}}}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/articulorium/_mapping")).andExpect(method(HttpMethod.PUT))
            .andExpect(content().json("{\"properties\":{\"lastModified\":{\"type\":\"date\",\"ignore_malformed\":true},"
                + "\"pages\":{\"type\":\"integer\"}}}", org.springframework.test.json.JsonCompareMode.STRICT))
            .andRespond(withSuccess("{\"acknowledged\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.ensureFields()).containsExactly("lastModified", "pages");
        server.verify();
    }

    @Test
    void declaresNothingWhenEveryFieldExists() {
        properties.setFields(Map.of("created", "date"));
        server.expect(requestTo(BASE + "/articulorium/_mapping")).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"articulorium\":{\"mappings\":{\"properties\":{\"created\":{\"type\":\"date\"}}}}}",
                MediaType.APPLICATION_JSON));

        assertThat(client.ensureFields()).isEmpty();
        server.verify();
    }

    @Test
    void declaresNothingWithoutConfiguredFields() {
        assertThat(client.ensureFields()).isEmpty();
        server.verify();
    }

    @Test
    void addsDocumentsUnderTheirEncodedIdentifier() {
        server.expect(requestTo(BASE + "/articulorium/_doc/http%3A%2F%2Fh%2Fa%20b"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(content().json("{\"id\":\"http://h/a b\",\"title\":[\"A\"]}"))
            .andRespond(withSuccess("{\"result\":\"created\"}", MediaType.APPLICATION_JSON));

        client.add(Map.of("id", "http://h/a b", "title", List.of("A")));
        server.verify();
    }

    @Test
    void deletesDocumentsById() {
        server.expect(requestTo(BASE + "/articulorium/_doc/http%3A%2F%2Fh%2Fa")).andExpect(method(HttpMethod.DELETE))
            .andRespond(withSuccess("{\"result\":\"deleted\"}", MediaType.APPLICATION_JSON));

        client.deleteById("http://h/a");
        server.verify();
    }

    @Test
    void deletingADocumentThatIsNotIndexedIsNotAnError() {
        server.expect(requestTo(BASE + "/articulorium/_doc/http%3A%2F%2Fh%2Fa")).andExpect(method(HttpMethod.DELETE))
            .andRespond(withResourceNotFound().contentType(MediaType.APPLICATION_JSON).body("{\"result\":\"not_found\"}"));

        client.deleteById("http://h/a");
        server.verify();
    }

    @Test
    void identifiersBeyondElasticsearchsLimitAreDigested() {
        final String id = "http://h/" + "a".repeat(600);

        assertThat(ElasticsearchIndexClient.documentId(id)).startsWith("sha256:").hasSize(7 + 64)
            .isEqualTo(ElasticsearchIndexClient.documentId(id));
        assertThat(ElasticsearchIndexClient.documentId("http://h/a")).isEqualTo("http://h/a");
        // the limit counts bytes: 200 three-byte characters are too long
        assertThat(ElasticsearchIndexClient.documentId("€".repeat(200))).startsWith("sha256:");
    }

    @Test
    void reportsTheDocumentCountAsItsCondition() {
        server.expect(requestTo(BASE + "/articulorium/_count")).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"count\":12,\"_shards\":{}}", MediaType.APPLICATION_JSON));

        assertThat(client.status()).isEqualTo(new IndexStatus(true, "OK", 12));
        server.verify();
    }

    @Test
    void sendsNoCredentialsUnlessConfigured() {
        server.expect(requestTo(BASE + "/articulorium/_count")).andExpect(headerDoesNotExist("Authorization"))
            .andRespond(withSuccess("{\"count\":0}", MediaType.APPLICATION_JSON));

        client.status();
        server.verify();
    }

    @Test
    void authenticatesWithAnApiKey() {
        properties.setApiKey("a2V5OnNlY3JldA==");
        properties.setUsername("ignored");
        client = client(properties);
        server.expect(requestTo(BASE + "/articulorium/_count")).andExpect(header("Authorization", "ApiKey a2V5OnNlY3JldA=="))
            .andRespond(withSuccess("{\"count\":0}", MediaType.APPLICATION_JSON));

        client.status();
        server.verify();
    }

    @Test
    void authenticatesWithAUserAndPassword() {
        properties.setUsername("elastic");
        properties.setPassword("changeme");
        client = client(properties);
        // elastic:changeme
        server.expect(requestTo(BASE + "/articulorium/_count")).andExpect(header("Authorization", "Basic ZWxhc3RpYzpjaGFuZ2VtZQ=="))
            .andRespond(withSuccess("{\"count\":0}", MediaType.APPLICATION_JSON));

        client.status();
        server.verify();
    }

    @Test
    void describesItselfWithoutCredentials() {
        properties.setPassword("changeme");

        assertThat(client.name()).isEqualTo("elasticsearch");
        assertThat(client.target()).isEqualTo("Elasticsearch index articulorium");
        assertThat(client.location()).containsOnlyKeys("baseUrl", "index");
    }

}
