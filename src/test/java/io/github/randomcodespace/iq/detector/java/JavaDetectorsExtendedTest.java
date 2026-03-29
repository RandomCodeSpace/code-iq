package io.github.randomcodespace.iq.detector.java;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended tests for Java detectors to cover more branches and code paths.
 */
class JavaDetectorsExtendedTest {

    // ==================== ClassHierarchyDetector — regex fallback ====================
    @Nested
    class ClassHierarchyExtended {
        private final ClassHierarchyDetector d = new ClassHierarchyDetector();

        @Test
        void detectsAbstractClass() {
            String code = """
                    public abstract class BaseService implements Serializable, Comparable {
                        public void doWork() {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ABSTRACT_CLASS));
        }

        @Test
        void detectsFinalClass() {
            String code = """
                    public final class ImmutableRecord extends AbstractRecord {
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertTrue((Boolean) r.nodes().get(0).getProperties().get("is_final"));
        }

        @Test
        void detectsInterfaceExtending() {
            String code = """
                    public interface Flyable extends Moveable, Trackable {
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals(NodeKind.INTERFACE, r.nodes().get(0).getKind());
            assertFalse(r.edges().isEmpty());
        }

        @Test
        void detectsEnumImplementingInterface() {
            String code = """
                    public enum Color implements Coded, Displayable {
                        RED, GREEN, BLUE;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals(NodeKind.ENUM, r.nodes().get(0).getKind());
            assertTrue(r.edges().size() >= 2, "Should have IMPLEMENTS edges for both interfaces");
        }

        @Test
        void detectsAnnotationType() {
            String code = """
                    public @interface MyCustomAnnotation {
                        String value();
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals(NodeKind.ANNOTATION_TYPE, r.nodes().get(0).getKind());
        }

        @Test
        void detectsProtectedClass() {
            String code = """
                    protected class InnerHelper extends BaseHelper {
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("protected", r.nodes().get(0).getProperties().get("visibility"));
        }

        @Test
        void detectsPrivateClass() {
            String code = """
                    private class PrivateInner {
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("private", r.nodes().get(0).getProperties().get("visibility"));
        }

        @Test
        void detectsPackagePrivateClass() {
            String code = """
                    class PackageLocalClass {
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("package-private", r.nodes().get(0).getProperties().get("visibility"));
        }

        @Test
        void detectsMultipleTypes() {
            String code = """
                    public class Foo extends Bar implements Baz {}
                    public interface Qux extends Comparable {}
                    public enum Status implements Coded {}
                    public @interface Config {}
                    """;
            var r = d.detect(ctx("java", code));
            assertEquals(4, r.nodes().size());
        }

        @Test
        void astDetectionWithPackage() {
            // valid Java that JavaParser can parse via AST
            String code = """
                    package com.example;
                    public class Animal implements java.io.Serializable {
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("Animal", r.nodes().get(0).getLabel());
        }

        @Test
        void astDetectsAbstractAndFinal() {
            String code = """
                    package com.example;
                    public abstract class AbstractService {
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals(NodeKind.ABSTRACT_CLASS, r.nodes().get(0).getKind());
        }

        @Test
        void astDetectsInterface() {
            String code = """
                    package com.example;
                    public interface Repository extends BaseRepo {
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals(NodeKind.INTERFACE, r.nodes().get(0).getKind());
            assertFalse(r.edges().isEmpty());
        }

        @Test
        void astDetectsEnum() {
            String code = """
                    package com.example;
                    public enum Status implements Coded {
                        ACTIVE, INACTIVE
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals(NodeKind.ENUM, r.nodes().get(0).getKind());
        }

        @Test
        void astDetectsAnnotationType() {
            String code = """
                    package com.example;
                    public @interface MyAnnotation {
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals(NodeKind.ANNOTATION_TYPE, r.nodes().get(0).getKind());
        }

        @Test
        void nullContentReturnsEmpty() {
            var r = d.detect(new DetectorContext("Test.java", "java", null));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void emptyContentReturnsEmpty() {
            var r = d.detect(ctx("java", ""));
            assertTrue(r.nodes().isEmpty());
        }
    }

    // ==================== SpringRestDetector — more branches ====================
    @Nested
    class SpringRestExtended {
        private final SpringRestDetector d = new SpringRestDetector();

        @Test
        void detectsPutAndDeleteMappings() {
            String code = """
                    @RestController
                    @RequestMapping("/api/items")
                    public class ItemController {
                        @PutMapping("/{id}")
                        public Item update(@PathVariable Long id, @RequestBody Item item) { return null; }
                        @DeleteMapping("/{id}")
                        public void delete(@PathVariable Long id) {}
                        @PatchMapping("/{id}")
                        public Item patch(@PathVariable Long id, @RequestBody Map<String,Object> fields) { return null; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.nodes().stream().anyMatch(n -> n.getLabel().contains("PUT")));
            assertTrue(r.nodes().stream().anyMatch(n -> n.getLabel().contains("DELETE")));
        }

        @Test
        void detectsRequestMappingWithMethod() {
            String code = """
                    @Controller
                    @RequestMapping("/web")
                    public class WebController {
                        @RequestMapping(value = "/page", method = RequestMethod.GET)
                        public String page() { return "page"; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsResponseBodyAnnotation() {
            String code = """
                    @Controller
                    public class ApiController {
                        @GetMapping("/data")
                        @ResponseBody
                        public String getData() { return "data"; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }
    }

    // ==================== SpringSecurityDetector — more branches ====================
    @Nested
    class SpringSecurityExtended {
        private final SpringSecurityDetector d = new SpringSecurityDetector();

        @Test
        void detectsPreAuthorize() {
            String code = """
                    @PreAuthorize("hasAuthority('WRITE')")
                    public void write() {}
                    @PostAuthorize("returnObject.owner == authentication.name")
                    public Document getDoc(Long id) { return null; }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsRolesAllowed() {
            String code = """
                    @RolesAllowed({"ADMIN", "MANAGER"})
                    public void manage() {}
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsSecurityFilterChain() {
            String code = """
                    @Bean
                    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
                        http.csrf().disable()
                            .authorizeHttpRequests()
                            .requestMatchers("/api/**").authenticated()
                            .requestMatchers("/public/**").permitAll();
                        return http.build();
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsWithPermitAll() {
            String code = """
                    package com.example;
                    @Configuration
                    @EnableWebSecurity
                    public class SecurityConfig {
                        @Bean
                        public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
                            http.authorizeHttpRequests(auth -> auth
                                .requestMatchers("/public/**").permitAll()
                                .anyRequest().authenticated()
                            );
                            return http.build();
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }
    }

    // ==================== JpaEntityDetector — more branches ====================
    @Nested
    class JpaEntityExtended {
        private final JpaEntityDetector d = new JpaEntityDetector();

        @Test
        void detectsManyToManyRelationship() {
            String code = """
                    @Entity
                    @Table(name = "students")
                    public class Student {
                        @ManyToMany
                        @JoinTable(name = "student_courses")
                        private Set<Course> courses;
                        @ManyToOne
                        @JoinColumn(name = "school_id")
                        private School school;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsIdAnnotations() {
            String code = """
                    @Entity
                    public class Product {
                        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
                        private Long id;
                        @Column(nullable = false, unique = true)
                        private String sku;
                        @Embedded
                        private Address address;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsInheritanceAnnotations() {
            String code = """
                    @Entity
                    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
                    @DiscriminatorColumn(name = "type")
                    public abstract class Vehicle {
                        @Id private Long id;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }
    }

    // ==================== ModuleDepsDetector — Gradle ====================
    @Nested
    class ModuleDepsExtended {
        private final ModuleDepsDetector d = new ModuleDepsDetector();

        @Test
        void detectsGradleDependencies() {
            String code = """
                    plugins {
                        id 'java'
                        id 'org.springframework.boot' version '3.2.0'
                    }
                    dependencies {
                        implementation 'org.springframework.boot:spring-boot-starter-web'
                        testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'
                        runtimeOnly 'org.postgresql:postgresql'
                    }
                    """;
            var r = d.detect(new DetectorContext("build.gradle", "groovy", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsGradleKtsDependencies() {
            String code = """
                    plugins {
                        kotlin("jvm") version "1.9.20"
                    }
                    dependencies {
                        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                    }
                    """;
            var r = d.detect(new DetectorContext("build.gradle.kts", "kotlin", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsMavenWithMultipleModules() {
            String pom = """
                    <project>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <packaging>pom</packaging>
                        <modules>
                            <module>core</module>
                            <module>web</module>
                            <module>api</module>
                        </modules>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-starter-web</artifactId>
                            </dependency>
                            <dependency>
                                <groupId>org.postgresql</groupId>
                                <artifactId>postgresql</artifactId>
                                <scope>runtime</scope>
                            </dependency>
                        </dependencies>
                    </project>
                    """;
            var r = d.detect(new DetectorContext("pom.xml", "xml", pom));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.edges().size() >= 2);
        }
    }

    // ==================== AzureFunctionsDetector — more branches ====================
    @Nested
    class AzureFunctionsExtended {
        private final AzureFunctionsDetector d = new AzureFunctionsDetector();

        @Test
        void detectsMultipleTriggerTypes() {
            String code = """
                    public class Functions {
                        @FunctionName("TimerFunc")
                        public void timerRun(@TimerTrigger(name = "timer", schedule = "0 */5 * * * *") String timerInfo) {}
                        @FunctionName("QueueFunc")
                        public void queueRun(@QueueTrigger(name = "msg", queueName = "myqueue") String message) {}
                        @FunctionName("BlobFunc")
                        public void blobRun(@BlobTrigger(name = "blob", path = "container/{name}") String content) {}
                        @FunctionName("CosmosFunc")
                        public void cosmosRun(@CosmosDBTrigger(name = "docs", databaseName = "db", collectionName = "col") String docs) {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().size() >= 4);
        }

        @Test
        void detectsEventGridTrigger() {
            String code = """
                    public class EventFunctions {
                        @FunctionName("EventGridFunc")
                        public void run(@EventGridTrigger(name = "event") String event) {}
                        @FunctionName("EventHubFunc")
                        public void hubRun(@EventHubTrigger(name = "events", eventHubName = "hub") String events) {}
                        @FunctionName("ServiceBusFunc")
                        public void busRun(@ServiceBusQueueTrigger(name = "msg", queueName = "q") String msg) {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().size() >= 3);
        }
    }

    // ==================== MicronautDetector — more branches ====================
    @Nested
    class MicronautExtended {
        private final MicronautDetector d = new MicronautDetector();

        @Test
        void detectsMultipleEndpointTypes() {
            String code = """
                    @Controller("/api")
                    public class ApiController {
                        @Get("/items")
                        public List<Item> list() { return null; }
                        @Post("/items")
                        public Item create(@Body Item item) { return null; }
                        @Put("/items/{id}")
                        public Item update(Long id, @Body Item item) { return null; }
                        @Delete("/items/{id}")
                        public void delete(Long id) {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().size() >= 4);
        }

        @Test
        void detectsMicronautBeans() {
            String code = """
                    @Factory
                    public class AppFactory {
                        @Bean
                        public DataSource dataSource() { return null; }
                    }
                    @Singleton
                    public class CacheService {}
                    @Prototype
                    public class RequestScope {}
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsScheduledAndEvents() {
            String code = """
                    @Singleton
                    public class TaskService {
                        @Scheduled(fixedDelay = "5s")
                        public void poll() {}
                        @EventListener
                        public void onStartup(ServerStartupEvent event) {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }
    }

    // ==================== PublicApiDetector — more branches ====================
    @Nested
    class PublicApiExtended {
        private final PublicApiDetector d = new PublicApiDetector();

        @Test
        void detectsOverloadedMethods() {
            String code = """
                    public class UserService {
                        public User findUser(String name) { return null; }
                        public User findUser(Long id) { return null; }
                        protected void process(Order order) {}
                        public void execute(String command, Map<String, Object> params) {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().size() >= 3);
        }

        @Test
        void excludesGettersAndSetters() {
            String code = """
                    public class Entity {
                        public String getName() { return name; }
                        public void setName(String name) { this.name = name; }
                        public boolean isActive() { return active; }
                        public int hashCode() { return 0; }
                        public boolean equals(Object o) { return false; }
                        public String toString() { return ""; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().isEmpty(), "Getters/setters/Object methods should be excluded");
        }
    }

    // ==================== WebSocketDetector — more branches ====================
    @Nested
    class WebSocketExtended {
        private final WebSocketDetector d = new WebSocketDetector();

        @Test
        void detectsStompAnnotations() {
            String code = """
                    @Controller
                    public class WsController {
                        @MessageMapping("/chat")
                        @SendTo("/topic/messages")
                        public ChatMessage send(ChatMessage msg) { return msg; }
                        @SubscribeMapping("/init")
                        public List<ChatMessage> init() { return List.of(); }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsWebSocketConfigurer() {
            String code = """
                    @Configuration
                    @EnableWebSocketMessageBroker
                    public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
                        @Override
                        public void configureMessageBroker(MessageBrokerRegistry config) {
                            config.enableSimpleBroker("/topic");
                            config.setApplicationDestinationPrefixes("/app");
                        }
                        @Override
                        public void registerStompEndpoints(StompEndpointRegistry registry) {
                            registry.addEndpoint("/ws").withSockJS();
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsJakartaWebSocket() {
            String code = """
                    @ServerEndpoint("/ws/notifications")
                    public class NotificationEndpoint {
                        @OnOpen
                        public void onOpen(Session session) {}
                        @OnMessage
                        public void onMessage(String message, Session session) {}
                        @OnClose
                        public void onClose(Session session) {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }
    }

    // ==================== RmiDetector — more branches ====================
    @Nested
    class RmiExtended {
        private final RmiDetector d = new RmiDetector();

        @Test
        void detectsRmiImplWithInterface() {
            // Need both a Remote interface AND UnicastRemoteObject implementation to get nodes + edges
            String code = """
                    public interface BankService extends java.rmi.Remote {
                        void deposit(double amount) throws RemoteException;
                    }
                    public class BankServiceImpl extends UnicastRemoteObject implements BankService {
                        public void deposit(double amount) throws RemoteException {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertFalse(r.edges().isEmpty());
        }

        @Test
        void detectsNamingBindAndLookup() {
            String code = """
                    public class Server {
                        public void start() {
                            Naming.rebind("BankService", new BankServiceImpl());
                        }
                    }
                    public class Client {
                        public void connect() {
                            BankService svc = (BankService) Naming.lookup("BankService");
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            // Naming.rebind and Naming.lookup produce edges (not nodes)
            assertFalse(r.edges().isEmpty());
        }

        @Test
        void detectsRegistryBind() {
            String code = """
                    public class RmiServer {
                        public void setup() {
                            Registry.bind("Calculator", new CalculatorImpl());
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.edges().isEmpty());
        }
    }

    // ==================== ConfigDefDetector — more branches ====================
    @Nested
    class ConfigDefExtended {
        private final ConfigDefDetector d = new ConfigDefDetector();

        @Test
        void detectsMultipleConfigDefs() {
            String code = """
                    public class ConnectorConfig {
                        static ConfigDef CONFIG = new ConfigDef()
                            .define("topic.name", Type.STRING, "default")
                            .define("batch.size", Type.INT, 100)
                            .define("enable.compression", Type.BOOLEAN, true)
                            .define("poll.interval.ms", Type.LONG, 1000L);
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().size() >= 4);
        }

        @Test
        void detectsConfigWithImportance() {
            String code = """
                    public class SinkConfig extends AbstractConfig {
                        static ConfigDef CONFIG = new ConfigDef()
                            .define("connection.url", Type.STRING, ConfigDef.NO_DEFAULT_VALUE, Importance.HIGH, "JDBC URL")
                            .define("max.retries", Type.INT, 3, Importance.MEDIUM, "Max retries");
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().size() >= 2);
        }
    }

    // ==================== AzureMessagingDetector — more branches ====================
    @Nested
    class AzureMessagingExtended {
        private final AzureMessagingDetector d = new AzureMessagingDetector();

        @Test
        void detectsEventHub() {
            String code = """
                    public class EventHubService {
                        EventHubProducerClient producer;
                        EventHubConsumerClient consumer;
                        public void init() {
                            new EventHubClientBuilder().connectionString("conn").buildProducerClient();
                            new EventProcessorClientBuilder().consumerGroup("$Default").buildEventProcessorClient();
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsServiceBusClient() {
            String code = """
                    public class BusService {
                        ServiceBusClient client;
                        public void setup() {
                            ServiceBusClientBuilder builder = new ServiceBusClientBuilder();
                            builder.queueName("orders").buildClient();
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsServiceBusTopic() {
            String code = """
                    public class TopicService {
                        public void setup() {
                            new ServiceBusClientBuilder().topicName("events").buildClient();
                            ServiceBusReceiverClient receiver = null;
                            ServiceBusProcessorClient processor = null;
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }
    }

    // ==================== IbmMqDetector — more branches ====================
    @Nested
    class IbmMqExtended {
        private final IbmMqDetector d = new IbmMqDetector();

        @Test
        void detectsTopicAccess() {
            String code = """
                    public class TopicService {
                        public void subscribe() {
                            MQQueueManager qm = new MQQueueManager("QM1");
                            MQTopic topic = qm.accessTopic("EVENTS.TOPIC", null, CMQC.MQTOPIC_OPEN_AS_SUBSCRIPTION, CMQC.MQSO_CREATE);
                            topic.get(msg);
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsJmsWithMQ() {
            String code = """
                    public class JmsMqService {
                        JmsConnectionFactory factory;
                        public void setup() {
                            MQQueueManager qm = new MQQueueManager("QM2");
                            qm.accessQueue("REPLY.QUEUE", openOptions);
                            queue.put(msg);
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }
    }

    // ==================== QuarkusDetector — more branches ====================
    @Nested
    class QuarkusExtended {
        private final QuarkusDetector d = new QuarkusDetector();

        @Test
        void detectsReactiveEndpoints() {
            String code = """
                    @Path("/api/items")
                    @ApplicationScoped
                    public class ItemResource {
                        @GET
                        public Uni<List<Item>> list() { return null; }
                        @POST
                        @Transactional
                        public Uni<Item> create(Item item) { return null; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsQuarkusEvents() {
            String code = """
                    @ApplicationScoped
                    public class EventService {
                        @Incoming("orders")
                        public void consume(String msg) {}
                        @Outgoing("notifications")
                        public String produce() { return "hello"; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }
    }

    // ==================== GraphqlResolverDetector — more branches ====================
    @Nested
    class GraphqlExtended {
        private final GraphqlResolverDetector d = new GraphqlResolverDetector();

        @Test
        void detectsSchemaMapping() {
            String code = """
                    @Controller
                    public class BookResolver {
                        @SchemaMapping(typeName = "Query", field = "books")
                        public List<Book> books() { return null; }
                        @SchemaMapping(typeName = "Mutation", field = "addBook")
                        public Book addBook(@Argument BookInput input) { return null; }
                        @SubscriptionMapping
                        public Flux<Book> bookAdded() { return null; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsDgsAnnotations() {
            String code = """
                    @DgsComponent
                    public class ShowsDataFetcher {
                        @DgsQuery
                        public List<Show> shows() { return null; }
                        @DgsMutation
                        public Show addShow(String title) { return null; }
                        @DgsData(parentType = "Show", field = "reviews")
                        public List<Review> reviews(DgsDataFetchingEnvironment dfe) { return null; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }
    }

    // ==================== TibcoEmsDetector — more branches ====================
    @Nested
    class TibcoEmsExtended {
        private final TibcoEmsDetector d = new TibcoEmsDetector();

        @Test
        void detectsTopicPublishing() {
            String code = """
                    public class EmsPublisher {
                        TibjmsConnectionFactory factory = new TibjmsConnectionFactory();
                        public void publish() {
                            factory.setServerUrl("tcp://ems:7222");
                            session.createTopic("EVENTS.TOPIC");
                            TopicPublisher publisher = session.createPublisher(topic);
                            publisher.publish(msg);
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsDurableSubscriber() {
            String code = """
                    public class EmsSubscriber {
                        public void subscribe() {
                            TibjmsConnectionFactory factory = new TibjmsConnectionFactory("tcp://ems:7222");
                            connection = factory.createConnection();
                            session.createDurableSubscriber(topic, "sub1");
                            session.createConsumer(destination);
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }
    }

    private static DetectorContext ctx(String language, String content) {
        return DetectorTestUtils.contextFor(language, content);
    }
}
