# GraalVM Native Builds in Articulorium

## Overview

Articulorium supports building services as GraalVM native images, providing an alternative deployment option with benefits like faster startup times and reduced memory consumption.

## Why Support Native Builds?

1. **Performance**: Native images start faster and use less memory than JVM-based containers
2. **Deployment Flexibility**: Users can choose between traditional JVM and GraalVM native deployments
3. **Resource Efficiency**: Particularly beneficial in containerized environments where resource usage matters

## Infrastructure

The project includes comprehensive infrastructure for native builds:

- `Dockerfile.native`: Dockerfile specifically for building native images
- `docker-compose.native.yml`: Compose configuration for running services as native images  
- Maven profiles (`-Pnative`) in parent pom.xml and individual service modules
- Support for GraalVM native-image compilation through the `native-maven-plugin`

## Building Native Images

To build a native image for a specific service:

```bash
# Build a single service (e.g., platform)
mvn -Pnative -pl platform -am package -DskipTests

# Or use Docker Compose with native profiles
docker compose -f docker-compose.yml -f docker-compose.native.yml build <service-name>
```

## Limitations

1. **Build Complexity**: Native builds require GraalVM JDK 25+ and are memory-intensive (a 10 GB builder heap per build by default, `NATIVE_MAX_HEAP`)
2. **Longer Build Times**: Native compilation takes significantly longer than JVM builds
3. **Compatibility Considerations**: Some dynamic features may need special configuration for native builds

## Class path resources

A native image holds only the class path resources it is told about. A resource a service opens at
run time (`classpath:...`, `ClassPathResource`, a resource pattern) must be registered by a
`RuntimeHintsRegistrar` of its module, or opening it fails in the native image only ("class path
resource [...] cannot be opened because it does not exist"):

| Module | Registrar | Resources |
|--------|-----------|-----------|
| platform | `PlatformRuntimeHints` | `namespaces.yml`, `root.ttl`, `ldp-constraints.txt`, the root ACLs `webac/*.ttl`, Jena's own resources |
| topology | `TopologyRuntimeHints` | the built-in topologies `topologies/*.json` |
| transform | `TransformRuntimeHints` | `transform/field-mappings.yml` |
| i18n starter | `I18nRuntimeHints` | the message bundles `i18n/*.properties` |

Each has a test (`*RuntimeHintsTest`); the platform's fails when a file is added below
`src/main/resources` without a hint. Records that are only read from JSON by hand (the answers of
the AI agent's model, for example) need `@RegisterReflectionForBinding` in the same way.

## Settings fixed at build time

Spring AOT evaluates bean conditions (`@ConditionalOn...Property`) while the image is built. A
setting that turns beans on or off therefore has the value of the **build**; changing it for the
container has no effect. The `application-native.yml` of each service holds the values the images
are built with:

| Setting | Built with | To build otherwise |
|---------|------------|--------------------|
| `spring.ai.model.chat` (ai) | `ollama` | `ARTICULORIUM_AI_PROVIDER` in the build environment |
| `articulorium.harvest.classification.enabled` (harvest) | `true` | `ARTICULORIUM_HARVEST_CLASSIFICATION_ENABLED=false` in the build environment |
| `articulorium.storage.type` (platform) | `s3` | `ARTICULORIUM_STORAGE_TYPE=filesystem` in the build environment |
| `articulorium.*.load-balanced` | `true` | the matching environment variable in the build environment |
| `spring.cloud.loadbalancer.eager-load.clients` (every service with load-balanced clients) | the `articulorium-*` services of the compose stack | edit the service's `application-native.yml`: a load-balanced URL (gateway `lb://` route, `articulorium.*.*-url`) may only name a service listed there |

Settings that are read by beans (URLs, credentials, limits, `articulorium.ai.enabled`, the root ACL
location, `articulorium.webac.oidc.*` and `articulorium.webac.consul.*`, the search index backend
`articulorium.index.backend` / `articulorium.discovery.index.backend`, and the SPARQL store
`articulorium.triplestore.backend`) work at run time as on the
JVM.

## Maintenance Status

Native builds are actively maintained as part of the core project infrastructure and are tested in the CI/CD pipeline.
