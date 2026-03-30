package io.github.randomcodespace.iq.analyzer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans well-known config files in a repository root and populates an
 * {@link InfrastructureRegistry} with discovered infrastructure endpoints.
 * <p>
 * Supported sources:
 * <ul>
 *   <li>Spring: {@code application.yml} / {@code application.properties}</li>
 *   <li>Docker Compose: {@code docker-compose.yml} / {@code compose.yml}</li>
 *   <li>Generic env files: {@code .env}, {@code .env.local}, etc.</li>
 *   <li>Maven build: {@code pom.xml} — dependency-based detection</li>
 * </ul>
 * <p>
 * Stateless Spring bean — safe for virtual threads and concurrent use.
 */
@Component
public class ConfigScanner {

    private static final Logger log = LoggerFactory.getLogger(ConfigScanner.class);

    private static final Pattern POM_ARTIFACT_ID =
            Pattern.compile("<artifactId>([^<]+)</artifactId>");

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Scan the given repository root and return a populated {@link InfrastructureRegistry}.
     * Never throws — errors are logged at DEBUG level and scanning continues.
     *
     * @param repoPath repository root directory
     * @return populated registry (may be empty if no config files found)
     */
    public InfrastructureRegistry scan(Path repoPath) {
        Path root = repoPath.toAbsolutePath().normalize();
        InfrastructureRegistry registry = new InfrastructureRegistry();

        scanSpringConfig(root, registry);
        scanDockerCompose(root, registry);
        scanEnvFiles(root, registry);
        scanBuildFiles(root, registry);

        log.debug("ConfigScanner found {} endpoints in {}", registry.size(), root);
        return registry;
    }

    // -------------------------------------------------------------------------
    // Spring application.yml / application.properties
    // -------------------------------------------------------------------------

    private void scanSpringConfig(Path root, InfrastructureRegistry registry) {
        List<Path> candidates = List.of(
                root.resolve("application.yml"),
                root.resolve("application.yaml"),
                root.resolve("application.properties"),
                root.resolve("src/main/resources/application.yml"),
                root.resolve("src/main/resources/application.yaml"),
                root.resolve("src/main/resources/application.properties")
        );

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                String name = candidate.getFileName().toString();
                if (name.endsWith(".properties")) {
                    parseSpringProperties(candidate, registry);
                } else {
                    parseSpringYaml(candidate, registry);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void parseSpringYaml(Path file, InfrastructureRegistry registry) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(content);
            if (!(loaded instanceof Map<?, ?> raw)) return;

            Map<String, String> flat = new TreeMap<>();
            flattenYaml("", (Map<String, Object>) raw, flat);
            processSpringFlatMap(flat, registry);
        } catch (Exception e) {
            log.debug("Failed to parse Spring YAML {}: {}", file, e.getMessage());
        }
    }

    private void parseSpringProperties(Path file, InfrastructureRegistry registry) {
        try {
            Properties props = new Properties();
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            Map<String, String> flat = new TreeMap<>();
            for (String key : props.stringPropertyNames()) {
                flat.put(key, props.getProperty(key));
            }
            processSpringFlatMap(flat, registry);
        } catch (Exception e) {
            log.debug("Failed to parse Spring properties {}: {}", file, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void flattenYaml(String prefix, Map<String, Object> data, Map<String, String> result) {
        for (var entry : data.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                flattenYaml(key, (Map<String, Object>) nested, result);
            } else if (value != null) {
                result.put(key, String.valueOf(value));
            }
        }
    }

    private void processSpringFlatMap(Map<String, String> flat, InfrastructureRegistry registry) {
        // Service name
        String appName = flat.get("spring.application.name");
        if (appName != null && !appName.isBlank()) {
            registry.setServiceName(appName.trim());
        }

        // Datasource (JPA/JDBC)
        String dsUrl = flat.get("spring.datasource.url");
        if (dsUrl != null && !dsUrl.isBlank()) {
            String dbType = detectDatabaseTypeFromUrl(dsUrl);
            registry.register(new InfraEndpoint(
                    "db:spring.datasource",
                    InfraEndpoint.Kind.DATABASE,
                    "spring.datasource",
                    dbType,
                    dsUrl,
                    Map.of("source", "spring.datasource.url")));
        }

        // Kafka bootstrap-servers
        String kafkaServers = coalesce(
                flat.get("spring.kafka.bootstrap-servers"),
                flat.get("spring.kafka.bootstrap_servers"));
        if (kafkaServers != null) {
            registry.register(new InfraEndpoint(
                    "topic:spring.kafka",
                    InfraEndpoint.Kind.TOPIC,
                    "spring.kafka",
                    "kafka",
                    kafkaServers,
                    Map.of("source", "spring.kafka.bootstrap-servers")));
        }

        // Redis (spring.data.redis or spring.redis)
        String redisHost = coalesce(
                flat.get("spring.data.redis.host"),
                flat.get("spring.redis.host"));
        if (redisHost != null) {
            String redisPort = coalesce(
                    flat.get("spring.data.redis.port"),
                    flat.get("spring.redis.port"),
                    "6379");
            registry.register(new InfraEndpoint(
                    "cache:spring.redis",
                    InfraEndpoint.Kind.CACHE,
                    "spring.redis",
                    "redis",
                    "redis://" + redisHost + ":" + redisPort,
                    Map.of("source", "spring.redis.host")));
        }

        // RabbitMQ
        String rabbitHost = flat.get("spring.rabbitmq.host");
        if (rabbitHost != null && !rabbitHost.isBlank()) {
            String rabbitPort = coalesce(flat.get("spring.rabbitmq.port"), "5672");
            registry.register(new InfraEndpoint(
                    "queue:spring.rabbitmq",
                    InfraEndpoint.Kind.QUEUE,
                    "spring.rabbitmq",
                    "rabbitmq",
                    "amqp://" + rabbitHost + ":" + rabbitPort,
                    Map.of("source", "spring.rabbitmq.host")));
        }
    }

    // -------------------------------------------------------------------------
    // Docker Compose
    // -------------------------------------------------------------------------

    private void scanDockerCompose(Path root, InfrastructureRegistry registry) {
        List<Path> candidates = List.of(
                root.resolve("docker-compose.yml"),
                root.resolve("docker-compose.yaml"),
                root.resolve("compose.yml"),
                root.resolve("compose.yaml")
        );

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                parseDockerCompose(candidate, registry);
                return; // Only process the first found
            }
        }
    }

    private void parseDockerCompose(Path file, InfrastructureRegistry registry) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(content);
            if (!(loaded instanceof Map<?, ?> data)) return;

            Object servicesObj = data.get("services");
            if (!(servicesObj instanceof Map<?, ?> services)) return;

            for (var entry : services.entrySet()) {
                String svcName = String.valueOf(entry.getKey());
                if (!(entry.getValue() instanceof Map<?, ?> svcConfig)) continue;

                Object imageObj = svcConfig.get("image");
                if (imageObj == null) continue;

                String image = String.valueOf(imageObj);
                String ports = extractFirstPort(svcConfig);
                detectDockerInfra(svcName, image, ports, registry);
            }
        } catch (Exception e) {
            log.debug("Failed to parse Docker Compose {}: {}", file, e.getMessage());
        }
    }

    private String extractFirstPort(Map<?, ?> svcConfig) {
        Object portsObj = svcConfig.get("ports");
        if (!(portsObj instanceof List<?> portsList) || portsList.isEmpty()) return null;
        return String.valueOf(portsList.get(0));
    }

    private void detectDockerInfra(String svcName, String image, String ports,
                                    InfrastructureRegistry registry) {
        String imageLower = image.toLowerCase();
        // Strip tag (e.g. postgres:15 → postgres)
        String imageBase = imageLower.contains(":") ? imageLower.substring(0, imageLower.indexOf(':')) : imageLower;
        // Strip registry prefix (e.g. docker.io/library/postgres → postgres)
        if (imageBase.contains("/")) {
            imageBase = imageBase.substring(imageBase.lastIndexOf('/') + 1);
        }

        Map<String, String> props = new TreeMap<>();
        props.put("image", image);
        props.put("source", "docker-compose");
        if (ports != null) props.put("ports", ports);

        if (imageBase.contains("postgres")) {
            registry.register(new InfraEndpoint("db:compose:" + svcName,
                    InfraEndpoint.Kind.DATABASE, svcName, "postgresql", null, props));
        } else if (imageBase.contains("mariadb")) {
            registry.register(new InfraEndpoint("db:compose:" + svcName,
                    InfraEndpoint.Kind.DATABASE, svcName, "mariadb", null, props));
        } else if (imageBase.contains("mysql")) {
            registry.register(new InfraEndpoint("db:compose:" + svcName,
                    InfraEndpoint.Kind.DATABASE, svcName, "mysql", null, props));
        } else if (imageBase.contains("mongo")) {
            registry.register(new InfraEndpoint("db:compose:" + svcName,
                    InfraEndpoint.Kind.DATABASE, svcName, "mongodb", null, props));
        } else if (imageBase.contains("redis")) {
            registry.register(new InfraEndpoint("cache:compose:" + svcName,
                    InfraEndpoint.Kind.CACHE, svcName, "redis", null, props));
        } else if (imageBase.contains("kafka") || imageBase.contains("cp-kafka")) {
            registry.register(new InfraEndpoint("topic:compose:" + svcName,
                    InfraEndpoint.Kind.TOPIC, svcName, "kafka", null, props));
        } else if (imageBase.contains("rabbitmq")) {
            registry.register(new InfraEndpoint("queue:compose:" + svcName,
                    InfraEndpoint.Kind.QUEUE, svcName, "rabbitmq", null, props));
        } else if (imageBase.contains("opensearch")) {
            registry.register(new InfraEndpoint("db:compose:" + svcName,
                    InfraEndpoint.Kind.DATABASE, svcName, "opensearch", null, props));
        } else if (imageBase.contains("elasticsearch")) {
            registry.register(new InfraEndpoint("db:compose:" + svcName,
                    InfraEndpoint.Kind.DATABASE, svcName, "elasticsearch", null, props));
        } else if (imageBase.contains("cassandra")) {
            registry.register(new InfraEndpoint("db:compose:" + svcName,
                    InfraEndpoint.Kind.DATABASE, svcName, "cassandra", null, props));
        }
        // zookeeper, nginx, etc. — intentionally skipped
    }

    // -------------------------------------------------------------------------
    // .env files
    // -------------------------------------------------------------------------

    private void scanEnvFiles(Path root, InfrastructureRegistry registry) {
        List<Path> candidates = List.of(
                root.resolve(".env"),
                root.resolve(".env.local"),
                root.resolve(".env.example"),
                root.resolve(".env.sample")
        );

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                parseEnvFile(candidate, registry);
            }
        }
    }

    private void parseEnvFile(Path file, InfrastructureRegistry registry) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            Map<String, String> envVars = new TreeMap<>();

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                // Strip surrounding quotes
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                envVars.put(key, value);
            }

            processEnvVars(envVars, registry);
        } catch (IOException e) {
            log.debug("Failed to parse .env file {}: {}", file, e.getMessage());
        }
    }

    private void processEnvVars(Map<String, String> env, InfrastructureRegistry registry) {
        // Database URL
        String dbUrl = coalesce(
                env.get("DATABASE_URL"),
                env.get("DB_URL"),
                env.get("JDBC_URL"),
                env.get("DB_CONNECTION"));
        if (dbUrl != null) {
            String id = "db:env:database_url";
            if (!registry.getDatabases().containsKey(id)) {
                registry.register(new InfraEndpoint(id, InfraEndpoint.Kind.DATABASE,
                        "database_url", detectDatabaseTypeFromUrl(dbUrl), dbUrl,
                        Map.of("source", ".env")));
            }
        } else {
            // DB_HOST fallback
            String dbHost = coalesce(env.get("DB_HOST"), env.get("DATABASE_HOST"));
            if (dbHost != null) {
                String dbPort = coalesce(env.get("DB_PORT"), env.get("DATABASE_PORT"), "5432");
                String id = "db:env:db_host";
                if (!registry.getDatabases().containsKey(id)) {
                    registry.register(new InfraEndpoint(id, InfraEndpoint.Kind.DATABASE,
                            "database", "postgresql", dbHost + ":" + dbPort,
                            Map.of("source", ".env")));
                }
            }
        }

        // Redis
        String redisUrl = env.get("REDIS_URL");
        if (redisUrl != null) {
            String id = "cache:env:redis_url";
            if (!registry.getCaches().containsKey(id)) {
                registry.register(new InfraEndpoint(id, InfraEndpoint.Kind.CACHE,
                        "redis", "redis", redisUrl, Map.of("source", ".env")));
            }
        } else {
            String redisHost = env.get("REDIS_HOST");
            if (redisHost != null) {
                String redisPort = coalesce(env.get("REDIS_PORT"), "6379");
                String id = "cache:env:redis_host";
                if (!registry.getCaches().containsKey(id)) {
                    registry.register(new InfraEndpoint(id, InfraEndpoint.Kind.CACHE,
                            "redis", "redis", "redis://" + redisHost + ":" + redisPort,
                            Map.of("source", ".env")));
                }
            }
        }

        // Kafka
        String kafkaBrokers = coalesce(
                env.get("KAFKA_BROKERS"),
                env.get("KAFKA_BOOTSTRAP_SERVERS"),
                env.get("BOOTSTRAP_SERVERS"));
        if (kafkaBrokers != null) {
            String id = "topic:env:kafka_brokers";
            if (!registry.getTopics().containsKey(id)) {
                registry.register(new InfraEndpoint(id, InfraEndpoint.Kind.TOPIC,
                        "kafka", "kafka", kafkaBrokers, Map.of("source", ".env")));
            }
        }
    }

    // -------------------------------------------------------------------------
    // pom.xml — dependency-based detection
    // -------------------------------------------------------------------------

    private void scanBuildFiles(Path root, InfrastructureRegistry registry) {
        Path pomXml = root.resolve("pom.xml");
        if (Files.isRegularFile(pomXml)) {
            parsePomXml(pomXml, registry);
        }
    }

    private void parsePomXml(Path file, InfrastructureRegistry registry) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Set<String> artifactIds = extractPomArtifactIds(content);

            boolean hasJpa      = artifactIds.stream().anyMatch(a -> a.contains("jpa") || a.contains("hibernate") || a.contains("jdbc"));
            boolean hasPostgres = artifactIds.contains("postgresql");
            boolean hasMysql    = artifactIds.stream().anyMatch(a -> a.contains("mysql-connector"));
            boolean hasH2       = artifactIds.contains("h2");
            boolean hasMongo    = artifactIds.stream().anyMatch(a -> a.contains("mongo"));
            boolean hasKafka    = artifactIds.stream().anyMatch(a -> a.contains("kafka"));
            boolean hasRedis    = artifactIds.stream().anyMatch(a -> a.contains("redis") || a.equals("lettuce-core") || a.equals("jedis"));
            boolean hasRabbit   = artifactIds.stream().anyMatch(a -> a.contains("amqp") || a.contains("rabbitmq"));
            boolean hasElastic  = artifactIds.stream().anyMatch(a -> a.contains("elasticsearch"));

            if (hasJpa || hasPostgres || hasMysql || hasH2 || hasMongo) {
                String dbType = hasPostgres ? "postgresql"
                        : hasMysql ? "mysql"
                        : hasMongo ? "mongodb"
                        : hasH2    ? "h2"
                        : "sql";
                String id = "db:pom:" + dbType;
                if (!registry.getDatabases().containsKey(id)) {
                    registry.register(new InfraEndpoint(id, InfraEndpoint.Kind.DATABASE,
                            dbType, dbType, null,
                            Map.of("source", "pom.xml", "detection", "dependency")));
                }
            }

            if (hasKafka) {
                String id = "topic:pom:kafka";
                if (!registry.getTopics().containsKey(id)) {
                    registry.register(new InfraEndpoint(id, InfraEndpoint.Kind.TOPIC,
                            "kafka", "kafka", null,
                            Map.of("source", "pom.xml", "detection", "dependency")));
                }
            }

            if (hasRedis) {
                String id = "cache:pom:redis";
                if (!registry.getCaches().containsKey(id)) {
                    registry.register(new InfraEndpoint(id, InfraEndpoint.Kind.CACHE,
                            "redis", "redis", null,
                            Map.of("source", "pom.xml", "detection", "dependency")));
                }
            }

            if (hasRabbit) {
                String id = "queue:pom:rabbitmq";
                if (!registry.getQueues().containsKey(id)) {
                    registry.register(new InfraEndpoint(id, InfraEndpoint.Kind.QUEUE,
                            "rabbitmq", "rabbitmq", null,
                            Map.of("source", "pom.xml", "detection", "dependency")));
                }
            }

            if (hasElastic) {
                String id = "db:pom:elasticsearch";
                if (!registry.getDatabases().containsKey(id)) {
                    registry.register(new InfraEndpoint(id, InfraEndpoint.Kind.DATABASE,
                            "elasticsearch", "elasticsearch", null,
                            Map.of("source", "pom.xml", "detection", "dependency")));
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse pom.xml {}: {}", file, e.getMessage());
        }
    }

    private Set<String> extractPomArtifactIds(String content) {
        Set<String> artifactIds = new TreeSet<>();
        Matcher m = POM_ARTIFACT_ID.matcher(content);
        while (m.find()) {
            artifactIds.add(m.group(1).trim().toLowerCase());
        }
        return artifactIds;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String detectDatabaseTypeFromUrl(String url) {
        if (url == null) return "sql";
        String lower = url.toLowerCase();
        if (lower.contains("postgresql") || lower.contains("postgres")) return "postgresql";
        if (lower.contains("mysql"))      return "mysql";
        if (lower.contains("mariadb"))    return "mariadb";
        if (lower.contains("oracle"))     return "oracle";
        if (lower.contains("sqlserver") || lower.contains("mssql")) return "sqlserver";
        if (lower.contains("h2"))         return "h2";
        if (lower.contains("sqlite"))     return "sqlite";
        if (lower.contains("mongodb") || lower.contains("mongo")) return "mongodb";
        return "sql";
    }

    private static String coalesce(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
