package com.articulorium.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

/**
 * Verifies that the gateway starts without Consul and exposes the configured routes.
 */
@SpringBootTest(properties = {
    "spring.cloud.consul.enabled=false",
    "spring.cloud.consul.config.enabled=false",
    "spring.cloud.consul.discovery.enabled=false",
    "spring.cloud.discovery.enabled=false",
    "spring.cloud.service-registry.auto-registration.enabled=false",
    "management.server.port=0",
    "server.port=0"
})
class GatewayApplicationTest {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void routesEveryServiceThroughTheLoadBalancer() {
        final List<Route> routes = routeLocator.getRoutes().collectList().block();

        assertThat(routes).extracting(Route::getId)
            .contains("articulorium-platform", "articulorium-transform", "articulorium-topology", "articulorium-service",
                "articulorium-root", "articulorium-ui-slash", "articulorium-auth", "articulorium-oai",
                "articulorium-harvest", "articulorium-ai", "articulorium-discovery-api", "articulorium-discovery",
                "articulorium-discovery-slash");
        assertThat(routes).filteredOn(route -> route.getId().equals("articulorium-platform"))
            .singleElement()
            .satisfies(route -> {
                assertThat(route.getUri().toString()).isEqualTo("lb://articulorium-platform");
                assertThat(route.getFilters()).isNotEmpty();
            });
        assertThat(routes).filteredOn(route -> route.getId().equals("articulorium-topology"))
            .singleElement()
            .satisfies(route -> assertThat(route.getUri().toString()).isEqualTo("lb://articulorium-topology"));
        // the identity provider is not registered in Consul: a fixed address, path unchanged
        assertThat(routes).filteredOn(route -> route.getId().equals("articulorium-auth"))
            .singleElement()
            .satisfies(route -> {
                assertThat(route.getUri().toString()).isEqualTo("http://keycloak:8080");
                assertThat(route.getFilters()).noneMatch(filter -> filter.toString().contains("StripPrefix"));
            });
        assertThat(routes).filteredOn(route -> route.getId().equals("articulorium-oai"))
            .singleElement()
            .satisfies(route -> {
                assertThat(route.getUri().toString()).isEqualTo("lb://articulorium-oai");
                assertThat(route.getFilters()).isNotEmpty();
            });
        // the harvest API only: the service's actuator endpoint stays on its management port
        assertThat(routes).filteredOn(route -> route.getId().equals("articulorium-harvest"))
            .singleElement()
            .satisfies(route -> {
                assertThat(route.getUri().toString()).isEqualTo("lb://articulorium-harvest");
                assertThat(route.getFilters()).anyMatch(filter -> filter.toString().contains("StripPrefix"));
            });
        assertThat(routes).filteredOn(route -> route.getId().equals("articulorium-ai"))
            .singleElement()
            .satisfies(route -> {
                assertThat(route.getUri().toString()).isEqualTo("lb://articulorium-ai");
                assertThat(route.getFilters()).anyMatch(filter -> filter.toString().contains("StripPrefix"));
            });
        // the discovery API is in Consul; the server-rendered pages run on Node.js at a fixed address,
        // with the path unchanged (their base href is /discover/), and the API's route comes first
        assertThat(routes).filteredOn(route -> route.getId().equals("articulorium-discovery-api"))
            .singleElement()
            .satisfies(route -> {
                assertThat(route.getUri().toString()).isEqualTo("lb://articulorium-discovery");
                assertThat(route.getFilters()).anyMatch(filter -> filter.toString().contains("StripPrefix"));
            });
        assertThat(routes).filteredOn(route -> route.getId().equals("articulorium-discovery"))
            .singleElement()
            .satisfies(route -> {
                assertThat(route.getUri().toString()).isEqualTo("http://discovery-ui:4000");
                assertThat(route.getFilters()).noneMatch(filter -> filter.toString().contains("StripPrefix"));
            });
        assertThat(routes).extracting(Route::getId).containsSubsequence("articulorium-discovery-api",
            "articulorium-discovery");
        assertThat(routes).filteredOn(route -> route.getId().equals("articulorium-service"))
            .singleElement()
            .satisfies(route -> assertThat(route.getUri().toString()).isEqualTo("lb://articulorium-service"));
    }

}
