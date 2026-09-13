FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -q -DskipTests dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2,sharing=shared mvn -q -DskipTests package
FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 ledgerbank
USER ledgerbank
COPY --from=build /workspace/target/*.jar /app.jar
EXPOSE 8084
ENTRYPOINT ["java","-jar","/app.jar"]
