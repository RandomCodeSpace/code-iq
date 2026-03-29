# Multi-stage build
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -DskipTests -B

# Runtime
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=builder /build/target/code-iq-*.jar app.jar

# AOT cache training (optional, for faster startup)
# RUN java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -jar app.jar || true

EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseZGC", "-jar", "app.jar"]
