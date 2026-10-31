package com.articulorum.gateway;

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
            .contains("articulorum-platform", "articulorum-transform", "articulorum-topology", "articulorum-service",
                "articulorum-root", "articulorum-ui-slash");
        assertThat(routes).filteredOn(route -> route.getId().equals("articulorum-platform"))
            .singleElement()
            .satisfies(route -> {
                assertThat(route.getUri().toString()).isEqualTo("lb://articulorum-platform");
                assertThat(route.getFilters()).isNotEmpty();
            });
        assertThat(routes).filteredOn(route -> route.getId().equals("articulorum-topology"))
            .singleElement()
            .satisfies(route -> assertThat(route.getUri().toString()).isEqualTo("lb://articulorum-topology"));
        assertThat(routes).filteredOn(route -> route.getId().equals("articulorum-service"))
            .singleElement()
            .satisfies(route -> assertThat(route.getUri().toString()).isEqualTo("lb://articulorum-service"));
    }

}
