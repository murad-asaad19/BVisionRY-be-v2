# syntax=docker/dockerfile:1

###############################################################################
# Stage 1 — build the executable (layered) jar with Maven + Temurin 21
###############################################################################
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Copy the POM first and resolve dependencies so this layer is cached and only
# re-runs when pom.xml changes (not on every source edit).
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline

# Now copy sources and build. Tests are skipped here; CI runs them separately.
COPY src ./src
RUN mvn -q -B -DskipTests package

# Normalise the built artifact name, then extract the layered jar using the
# Spring Boot 3.5 tools jarmode. This produces dependency / spring-boot-loader /
# snapshot-dependencies / application layers ordered by change frequency, plus a
# slim launcher application.jar that references the extracted dependency layers.
RUN cp "$(ls target/*.jar | grep -v -- '-sources\.jar' | head -n 1)" application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

###############################################################################
# Stage 2 — minimal JRE runtime
###############################################################################
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# curl is used by the Compose healthcheck (GET /api/v1/health).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Run as a non-root user.
RUN groupadd --system spring && useradd --system --gid spring spring

# Copy extracted layers least-changed first to maximise Docker layer cache hits.
COPY --from=build --chown=spring:spring /workspace/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/application/ ./

USER spring

EXPOSE 8080

# Cap the heap relative to the container memory limit; leave headroom for
# metaspace, threads and direct buffers.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

# The slim launcher jar references the extracted dependency layers; it is the
# Spring Boot 3.5 recommended entrypoint (NOT the original uber jar).
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar application.jar"]
