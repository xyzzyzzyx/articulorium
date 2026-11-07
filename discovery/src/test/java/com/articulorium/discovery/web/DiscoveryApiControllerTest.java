package com.articulorium.discovery.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.articulorium.discovery.search.ItemService;
import com.articulorium.discovery.search.NotFoundException;
import com.articulorium.discovery.search.ResourceKind;
import com.articulorium.discovery.search.SearchRequest;
import com.articulorium.discovery.search.SearchResponse;
import com.articulorium.discovery.search.SearchService;
import com.articulorium.discovery.search.SitemapService;
import com.articulorium.discovery.solr.SolrUnavailableException;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests the discovery API over the full application context with the shipped settings and mocked
 * services.
 */
// Consul is turned off before the configuration data (spring.config.import) is resolved
@SpringBootTest(properties = {"spring.cloud.consul.enabled=false", "spring.cloud.consul.config.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DiscoveryApiControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SearchService search;

    @MockitoBean
    private ItemService items;

    @MockitoBean
    private SitemapService sitemap;

    @Test
    void servesThePublicPartOfTheConfiguration() throws Exception {
        final String body = mvc.perform(get("/api/config"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "max-age=60, public"))
            .andExpect(jsonPath("$.site.name.value").value("Articulorium"))
            .andExpect(jsonPath("$.site.description.key").value("site.description"))
            .andExpect(jsonPath("$.theme.colorScheme").value("light"))
            .andExpect(jsonPath("$.layout.home.sections[0]").value("HERO"))
            .andExpect(jsonPath("$.layout.item.fields[0].field").value("creator"))
            .andExpect(jsonPath("$.search.facets[0].id").value("type"))
            .andExpect(jsonPath("$.search.facets[0].values['http://pcdm.org/models#Object'].key").value("kinds.object"))
            .andExpect(jsonPath("$.search.facets[5].type").value("YEAR_RANGE"))
            .andExpect(jsonPath("$.search.sorts[0].id").value("relevance"))
            .andExpect(jsonPath("$.search.defaultSort").value("relevance"))
            .andExpect(jsonPath("$.seo.robots").value("index, follow"))
            .andExpect(jsonPath("$.seo.sitemap").value(true))
            .andExpect(jsonPath("$.locales.defaultLocale").value("en"))
            .andExpect(jsonPath("$.locales.available[1].direction").value("rtl"))
            .andExpect(jsonPath("$.urls.publicUrl").value("http://localhost:8000/discover/"))
            .andExpect(jsonPath("$.urls.iiif").value("http://localhost:8000/transform/iiif/"))
            .andReturn().getResponse().getContentAsString();

        // internal addresses, base filters, boosts and Solr fields stay in the service
        assertThat(body).doesNotContain("solr").doesNotContain("articulorium-platform")
            .doesNotContain("hasParent_str").doesNotContain("type_str").doesNotContain("boost");
    }

    @Test
    void passesSearchesOn() throws Exception {
        when(search.search(any())).thenReturn(new SearchResponse("whale", 2, 10, 1, "title",
            List.of(new SearchResponse.Result("c/moby", "Moby Dick", null, ResourceKind.OBJECT, List.of(), null, null)),
            0, List.of(), List.of("digitized")));

        mvc.perform(get("/api/search").param("q", "whale").param("page", "2").param("size", "10").param("sort", "title")
                .param("f", "type:http://pcdm.org/models#Object", "type:http://purl.org/ontology/bibo/Book", "year:1850-1899")
                .param("filter", "digitized").param("within", "communities/c").param("kind", "object").param("lang", "de-CH"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "max-age=30, public"))
            .andExpect(header().string("Vary", "Authorization"))
            .andExpect(jsonPath("$.results[0].path").value("c/moby"))
            .andExpect(jsonPath("$.results[0].description").doesNotExist());

        final ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(search).search(request.capture());
        assertThat(request.getValue()).isEqualTo(new SearchRequest("whale", 2, 10, "title",
            Map.of("type", List.of("http://pcdm.org/models#Object", "http://purl.org/ontology/bibo/Book"),
                "year", List.of("1850-1899")),
            Set.of("digitized"), "communities/c", ResourceKind.OBJECT, "de", null));
    }

    @Test
    void keepsAnswersForSignedInVisitorsPrivate() throws Exception {
        when(items.item(eq("c/moby"), eq("en"), eq("Bearer alice"))).thenReturn(new ItemService.ItemView("c/moby",
            "http://localhost:8000/platform/c/moby", "Moby Dick", null, ResourceKind.OBJECT, List.of(), null, null, null,
            List.of(), List.of(), List.of(), null, null, null));

        mvc.perform(get("/api/item").param("path", "c/moby").header("Authorization", "Bearer alice"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store, private"))
            .andExpect(jsonPath("$.title").value("Moby Dick"));
    }

    @Test
    void reportsRefusalsAsProblems() throws Exception {
        when(items.item(eq("private"), any(), isNull())).thenThrow(new NotFoundException("private"));
        when(search.search(any())).thenThrow(new SolrUnavailableException("The search index is unavailable"));

        mvc.perform(get("/api/item").param("path", "private"))
            .andExpect(status().isNotFound())
            .andExpect(header().string("Content-Language", "en"))
            .andExpect(jsonPath("$.status").value(404));
        mvc.perform(get("/api/item"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/search").param("f", "novalue"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Facet values are <facet>:<value>: novalue"));
        mvc.perform(get("/api/search").param("kind", "people"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/search").param("page", "two"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/search").param("q", "whale"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.detail").value("The search index is unavailable"));
    }

    @Test
    void listsSitemapEntries() throws Exception {
        when(sitemap.entries()).thenReturn(List.of(new SitemapService.Entry("c/moby", "2026-02-01T00:00:00Z")));

        mvc.perform(get("/api/sitemap"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].path").value("c/moby"))
            .andExpect(jsonPath("$[0].modified").value("2026-02-01T00:00:00Z"));
    }

}
