package com.articulorum.topology.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Settings of the topology service ({@code articulorum.topology.*}).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "articulorum.topology")
public class TopologyProperties {

    /**
     * Where the platform's LDP root is reached. With {@link #isLoadBalanced()} the host is the
     * Consul service name.
     */
    private String platformUrl = "http://articulorum-platform/";

    /** Whether {@link #platformUrl} names a Consul service resolved by the load balancer. */
    private boolean loadBalanced = true;

    /** Container (below the LDP root) holding custom topology definitions. */
    private String container = "topologies";

    /** Largest number of children of one kind created by a single build request. */
    private int maxChildren = 500;

}
