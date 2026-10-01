# ---- build ----------------------------------------------------------------
# Maven image pinned to JDK 21, matching <maven.compiler.release> in pom.xml.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Dependencies first, in their own layer: pom.xml changes rarely, source changes
# constantly, so this keeps a code edit from re-downloading the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- runtime --------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Unprivileged: this process makes outbound HTTP requests to sixty third-party
# hosts and parses whatever they return, which is not work for root.
RUN groupadd --system app && useradd --system --gid app --home /app app

COPY --from=build /app/target/*.jar app.jar
USER app

# Containers get no cron, so the long-running service carries its own scheduler.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
