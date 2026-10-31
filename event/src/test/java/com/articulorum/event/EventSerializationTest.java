package com.articulorum.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.articulorum.domain.BinaryContent;
import com.articulorum.domain.InteractionModel;
import com.articulorum.domain.LdpResource;
import com.articulorum.domain.Triple;
import com.articulorum.domain.vocabulary.Ldp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies that bus events survive a JSON round trip, which is how Spring Cloud Bus transports
 * them over Kafka. The mapper is configured like the Jackson 3 mapper Spring Cloud Bus 5 uses.
 */
class EventSerializationTest {

    private JsonMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // as configured by BusConfiguration#busEventFinalFieldsCustomizer
            .enable(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS)
            .registerSubtypes(ResourceEvent.class, DocumentEvent.class)
            .build();
    }

    @Test
    void resourceEventRoundTrip() throws Exception {
        final LdpResource resource = new LdpResource("files/report.pdf", InteractionModel.NON_RDF_SOURCE);
        resource.setBinary(new BinaryContent("key-1", "v1", "application/pdf", "report.pdf", 42L, "abc"));
        resource.createdBy("alice");
        resource.setVersioned(true);
        resource.setTriples(List.of(Triple.literal("/files/report.pdf", "http://purl.org/dc/terms/title", "Report")));
        final List<Triple> triples = List.of(Triple.iri("/files", Ldp.CONTAINS, "/files/report.pdf"));
        final ResourceEvent event = new ResourceEvent(this, "platform:1", EventDestinations.all(), resource,
            EventAction.CREATED, "http://localhost:9000", triples);

        final ResourceEvent copy = mapper.readValue(mapper.writeValueAsString(event), ResourceEvent.class);

        assertThat(copy.getAction()).isEqualTo(EventAction.CREATED);
        assertThat(copy.getBaseUri()).isEqualTo("http://localhost:9000");
        assertThat(copy.getOriginService()).isEqualTo("platform:1");
        assertThat(copy.getTriples()).isEqualTo(triples);
        assertThat(copy.getResource().getId()).isEqualTo(resource.getId());
        assertThat(copy.getResource().getPath()).isEqualTo("files/report.pdf");
        assertThat(copy.getResource().getParentPath()).isEqualTo("files");
        assertThat(copy.getResource().getBinary()).isEqualTo(resource.getBinary());
        assertThat(copy.getResource().getTriples()).isEqualTo(resource.getTriples());
        assertThat(copy.getResource().getCreated()).isEqualTo(resource.getCreated());
        assertThat(copy.getResource().getCreatedBy()).isEqualTo("alice");
        assertThat(copy.getResource().isVersioned()).isTrue();
    }

    @Test
    void documentEventRoundTrip() throws Exception {
        final Map<String, Collection<?>> document = Map.of("id", List.of("http://h/a"), "title", List.of("A"));
        final DocumentEvent event = new DocumentEvent(this, "transform:1", EventDestinations.all(), document,
            EventAction.DELETED);

        final DocumentEvent copy = mapper.readValue(mapper.writeValueAsString(event), DocumentEvent.class);

        assertThat(copy.getAction()).isEqualTo(EventAction.DELETED);
        assertThat(copy.documentId()).isEqualTo("http://h/a");
        assertThat(copy.getDocument()).containsKeys("id", "title");
    }

    @Test
    void documentIdIsNullWithoutIdField() {
        final DocumentEvent event = new DocumentEvent(this, "t", EventDestinations.all(), Map.of(), EventAction.CREATED);
        assertThat(event.documentId()).isNull();
    }

}
