package com.articulorium.discovery.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.articulorium.discovery.access.AccessVerifier;
import com.articulorium.discovery.config.DiscoveryProperties;
import com.articulorium.discovery.config.PublicUrls;
import com.articulorium.discovery.iiif.IiifSummary;
import com.articulorium.discovery.iiif.IiifSummaryClient;
import com.articulorium.discovery.solr.SolrRequests;
import com.articulorium.discovery.solr.SolrSchema;
import com.articulorium.discovery.solr.SolrSearchClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Tests the descriptions of resource pages against a mocked Solr, mocked access checks and mocked
 * IIIF summaries.
 */
class ItemServiceTest {

    private static final String P = "http://localhost:8000/platform/";

    private MockRestServiceServer solr;

    private DiscoveryProperties properties;

    private AccessVerifier access;

    private IiifSummaryClient iiif;

    private ItemService service;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        solr = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        properties = SearchServiceTest.settings();
        final DiscoveryProperties.Field creator = new DiscoveryProperties.Field();
        creator.setField("creator");
        final DiscoveryProperties.Field license = new DiscoveryProperties.Field();
        license.setField("license");
        license.setLink(true);
        properties.getLayout().getItem().getFields().add(creator);
        properties.getLayout().getItem().getFields().add(license);
        properties.getLayout().getItem().setRepositoryLink(true);
        access = mock(AccessVerifier.class);
        when(access.mayRead(anyString(), any())).thenReturn(true);
        iiif = mock(IiifSummaryClient.class);
        final PublicUrls urls = new PublicUrls(properties);
        final SolrSearchClient client = new SolrSearchClient(builder, properties);
        service = new ItemService(client, access, iiif, urls, properties,
            new SearchService(client, mock(SolrSchema.class), access, urls, properties));
        when(access.readable(anyCollection(), any())).thenAnswer(invocation -> invocation.<Collection<String>>getArgument(0)
            .stream().filter(path -> access.mayRead(path, invocation.getArgument(1))).toList());
        // the documents Solr holds, some identified by the platform's internal address; there is
        // no document for "communities" (outside the discovery layer)
        final Map<String, String> docs = Map.of(
            "communities/c/moby", """
                {"id": "http://articulorium-platform/communities/c/moby", "type": ["http://pcdm.org/models#Object"],
                 "title": ["Moby Dick"], "title_txt_fr": ["Moby Dick ou le Cachalot"],
                 "description": ["A whale of a tale"], "creator": ["Herman Melville", "Herman Melville"],
                 "license": ["https://creativecommons.org/publicdomain/mark/1.0/", "javascript:alert(1)"],
                 "created": ["2026-01-01T00:00:00Z"], "lastModified": ["2026-02-01T00:00:00Z"],
                 "languages": ["en", "fr"]}
                """,
            "communities/top/c", """
                {"id": "http://articulorium-platform/communities/top/c",
                 "type": ["https://articulorium.org/ns/repository#Collection"], "title": ["Rare books"]}
                """,
            "communities/top", """
                {"id": "%scommunities/top", "type": ["https://articulorium.org/ns/repository#Community"],
                 "title": ["Library"]}
                """.formatted(P),
            "communities/top/c/moby", """
                {"id": "%scommunities/top/c/moby", "type": ["http://pcdm.org/models#Object"], "title": ["Moby Dick"]}
                """.formatted(P));
        // Solr answers with the documents whose identifiers are asked for
        solr.expect(manyTimes(), requestTo("http://solr.test/solr/articulorium/select")).andRespond(request -> {
            final MultiValueMap<String, String> form = SolrRequests.form(((MockClientHttpRequest) request).getBodyAsString());
            assertThat(form.get("fq")).as("pages are limited to the discovery layer").contains("-id:*\\/members\\/*");
            // id:("<public IRI>" OR "<internal IRI>" ...)
            final String q = form.getFirst("q");
            assertThat(q).contains("\"http://articulorium-platform/");
            final String found = docs.entrySet().stream()
                .filter(doc -> q.contains("\"" + P + doc.getKey() + "\"")
                    && q.contains("\"http://articulorium-platform/" + doc.getKey() + "\""))
                .map(Map.Entry::getValue)
                .collect(Collectors.joining(","));
            return withSuccess("{\"response\": {\"docs\": [" + found + "]}}", MediaType.APPLICATION_JSON)
                .createResponse(request);
        });
    }

    @Test
    void describesResourcesInTheVisitorsLanguage() {
        final IiifSummary summary = new IiifSummary(P.replace("platform", "transform") + "iiif/communities/c/moby",
            "Manifest", "https://images.example.org/moby.jpg", 2, 2, 0, 0, 1, 0);
        when(iiif.summary("communities/c/moby", null)).thenReturn(Optional.of(summary));

        final ItemService.ItemView item = service.item("/communities/c/moby/", "fr", null);

        assertThat(item.path()).isEqualTo("communities/c/moby");
        assertThat(item.iri()).isEqualTo(P + "communities/c/moby");
        assertThat(item.title()).isEqualTo("Moby Dick ou le Cachalot");
        assertThat(item.description()).isEqualTo("A whale of a tale");
        assertThat(item.kind()).isEqualTo(ResourceKind.OBJECT);
        assertThat(item.created()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(item.modified()).isEqualTo("2026-02-01T00:00:00Z");
        assertThat(item.languages()).containsExactly("en", "fr");
        assertThat(item.fields()).containsExactly(
            new ItemService.ItemView.FieldValues("creator", java.util.List.of("Herman Melville"), false),
            new ItemService.ItemView.FieldValues("license",
                java.util.List.of("https://creativecommons.org/publicdomain/mark/1.0/"), true));
        assertThat(item.trail()).as("a resource without indexed ancestors has no trail").isEmpty();
        assertThat(item.iiif()).isEqualTo(summary);
        assertThat(item.thumbnail()).isEqualTo("https://images.example.org/moby.jpg");
        assertThat(item.repositoryUrl()).isEqualTo("http://localhost:8000/ui/browse/communities/c/moby");
    }

    @Test
    void listsTheIndexedAncestorsOutermostFirst() {
        when(iiif.summary(anyString(), any())).thenReturn(Optional.empty());

        final ItemService.ItemView item = service.item("communities/top/c/moby", "en", null);

        // "communities" is not in the index
        assertThat(item.trail()).containsExactly(
            new ItemService.ItemView.Crumb("communities/top", "Library", ResourceKind.COMMUNITY),
            new ItemService.ItemView.Crumb("communities/top/c", "Rare books", ResourceKind.COLLECTION));
    }

    @Test
    void leavesAncestorsTheVisitorMayNotReadOutOfTheTrail() {
        when(iiif.summary(anyString(), any())).thenReturn(Optional.empty());
        when(access.mayRead(eq("communities/top"), any())).thenReturn(false);

        final ItemService.ItemView item = service.item("communities/top/c/moby", null, null);

        assertThat(item.title()).isEqualTo("Moby Dick");
        assertThat(item.trail()).extracting(ItemService.ItemView.Crumb::path).containsExactly("communities/top/c");
        assertThat(item.iiif()).isNull();
        assertThat(item.thumbnail()).isNull();
    }

    @Test
    void reportsPrivateMissingAndExcludedResourcesAlike() {
        when(access.mayRead(eq("private"), eq("Bearer bob"))).thenReturn(false);

        assertThatThrownBy(() -> service.item("private", null, "Bearer bob")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.item("communities/c/page-1", null, null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.item("", null, null)).isInstanceOf(NotFoundException.class);
        verify(iiif, never()).summary(anyString(), any());
    }

    @Test
    void refusesMalformedPaths() {
        assertThatThrownBy(() -> service.item("a/../b", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.item("a//b", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.item("a?b", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.item("x".repeat(2000), null, null)).isInstanceOf(IllegalArgumentException.class);
    }

}
