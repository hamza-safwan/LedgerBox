FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -q -DskipTests dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2,sharing=shared mvn -q -DskipTests package
FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && useradd --system --uid 10001 ledgerbank && mkdir -p /var/lib/ledgerbank && chown 10001 /var/lib/ledgerbank
USER ledgerbank
COPY --from=build /workspace/target/*.jar /app.jar
EXPOSE 8081
ENTRYPOINT ["java","-jar","/app.jar"]
