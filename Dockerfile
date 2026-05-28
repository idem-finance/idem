# Stage 1 — build all Maven modules, produce the executable jar
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw mvnw
COPY pom.xml pom.xml
COPY core/pom.xml core/pom.xml
COPY application/pom.xml application/pom.xml
COPY infrastructure/pom.xml infrastructure/pom.xml
COPY api/pom.xml api/pom.xml
COPY mcp/pom.xml mcp/pom.xml
COPY app/pom.xml app/pom.xml
# Resolve dependencies in a cacheable layer before copying source
RUN ./mvnw dependency:go-offline --no-transfer-progress -q
COPY core/src core/src
COPY application/src application/src
COPY infrastructure/src infrastructure/src
COPY api/src api/src
COPY mcp/src mcp/src
COPY app/src app/src
RUN ./mvnw package -DskipTests --no-transfer-progress

# Stage 2 — minimal JRE image
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /workspace/app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
