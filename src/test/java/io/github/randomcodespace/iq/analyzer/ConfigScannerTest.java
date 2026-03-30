package io.github.randomcodespace.iq.analyzer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigScannerTest {

    @TempDir
    Path tempDir;

    private ConfigScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new ConfigScanner();
    }

    // -------------------------------------------------------------------------
    // Spring application.yml
    // -------------------------------------------------------------------------

    @Test
    void parsesSpringYamlDatasource() throws IOException {
        write("src/main/resources/application.yml", """
                spring:
                  application:
                    name: order-service
                  datasource:
                    url: jdbc:postgresql://localhost:5432/orders
                    username: admin
                """);

        InfrastructureRegistry registry = scanner.scan(tempDir);

        assertEquals("order-service", registry.getServiceName());
        assertTrue(registry.getDatabases().containsKey("db:spring.datasource"));
        InfraEndpoint db = registry.getDatabases().get("db:spring.datasource");
        assertEquals("postgresql", db.type());
        assertEquals("jdbc:postgresql://localhost:5432/orders", db.connectionUrl());
    }

    @Test
    void parsesSpringYamlKafka() throws IOException {
        write("application.yml", """
                spring:
                  kafka:
                    bootstrap-servers: localhost:9092,localhost:9093
                """);

        InfrastructureRegistry registry = scanner.scan(tempDir);

        assertTrue(registry.getTopics().containsKey("topic:spring.kafka"));
        assertEquals("kafka", registry.getTopics().get("topic:spring.kafka").type());
    }

    @Test
    void parsesSpringYamlRedis() throws IOException {
        write("src/main/resources/application.yml", """
                spring:
                  data:
                    redis:
                      host: redis-host
                      port: 6380
                """);

        InfrastructureRegistry registry = scanner.scan(tempDir);

        assertTrue(registry.getCaches().containsKey("cache:spring.redis"));
        InfraEndpoint cache = registry.getCaches().get("cache:spring.redis");
        assertEquals("redis", cache.type());
        assertEquals("redis://redis-host:6380", cache.connectionUrl());
    }

    @Test
    void parsesSpringYamlRabbitmq() throws IOException {
        write("application.yml", """
                spring:
                  rabbitmq:
                    host: mq-host
                    port: 5673
                """);

        InfrastructureRegistry registry = scanner.scan(tempDir);

        assertTrue(registry.getQueues().containsKey("queue:spring.rabbitmq"));
        InfraEndpoint q = registry.getQueues().get("queue:spring.rabbitmq");
        assertEquals("rabbitmq", q.type());
        assertEquals("amqp://mq-host:5673", q.connectionUrl());
    }

    // -------------------------------------------------------------------------
    // Spring application.properties
    // -------------------------------------------------------------------------

    @Test
    void parsesSpringProperties() throws IOException {
        write("src/main/resources/application.properties", """
                spring.application.name=inventory-service
                spring.datasource.url=jdbc:mysql://localhost:3306/inventory
                spring.kafka.bootstrap-servers=kafka:9092
                spring.redis.host=redis
                spring.redis.port=6379
                spring.rabbitmq.host=rabbit
                """);

        InfrastructureRegistry registry = scanner.scan(tempDir);

        assertEquals("inventory-service", registry.getServiceName());
        assertEquals("mysql", registry.getDatabases().get("db:spring.datasource").type());
        assertEquals("kafka", registry.getTopics().get("topic:spring.kafka").type());
        assertEquals("redis", registry.getCaches().get("cache:spring.redis").type());
        assertEquals("rabbitmq", registry.getQueues().get("queue:spring.rabbitmq").type());
    }

    // -------------------------------------------------------------------------
    // Docker Compose
    // -------------------------------------------------------------------------

    @Test
    void parsesDockerCompose() throws IOException {
        write("docker-compose.yml", """
                version: "3.9"
                services:
                  postgres:
                    image: postgres:15
                    ports:
                      - "5432:5432"
                  kafka:
                    image: confluentinc/cp-kafka:7.5.0
                  redis:
                    image: redis:7-alpine
                  rabbitmq:
                    image: rabbitmq:3-management
                """);

        InfrastructureRegistry registry = scanner.scan(tempDir);

        assertTrue(registry.getDatabases().containsKey("db:compose:postgres"));
        assertEquals("postgresql", registry.getDatabases().get("db:compose:postgres").type());
        assertEquals("5432:5432", registry.getDatabases().get("db:compose:postgres").properties().get("ports"));

        assertTrue(registry.getTopics().containsKey("topic:compose:kafka"));
        assertEquals("kafka", registry.getTopics().get("topic:compose:kafka").type());

        assertTrue(registry.getCaches().containsKey("cache:compose:redis"));
        assertEquals("redis", registry.getCaches().get("cache:compose:redis").type());

        assertTrue(registry.getQueues().containsKey("queue:compose:rabbitmq"));
        assertEquals("rabbitmq", registry.getQueues().get("queue:compose:rabbitmq").type());
    }

    @Test
    void parsesDockerComposeMysql() throws IOException {
        write("docker-compose.yml", """
                services:
                  db:
                    image: mysql:8.0
                """);

        InfrastructureRegistry registry = scanner.scan(tempDir);

        assertTrue(registry.getDatabases().containsKey("db:compose:db"));
        assertEquals("mysql", registry.getDatabases().get("db:compose:db").type());
    }

    @Test
    void parsesDockerComposeMongo() throws IOException {
        write("docker-compose.yml", """
                services:
                  mongo:
                    image: mongo:6
                """);

        InfrastructureRegistry registry = scanner.scan(tempDir);

        assertTrue(registry.getDatabases().containsKey("db:compose:mongo"));
        assertEquals("mongodb", registry.getDatabases().get("db:compose:mongo").type());
    }

    @Test
    void dockerComposeSkipsUnknownImages() throws IOException {
        write("docker-compose.yml", """
                services:
                  nginx:
                    image: nginx:latest
                  zookeeper:
                    image: zookeeper:3.8
                """);

        InfrastructureRegistry registry = scanner.scan(tempDir);
        assertTrue(registry.isEmpty());
    }

    // -------------------------------------------------------------------------
    // .env files
    // -------------------------------------------------------------------------

    @Test
    void parsesEnvDatabaseUrl() throws IOException {
        write(".env", """
                DATABASE_URL=postgresql://user:pass@db-host:5432/mydb
                REDIS_URL=redis://cache:6379
                KAFKA_BROKERS=broker1:9092,broker2:9092
                """);

        InfrastructureRegistry registry = scanner.scan(tempDir);

        assertTrue(registry.getDatabases().containsKey("db:env:database_url"));
        assertEquals("postgresql", registry.getDatabases().get("db:env:database_url").type());

        assertTrue(registry.getCaches().containsKey("cache:env:redis_url"));
        assertEquals("redis", registry.getCaches().get("cache:env:redis_url").type());

        assertTrue(registry.getTopics().containsKey("topic:env:kafka_brokers"));
        assertEquals("kafka", registry.getTopics().get("topic:env:kafka_brokers").type());
    }

    @Test
    void parsesEnvDbHost() throws IOException {
        write(".env", """
                DB_HOST=localhost
                DB_PORT=5432
                """);

        InfrastructureRegistry registry = scanner.scan(tempDir);
        assertTrue(registry.getDatabases().containsKey("db:env:db_host"));
        assertEquals("postgresql", registry.getDatabases().get("db:env:db_host").type());
    }

    @Test
    void parsesEnvQuotedValues() throws IOException {
        write(".env", """
                REDIS_URL="redis://cached:6379"
                DB_HOST='myhost'
                """);

        InfrastructureRegistry registry = scanner.scan(tempDir);
        assertTrue(registry.getCaches().containsKey("cache:env:redis_url"));
        assertEquals("redis://cached:6379", registry.getCaches().get("cache:env:redis_url").connectionUrl());
    }

    @Test
    void parsesEnvIgnoresComments() throws IOException {
        write(".env", """
                # This is a comment
                # REDIS_URL=redis://should-be-ignored
                REDIS_HOST=real-host
                """);

        InfrastructureRegistry registry = scanner.scan(tempDir);
        assertFalse(registry.getCaches().containsKey("cache:env:redis_url"),
                "Commented-out value should not be parsed");
        assertTrue(registry.getCaches().containsKey("cache:env:redis_host"));
    }

    // -------------------------------------------------------------------------
    // pom.xml
    // -------------------------------------------------------------------------

    @Test
    void parsesPomXmlDependencies() throws IOException {
        write("pom.xml", """
                <project>
                  <artifactId>my-app</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-data-jpa</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.postgresql</groupId>
                      <artifactId>postgresql</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.kafka</groupId>
                      <artifactId>spring-kafka</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-data-redis</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-amqp</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

        InfrastructureRegistry registry = scanner.scan(tempDir);

        assertTrue(registry.getDatabases().containsKey("db:pom:postgresql"));
        assertEquals("postgresql", registry.getDatabases().get("db:pom:postgresql").type());
        assertEquals("pom.xml", registry.getDatabases().get("db:pom:postgresql").properties().get("source"));

        assertTrue(registry.getTopics().containsKey("topic:pom:kafka"));
        assertTrue(registry.getCaches().containsKey("cache:pom:redis"));
        assertTrue(registry.getQueues().containsKey("queue:pom:rabbitmq"));
    }

    // -------------------------------------------------------------------------
    // Empty / no config
    // -------------------------------------------------------------------------

    @Test
    void returnsEmptyRegistryWhenNoConfigFiles() {
        InfrastructureRegistry registry = scanner.scan(tempDir);
        assertTrue(registry.isEmpty());
    }

    @Test
    void handlesEmptyYamlGracefully() throws IOException {
        write("application.yml", "");
        InfrastructureRegistry registry = scanner.scan(tempDir);
        assertTrue(registry.isEmpty());
    }

    @Test
    void handlesMalformedYamlGracefully() throws IOException {
        write("application.yml", ": invalid: yaml: {{{");
        assertDoesNotThrow(() -> scanner.scan(tempDir));
    }

    // -------------------------------------------------------------------------
    // Determinism
    // -------------------------------------------------------------------------

    @Test
    void deterministic_sameInputSameOutput() throws IOException {
        write("src/main/resources/application.yml", """
                spring:
                  application:
                    name: test-service
                  datasource:
                    url: jdbc:postgresql://db:5432/testdb
                  kafka:
                    bootstrap-servers: kafka:9092
                """);
        write("docker-compose.yml", """
                services:
                  redis:
                    image: redis:7
                """);

        InfrastructureRegistry r1 = scanner.scan(tempDir);
        InfrastructureRegistry r2 = scanner.scan(tempDir);

        assertEquals(r1.size(), r2.size());
        assertEquals(r1.getServiceName(), r2.getServiceName());
        assertEquals(r1.getAll().stream().map(InfraEndpoint::id).toList(),
                     r2.getAll().stream().map(InfraEndpoint::id).toList());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void write(String relativePath, String content) throws IOException {
        Path target = tempDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
