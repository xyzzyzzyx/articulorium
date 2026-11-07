package com.articulorium.harvest.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Tests the platform client's search, creation and caller credentials.
 */
class PlatformSearchTest {

    @Test
    void searchesWithTheCallersCredentials() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        final PlatformClient harvester = new PlatformClient(builder.build(), "http://platform.test/",
            PlatformAuthorization.fixed("Bearer harvester"));
        server.expect(requestTo(Matchers.startsWith("http://platform.test/fcr:search?")))
            .andExpect(queryParam("condition", "rdf_type%3Dhttps%3A%2F%2Farticulorium.org%2Fns%2Frepository%23ReviewPending",
                "fedora_id%3Dinfo%3Afedora%2Fharvested%2Frepo%2F*"))
            .andExpect(queryParam("max_results", "30")).andExpect(queryParam("offset", "20"))
            .andExpect(queryParam("order_by", "modified")).andExpect(queryParam("order", "desc"))
            .andExpect(header("Authorization", "Bearer alice"))
            .andRespond(withSuccess("""
                {"items": [{"fedora_id": "http://platform.test/harvested/repo/a/classification"},
                           {"fedora_id": "info:fedora/harvested/repo/b/classification"},
                           {"fedora_id": "http://elsewhere.test/x"}],
                 "pagination": {"totalResults": 42}}
                """, MediaType.APPLICATION_JSON));

        final PlatformClient.SearchPage page = harvester.as("Bearer alice").search(List.of(
            "rdf_type=https://articulorium.org/ns/repository#ReviewPending",
            "fedora_id=info:fedora/harvested/repo/*"), 20, 10);

        assertThat(page.paths()).containsExactly("harvested/repo/a/classification", "harvested/repo/b/classification");
        assertThat(page.total()).isEqualTo(42);
        server.verify();
    }

    @Test
    void createsResourcesWithASlug() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        final PlatformClient client = new PlatformClient(builder.build(), "http://platform.test/",
            PlatformAuthorization.fixed(null));
        server.expect(requestTo("http://platform.test/communities/c"))
            .andExpect(header("Slug", "item"))
            .andExpect(header("Link", "<http://www.w3.org/ns/ldp#BasicContainer>; rel=\"type\""))
            .andRespond(withStatus(HttpStatus.CREATED).location(URI.create("/communities/c/item-2")));

        assertThat(client.create("communities/c", "item", "<> <http://purl.org/dc/terms/title> \"t\" .\n",
            "http://www.w3.org/ns/ldp#BasicContainer")).isEqualTo("communities/c/item-2");
        assertThat(client.pathOf("http://platform.test/a/b")).contains("a/b");
        assertThat(client.pathOf("http://platform.test/a#x")).isEmpty();
    }

}
