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

1. **Build Complexity**: Native builds require GraalVM JDK 25+ and are memory-intensive (up to 8GB per build)
2. **Longer Build Times**: Native compilation takes significantly longer than JVM builds
3. **Compatibility Considerations**: Some dynamic features may need special configuration for native builds

## Maintenance Status

Native builds are actively maintained as part of the core project infrastructure and are tested in the CI/CD pipeline.