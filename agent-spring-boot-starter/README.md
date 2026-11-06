# Agent Spring Boot starter

Spring Boot integration for the [agent library](../agent/README.md). Add the dependency and the
application gets an `AgentRegistry` bean through auto-configuration:

```xml
<dependency>
  <groupId>com.articulorium</groupId>
  <artifactId>agent-spring-boot-starter</artifactId>
</dependency>
```

It brings `agent` and `spring-boot-autoconfigure` with it; nothing else is required. Spring Boot
Actuator is optional (see below).

## Declaring agents

Agents come from two places, combined in this order:

1. every `Agent` bean in the context, in `@Order` order;
2. the agents declared under `articulorium.agents.definitions`, keyed by name, in declaration order.

```yaml
articulorium:
  agents:
    definitions:
      owl:
        description: read owl
      "[ocr.v2]":            # names with dots or other special characters need brackets
        description: read scanned pages
```

Because definitions are keyed by name, a single agent can be added or changed from any property
source: an environment variable (`ARTICULORIUM_AGENTS_DEFINITIONS_LARK_DESCRIPTION=sing`), a
command line argument or a Consul key. Two agents with the same name stop the application at
start-up with a message naming the agent. The registry is logged at start-up
(`Registered 1 agent(s): [owl]`).

## Using agents

Inject the registry like any other bean:

```java
@RestController
class AgentController {

    private final AgentRegistry agents;

    AgentController(AgentRegistry agents) {
        this.agents = agents;
    }

    @GetMapping("/agents/{name}")
    Agent agent(@PathVariable String name) {
        return agents.find(name).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

}
```

Defining your own `AgentRegistry` bean replaces the auto-configured one.

## Settings

| Property                            | Default | What                                                         |
|-------------------------------------|---------|--------------------------------------------------------------|
| `articulorium.agents.enabled`       | `true`  | `false` turns the auto-configuration off                     |
| `articulorium.agents.definitions.<name>.description` | | an agent declared in configuration                 |
| `management.info.agents.enabled`    | `true`  | `false` leaves the agents out of `/actuator/info`            |

The properties are described in the generated configuration metadata, so IDEs complete them.

## Actuator

With Spring Boot Actuator on the class path, `/actuator/info` reports the agents:

```json
"agents": { "count": 1, "names": ["owl"] }
```

## Compatibility

Built and tested against the Spring Boot version of the reactor (4.1). Applications that do not
use Spring depend on `agent` alone; it is unaffected by this module.

```bash
mvn -pl agent-spring-boot-starter -am verify
```
