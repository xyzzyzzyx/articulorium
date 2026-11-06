package com.articulorium.agent.autoconfigure;

import com.articulorium.agent.AgentRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

/**
 * Adds the known agents to {@code /actuator/info}:
 * {@code "agents": {"count": 1, "names": ["owl"]}}.
 */
public class AgentInfoContributor implements InfoContributor {

    private final AgentRegistry registry;

    /**
     * Creates the contributor.
     *
     * @param registry the agents to report
     */
    public AgentInfoContributor(final AgentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void contribute(final Info.Builder builder) {
        final Map<String, Object> agents = new LinkedHashMap<>();
        agents.put("count", registry.size());
        agents.put("names", List.copyOf(registry.getNames()));
        builder.withDetail("agents", agents);
    }

}
