package com.articulorium.agent.autoconfigure;

import com.articulorium.agent.Agent;
import com.articulorium.agent.AgentRegistry;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration of the agent library.
 *
 * <p>Contributes an {@link AgentRegistry} holding every {@link Agent} bean of the context (in
 * {@code @Order} order) followed by the agents declared under
 * {@code articulorium.agents.definitions}. Two agents with the same name stop the application
 * from starting. An application that defines its own {@link AgentRegistry} bean keeps it.</p>
 *
 * <p>With Spring Boot Actuator on the class path, {@code /actuator/info} lists the agents under
 * {@code agents} (turn off with {@code management.info.agents.enabled=false}).</p>
 *
 * <p>Set {@code articulorium.agents.enabled=false} to turn the integration off.</p>
 */
@AutoConfiguration
@ConditionalOnClass(AgentRegistry.class)
@ConditionalOnBooleanProperty(name = "articulorium.agents.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AgentProperties.class)
public class AgentAutoConfiguration {

    private static final Log LOG = LogFactory.getLog(AgentAutoConfiguration.class);

    /**
     * The registry of every known agent.
     *
     * @param properties the agents declared in configuration
     * @param beans      the agents defined as beans
     * @return the registry
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentRegistry agentRegistry(final AgentProperties properties,
            final ObjectProvider<Agent> beans) {
        final List<Agent> agents = new ArrayList<>(beans.orderedStream().toList());
        agents.addAll(properties.toAgents());
        final AgentRegistry registry = new AgentRegistry(agents);
        LOG.info("Registered " + registry.size() + " agent(s): " + registry.getNames());
        return registry;
    }

    /**
     * Actuator support, configured only when Spring Boot Actuator is present.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(InfoContributor.class)
    @ConditionalOnBooleanProperty(name = "management.info.agents.enabled", matchIfMissing = true)
    static class AgentInfoConfiguration {

        /**
         * Lists the agents in {@code /actuator/info}.
         *
         * @param registry the agents
         * @return the contributor
         */
        @Bean
        @ConditionalOnMissingBean
        AgentInfoContributor agentInfoContributor(final AgentRegistry registry) {
            return new AgentInfoContributor(registry);
        }

    }

}
