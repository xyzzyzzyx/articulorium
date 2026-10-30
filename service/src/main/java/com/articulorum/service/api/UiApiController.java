package com.articulorum.service.api;

import com.articulorum.agent.Agent;
import com.articulorum.service.config.UiProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configuration and agent information for the user interface.
 */
@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "User interface", description = "Settings and agents for the user interface")
public class UiApiController {

    private final UiProperties properties;

    private final List<Agent> agents;

    /**
     * Creates the controller.
     *
     * @param properties user interface settings
     * @param agents     the agents known to the service
     */
    public UiApiController(final UiProperties properties, final List<Agent> agents) {
        this.properties = properties;
        this.agents = List.copyOf(agents);
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
        return agents;
    }

}
