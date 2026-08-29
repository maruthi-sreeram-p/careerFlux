# =============================================================================
# CareerFlux backend
#
# Two stages: the build stage keeps Maven and the JDK out of the final image,
# which is a JRE plus one jar.
# =============================================================================

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies resolve in their own layer, so a source-only change does not
# re-download the world.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests


FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Never run as root. Resume files are candidate data; the process that writes
# them should own as little as possible.
RUN addgroup -S careerflux && adduser -S -G careerflux careerflux \
    && mkdir -p /app/data/resumes \
    && chown -R careerflux:careerflux /app

COPY --from=build --chown=careerflux:careerflux /build/target/careerflux-backend-*.jar /app/app.jar

USER careerflux
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

# Container-aware heap sizing rather than a fixed -Xmx, so the same image
# behaves correctly whatever memory limit it is given.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
