# syntax=docker/dockerfile:1.7
#
# JVM image for one Articulorum service.
#
#   docker build --build-arg MODULE=platform -t articulorum/platform .
#
# The build context is the repository root because every service depends on the shared domain
# and event modules. Maven's local repository is kept in a BuildKit cache mount, so building
# several services in a row only downloads dependencies once. The Angular UI (ui module, needed
# by the service module) is built by Maven with a Node.js runtime it downloads itself; other
# modules skip it.

ARG MODULE=platform

FROM maven:3.9-eclipse-temurin-25 AS build
ARG MODULE
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl "${MODULE}" -am package -DskipTests \
 && cp "${MODULE}/target/${MODULE}-"*.jar /workspace/app.jar

FROM eclipse-temurin:25-jre
ARG MODULE
LABEL org.opencontainers.image.title="articulorum-${MODULE}" \
      org.opencontainers.image.source="https://github.com/william-stanley-welling/articulorium"
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --home-dir /app articulorum
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
RUN mkdir -p /app/data && chown -R articulorum /app
USER articulorum
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# 9000: service API, 8000: actuator (health)
EXPOSE 9000 8000
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
