package com.articulorium.service.api;

import com.articulorium.agent.Agent;
import com.articulorium.agent.AgentRegistry;
import com.articulorium.i18n.Messages;
import com.articulorium.service.config.UiProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Configuration and agent information for the user interface.
 */
@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "User interface", description = "Settings and agents for the user interface")
public class UiApiController {

    private final UiProperties properties;

    private final AgentRegistry agents;

    private final Messages messages;

    /**
     * Creates the controller.
     *
     * @param properties user interface settings
     * @param agents     the agents known to the service
     * @param messages   error messages in the request's locale
     */
    public UiApiController(final UiProperties properties, final AgentRegistry agents,
            final Messages messages) {
        this.properties = properties;
        this.agents = agents;
        this.messages = messages;
    }

    /**
     * Returns the settings the user interface needs at start-up.
     *
     * @return the settings
     */
    @GetMapping("/config")
    @Operation(operationId = "uiConfig", summary = "Settings of the user interface")
    public UiProperties config() {
        return properties;
    }

    /**
     * Lists the agents known to the service.
     *
     * @return the agents
     */
    @GetMapping("/agents")
    @Operation(operationId = "agents", summary = "List the known agents")
    public List<Agent> agents() {
        return agents.getAgents();
    }

    /**
     * Returns one agent.
     *
     * @param name the name of the agent
     * @return the agent
     * @throws ResponseStatusException 404 when no agent has that name
     */
    @GetMapping("/agents/{name}")
    @Operation(operationId = "agent", summary = "One known agent")
    public Agent agent(@PathVariable final String name) {
        return agents.find(name)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                messages.get("agent.not-found", name)));
    }

}
