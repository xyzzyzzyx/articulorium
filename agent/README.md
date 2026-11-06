# Agent

Library module for Articulorium agents: named, automated actors that work against the Linked Data
Platform. It provides the `Agent` value type and `AgentRegistry`, an immutable set of agents
looked up by name. It is plain Java with no framework dependencies (the build fails if Spring
reaches its compile or runtime class path), so any application can use it directly:

```java
AgentRegistry agents = AgentRegistry.of(new Agent("owl", "read owl"), new Agent("lark", "sing"));
agents.find("owl").ifPresent(owl -> System.out.println(owl.getDescription()));
```

Spring Boot applications use [agent-spring-boot-starter](../agent-spring-boot-starter/README.md)
instead, which builds the registry from configuration and `Agent` beans.

```bash
mvn -pl agent verify
```
