package io.github.randomcodespace.iq.detector.java;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for all 28 Java detectors ported from Python.
 * Each detector has: positive match, negative match, and determinism tests.
 */
class JavaDetectorsTest {

    // ==================== SpringRestDetector ====================
    @Nested
    class SpringRestTests {
        private static final String SAMPLE = """
                @RestController
                @RequestMapping("/api/users")
                public class UserController {
                    @GetMapping("/{id}")
                    public User getUser(@PathVariable Long id) { return null; }
                    @PostMapping
                    public User createUser(@RequestBody User u) { return null; }
                }
                """;

        @Test
        void detectsSpringEndpoints() {
            var d = new SpringRestDetector();
            var r = d.detect(ctx("java", SAMPLE));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.nodes().stream().anyMatch(n -> n.getLabel().contains("GET")));
            assertTrue(r.nodes().stream().anyMatch(n -> n.getLabel().contains("POST")));
            assertTrue(r.nodes().stream()
                    .filter(n -> n.getKind() == io.github.randomcodespace.iq.model.NodeKind.ENDPOINT)
                    .allMatch(n -> "spring_boot".equals(n.getProperties().get("framework"))),
                    "All endpoint nodes should have framework=spring_boot");
        }

        @Test
        void ignoresPlainCode() {
            var r = new SpringRestDetector().detect(ctx("java", "public class Foo {}"));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new SpringRestDetector(), ctx("java", SAMPLE));
        }

        @Test
        void requestMappingWithoutMethodDefaultsToAll() {
            String source = """
                    @RestController
                    @RequestMapping("/api")
                    public class MyController {
                        @RequestMapping("/items")
                        public String listItems() { return "items"; }
                        @RequestMapping(value = "/search", method = RequestMethod.POST)
                        public String search() { return "results"; }
                    }
                    """;
            var r = new SpringRestDetector().detect(ctx("java", source));
            var endpoints = r.nodes().stream()
                    .filter(n -> n.getKind() == io.github.randomcodespace.iq.model.NodeKind.ENDPOINT)
                    .toList();
            assertEquals(2, endpoints.size(), "Should detect 2 endpoints");
            // @RequestMapping without method= should default to ALL
            assertTrue(endpoints.stream().anyMatch(n -> "ALL".equals(n.getProperties().get("http_method"))),
                    "RequestMapping without method should default to ALL");
            // @RequestMapping with explicit method should use that method
            assertTrue(endpoints.stream().anyMatch(n -> "POST".equals(n.getProperties().get("http_method"))),
                    "RequestMapping with method=POST should be POST");
            // No endpoint should have UNKNOWN or null http_method
            assertTrue(endpoints.stream().allMatch(n -> n.getProperties().get("http_method") != null),
                    "All endpoints must have http_method set");
        }

        @Test
        void skipsModelAttributeMethods() {
            String source = """
                    @Controller
                    @RequestMapping("/owners")
                    public class OwnerController {
                        @ModelAttribute("owner")
                        public Owner findOwner(@PathVariable int ownerId) { return null; }
                        @GetMapping("/new")
                        public String initCreationForm() { return "createForm"; }
                        @PostMapping("/new")
                        public String processCreationForm(@Valid Owner owner) { return "redirect:/"; }
                    }
                    """;
            var r = new SpringRestDetector().detect(ctx("java", source));
            var endpoints = r.nodes().stream()
                    .filter(n -> n.getKind() == io.github.randomcodespace.iq.model.NodeKind.ENDPOINT)
                    .toList();
            assertEquals(2, endpoints.size(), "Should detect 2 endpoints (GET and POST), not @ModelAttribute");
            assertTrue(endpoints.stream().noneMatch(n -> n.getLabel().contains("findOwner")),
                    "@ModelAttribute method should not be detected as endpoint");
        }

        @Test
        void skipsInitBinderMethods() {
            String source = """
                    @Controller
                    @RequestMapping("/pets")
                    public class PetController {
                        @InitBinder
                        public void setAllowedFields(WebDataBinder binder) {}
                        @GetMapping("/new")
                        public String initCreationForm() { return "createForm"; }
                    }
                    """;
            var r = new SpringRestDetector().detect(ctx("java", source));
            var endpoints = r.nodes().stream()
                    .filter(n -> n.getKind() == io.github.randomcodespace.iq.model.NodeKind.ENDPOINT)
                    .toList();
            assertEquals(1, endpoints.size(), "Should detect only 1 endpoint, not @InitBinder");
            assertEquals("GET", endpoints.get(0).getProperties().get("http_method"));
        }

        @Test
        void skipsExceptionHandlerMethods() {
            String source = """
                    @RestController
                    @RequestMapping("/api")
                    public class ApiController {
                        @ExceptionHandler(Exception.class)
                        public ResponseEntity<String> handleError(Exception ex) { return null; }
                        @GetMapping("/data")
                        public String getData() { return "data"; }
                    }
                    """;
            var r = new SpringRestDetector().detect(ctx("java", source));
            var endpoints = r.nodes().stream()
                    .filter(n -> n.getKind() == io.github.randomcodespace.iq.model.NodeKind.ENDPOINT)
                    .toList();
            assertEquals(1, endpoints.size(), "Should detect only 1 endpoint, not @ExceptionHandler");
        }

        @Test
        void requestMappingWithoutMethodIsDeterministic() {
            String source = """
                    @RestController
                    @RequestMapping("/api")
                    public class MyController {
                        @RequestMapping("/items")
                        public String listItems() { return "items"; }
                    }
                    """;
            DetectorTestUtils.assertDeterministic(new SpringRestDetector(), ctx("java", source));
        }
    }

    // ==================== SpringSecurityDetector ====================
    @Nested
    class SpringSecurityTests {
        private static final String SAMPLE = """
                @EnableWebSecurity
                public class SecurityConfig {
                    @Secured("ROLE_ADMIN")
                    public void adminOnly() {}
                    @PreAuthorize("hasRole('USER')")
                    public void userOnly() {}
                    public SecurityFilterChain filterChain(HttpSecurity http) { return null; }
                }
                """;

        @Test
        void detectsSecurityAnnotations() {
            var r = new SpringSecurityDetector().detect(ctx("java", SAMPLE));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.nodes().stream().anyMatch(n -> n.getLabel().equals("@Secured")));
            assertTrue(r.nodes().stream().anyMatch(n -> n.getLabel().equals("@EnableWebSecurity")));
            assertTrue(r.nodes().stream()
                    .allMatch(n -> "spring_boot".equals(n.getProperties().get("framework"))),
                    "All guard nodes should have framework=spring_boot");
        }

        @Test
        void ignoresPlainCode() {
            assertTrue(new SpringSecurityDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new SpringSecurityDetector(), ctx("java", SAMPLE));
        }
    }

    // ==================== SpringEventsDetector ====================
    @Nested
    class SpringEventsTests {
        private static final String SAMPLE = """
                public class EventService {
                    @EventListener
                    public void handle(OrderEvent event) {}
                    public void publish() {
                        applicationEventPublisher.publishEvent(new OrderEvent());
                    }
                }
                """;

        @Test
        void detectsEvents() {
            var r = new SpringEventsDetector().detect(ctx("java", SAMPLE));
            assertFalse(r.nodes().isEmpty());
            assertFalse(r.edges().isEmpty());
            assertTrue(r.nodes().stream()
                    .allMatch(n -> "spring_boot".equals(n.getProperties().get("framework"))),
                    "All event nodes should have framework=spring_boot");
        }

        @Test
        void ignoresPlainCode() {
            assertTrue(new SpringEventsDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new SpringEventsDetector(), ctx("java", SAMPLE));
        }
    }

    // ==================== JpaEntityDetector ====================
    @Nested
    class JpaEntityTests {
        private static final String SAMPLE = """
                @Entity
                @Table(name = "users")
                public class User {
                    @Column(name = "email")
                    private String email;
                    @OneToMany
                    private List<Order> orders;
                }
                """;

        @Test
        void detectsEntity() {
            var r = new JpaEntityDetector().detect(ctx("java", SAMPLE));
            assertEquals(2, r.nodes().size()); // entity + database:unknown
            assertTrue(r.nodes().stream().anyMatch(n -> n.getLabel().contains("users")));
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == io.github.randomcodespace.iq.model.EdgeKind.CONNECTS_TO));
            assertTrue(r.nodes().stream()
                    .filter(n -> n.getKind() == io.github.randomcodespace.iq.model.NodeKind.ENTITY)
                    .allMatch(n -> "spring_boot".equals(n.getProperties().get("framework"))),
                    "Entity nodes should have framework=spring_boot");
        }

        @Test
        void ignoresNonEntity() {
            assertTrue(new JpaEntityDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new JpaEntityDetector(), ctx("java", SAMPLE));
        }
    }

    // ==================== RepositoryDetector ====================
    @Nested
    class RepositoryTests {
        private static final String SAMPLE = """
                @Repository
                public interface UserRepository extends JpaRepository<User, Long> {
                    @Query("SELECT u FROM User u WHERE u.email = ?1")
                    User findByEmail(String email);
                }
                """;

        @Test
        void detectsRepository() {
            var r = new RepositoryDetector().detect(ctx("java", SAMPLE));
            assertEquals(2, r.nodes().size()); // repository + database:unknown
            assertTrue(r.nodes().stream().anyMatch(n -> "UserRepository".equals(n.getLabel())));
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == io.github.randomcodespace.iq.model.EdgeKind.CONNECTS_TO));
            assertTrue(r.nodes().stream()
                    .filter(n -> n.getKind() == io.github.randomcodespace.iq.model.NodeKind.REPOSITORY)
                    .allMatch(n -> "spring_boot".equals(n.getProperties().get("framework"))),
                    "Repository nodes should have framework=spring_boot");
        }

        @Test
        void ignoresPlainCode() {
            assertTrue(new RepositoryDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new RepositoryDetector(), ctx("java", SAMPLE));
        }
    }

    // ==================== JdbcDetector ====================
    @Nested
    class JdbcTests {
        private static final String SAMPLE = """
                public class DbService {
                    private final JdbcTemplate jdbcTemplate;
                    public void connect() {
                        DriverManager.getConnection("jdbc:mysql://localhost:3306/mydb");
                    }
                }
                """;

        @Test
        void detectsJdbc() {
            var r = new JdbcDetector().detect(ctx("java", SAMPLE));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void ignoresPlainCode() {
            assertTrue(new JdbcDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new JdbcDetector(), ctx("java", SAMPLE));
        }
    }

    // ==================== RawSqlDetector ====================
    @Nested
    class RawSqlTests {
        private static final String SAMPLE = """
                public class QueryService {
                    @Query("SELECT * FROM users WHERE id = ?1")
                    User findById(Long id);
                }
                """;

        @Test
        void detectsRawSql() {
            var r = new RawSqlDetector().detect(ctx("java", SAMPLE));
            assertFalse(r.nodes().isEmpty());
            assertEquals("users", r.nodes().get(0).getProperties().get("tables").toString().replaceAll("[\\[\\]]", ""));
        }

        @Test
        void ignoresPlainCode() {
            assertTrue(new RawSqlDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new RawSqlDetector(), ctx("java", SAMPLE));
        }
    }

    // ==================== KafkaDetector ====================
    @Nested
    class KafkaTests {
        private static final String SAMPLE = """
                public class KafkaService {
                    @KafkaListener(topics = "orders")
                    public void consume(String msg) {}
                    public void produce() { kafkaTemplate.send("notifications", "hi"); }
                }
                """;

        @Test
        void detectsKafka() {
            var r = new KafkaDetector().detect(ctx("java", SAMPLE));
            assertFalse(r.nodes().isEmpty());
            assertFalse(r.edges().isEmpty());
        }

        @Test
        void ignoresPlainCode() {
            assertTrue(new KafkaDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new KafkaDetector(), ctx("java", SAMPLE));
        }

        @Test
        void detectsKotlinKafkaListener() {
            String kotlinSample = """
                    class OrderConsumer {
                        @KafkaListener(topics = "orders")
                        fun consume(msg: String) {}
                    }
                    """;
            var r = new KafkaDetector().detect(ctx("kotlin", kotlinSample));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind().getValue().equals("consumes")));
        }

        @Test
        void detectsKotlinKafkaTemplate() {
            String kotlinSample = """
                    class NotificationService(val kafkaTemplate: KafkaTemplate<String, String>) {
                        fun notify() { kafkaTemplate.send("notifications", "hi") }
                    }
                    """;
            var r = new KafkaDetector().detect(ctx("kotlin", kotlinSample));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind().getValue().equals("produces")));
        }

        @Test
        void detectsKotlinObjectDeclaration() {
            String kotlinSample = """
                    object OrderConsumer {
                        @KafkaListener(topics = "orders")
                        fun consume(msg: String) {}
                    }
                    """;
            var r = new KafkaDetector().detect(ctx("kotlin", kotlinSample));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind().getValue().equals("consumes")));
        }

        @Test
        void detectsKotlinDataClassModifiers() {
            String kotlinSample = """
                    internal open class PaymentConsumer {
                        @KafkaListener(topics = "payments")
                        fun consume(msg: String) {}
                    }
                    """;
            var r = new KafkaDetector().detect(ctx("kotlin", kotlinSample));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind().getValue().equals("consumes")));
        }
    }

    // ==================== KafkaProtocolDetector ====================
    @Nested
    class KafkaProtocolTests {
        private static final String SAMPLE = """
                public class FetchRequest extends AbstractRequest {
                }
                public class FetchResponse extends AbstractResponse {
                }
                """;

        @Test
        void detectsProtocolMessages() {
            var r = new KafkaProtocolDetector().detect(ctx("java", SAMPLE));
            assertEquals(2, r.nodes().size());
        }

        @Test
        void ignoresPlainCode() {
            assertTrue(new KafkaProtocolDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new KafkaProtocolDetector(), ctx("java", SAMPLE));
        }
    }

    // ==================== JmsDetector ====================
    @Nested
    class JmsTests {
        private static final String SAMPLE = """
                public class JmsService {
                    @JmsListener(destination = "orders.queue")
                    public void receive(String msg) {}
                    public void send() { jmsTemplate.send("reply.queue", msg); }
                }
                """;

        @Test
        void detectsJms() {
            var r = new JmsDetector().detect(ctx("java", SAMPLE));
            assertFalse(r.nodes().isEmpty());
            assertFalse(r.edges().isEmpty());
        }

        @Test
        void ignoresPlainCode() {
            assertTrue(new JmsDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new JmsDetector(), ctx("java", SAMPLE));
        }
    }

    // ==================== RabbitmqDetector ====================
    @Nested
    class RabbitmqTests {
        private static final String SAMPLE = """
                public class RabbitService {
                    @RabbitListener(queues = "orders")
                    public void receive(String msg) {}
                    public void send() { rabbitTemplate.convertAndSend("exchange1", "key", "msg"); }
                }
                """;

        @Test
        void detectsRabbitmq() {
            var r = new RabbitmqDetector().detect(ctx("java", SAMPLE));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void ignoresPlainCode() {
            assertTrue(new RabbitmqDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new RabbitmqDetector(), ctx("java", SAMPLE));
        }
    }

    // ==================== JaxrsDetector ====================
    @Nested
    class JaxrsTests {
        private static final String SAMPLE = """
                @Path("/api/users")
                public class UserResource {
                    @GET
                    @Path("/{id}")
                    public User getUser(@PathParam("id") Long id) { return null; }
                }
                """;

        @Test
        void detectsJaxrs() {
            var r = new JaxrsDetector().detect(ctx("java", SAMPLE));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.nodes().get(0).getLabel().contains("GET"));
        }

        @Test
        void ignoresPlainCode() {
            assertTrue(new JaxrsDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new JaxrsDetector(), ctx("java", SAMPLE));
        }
    }

    // ==================== GrpcServiceDetector ====================
    @Nested
    class GrpcServiceTests {
        private static final String SAMPLE = """
                @GrpcService
                public class GreeterServiceImpl extends GreeterGrpc.GreeterImplBase {
                    @Override
                    public void sayHello(HelloRequest request) {}
                }
                """;

        @Test
        void detectsGrpc() {
            var r = new GrpcServiceDetector().detect(ctx("java", SAMPLE));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.nodes().stream().anyMatch(n -> n.getLabel().contains("gRPC")));
        }

        @Test
        void ignoresPlainCode() {
            assertTrue(new GrpcServiceDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new GrpcServiceDetector(), ctx("java", SAMPLE));
        }
    }

    // ==================== GraphqlResolverDetector ====================
    @Nested
    class GraphqlResolverTests {
        private static final String SAMPLE = """
                @Controller
                public class BookController {
                    @QueryMapping
                    public Book bookById(String id) { return null; }
                    @MutationMapping
                    public Book addBook(BookInput input) { return null; }
                }
                """;

        @Test
        void detectsGraphql() {
            var r = new GraphqlResolverDetector().detect(ctx("java", SAMPLE));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.nodes().stream().anyMatch(n -> n.getLabel().contains("Query")));
        }

        @Test
        void ignoresPlainCode() {
            assertTrue(new GraphqlResolverDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new GraphqlResolverDetector(), ctx("java", SAMPLE));
        }
    }

    // ==================== WebSocketDetector ====================
    @Nested
    class WebSocketTests {
        private static final String SAMPLE = """
                @ServerEndpoint("/ws/chat")
                public class ChatEndpoint {
                    @MessageMapping("/send")
                    @SendTo("/topic/messages")
                    public String handle(String msg) { return msg; }
                }
                """;

        @Test
        void detectsWebSocket() {
            var r = new WebSocketDetector().detect(ctx("java", SAMPLE));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void ignoresPlainCode() {
            assertTrue(new WebSocketDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new WebSocketDetector(), ctx("java", SAMPLE));
        }
    }

    // ==================== RmiDetector ====================
    @Nested
    class RmiTests {
        private static final String SAMPLE = """
                public interface Calculator extends java.rmi.Remote {
                    int add(int a, int b) throws RemoteException;
                }
                public class CalculatorImpl extends java.rmi.server.UnicastRemoteObject implements Calculator {
                }
                """;

        @Test
        void detectsRmi() {
            var r = new RmiDetector().detect(ctx("java", SAMPLE));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void ignoresPlainCode() {
            assertTrue(new RmiDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new RmiDetector(), ctx("java", SAMPLE));
        }
    }

    // ==================== ClassHierarchyDetector ====================
    @Nested
    class ClassHierarchyTests {
        private static final String SAMPLE = """
                public abstract class Animal implements Serializable {
                }
                public class Dog extends Animal implements Comparable {
                }
                public interface Flyable extends Moveable {
                }
                public enum Color implements Coded {
                }
                public @interface MyAnnotation {
                }
                """;

        @Test
        void detectsHierarchy() {
            var r = new ClassHierarchyDetector().detect(ctx("java", SAMPLE));
            assertEquals(5, r.nodes().size());
            assertFalse(r.edges().isEmpty());
        }

        @Test
        void ignoresEmptyFile() {
            assertTrue(new ClassHierarchyDetector().detect(ctx("java", "")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new ClassHierarchyDetector(), ctx("java", SAMPLE));
        }
    }

    // ==================== ConfigDefDetector ====================
    @Nested
    class ConfigDefTests {
        private static final String SAMPLE = """
                public class MyConfig {
                    static ConfigDef CONFIG = new ConfigDef()
                        .define("my.setting.name", Type.STRING, "default")
                        .define("my.setting.port", Type.INT, 8080);
                }
                """;

        @Test
        void detectsConfigDef() {
            var r = new ConfigDefDetector().detect(ctx("java", SAMPLE));
            assertEquals(2, r.nodes().size());
            assertEquals(2, r.edges().size());
        }

        @Test
        void ignoresPlainCode() {
            assertTrue(new ConfigDefDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new ConfigDefDetector(), ctx("java", SAMPLE));
        }

        @Test
        void detectsSpringValueAnnotation() {
            String sample = """
                    import org.springframework.beans.factory.annotation.Value;
                    public class AppConfig {
                        @Value("${app.timeout}")
                        private int timeout;
                        @Value("${app.host}")
                        private String host;
                    }
                    """;
            var r = new ConfigDefDetector().detect(ctx("java", sample));
            assertEquals(2, r.nodes().size());
            assertTrue(r.nodes().stream().anyMatch(n -> n.getLabel().equals("app.timeout")));
            assertTrue(r.nodes().stream().anyMatch(n -> n.getLabel().equals("app.host")));
        }

        @Test
        void detectsConfigurationPropertiesAnnotation() {
            String sample = """
                    import org.springframework.boot.context.properties.ConfigurationProperties;
                    @ConfigurationProperties(prefix = "spring.datasource")
                    public class DataSourceConfig {
                        private String url;
                    }
                    """;
            var r = new ConfigDefDetector().detect(ctx("java", sample));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.nodes().stream().anyMatch(n -> n.getLabel().equals("spring.datasource")));
        }

        @Test
        void isDeterministicWithValueAnnotations() {
            String sample = """
                    public class Cfg {
                        @Value("${server.port}") private int port;
                        @Value("${server.host}") private String host;
                    }
                    """;
            DetectorTestUtils.assertDeterministic(new ConfigDefDetector(), ctx("java", sample));
        }
    }

    // ==================== ModuleDepsDetector ====================
    @Nested
    class ModuleDepsTests {
        private static final String POM = """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <modules>
                        <module>core</module>
                    </modules>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework</groupId>
                            <artifactId>spring-core</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;

        @Test
        void detectsMaven() {
            var r = new ModuleDepsDetector().detect(new DetectorContext("pom.xml", "xml", POM));
            assertFalse(r.nodes().isEmpty());
            assertFalse(r.edges().isEmpty());
        }

        @Test
        void ignoresPlainJava() {
            assertTrue(new ModuleDepsDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new ModuleDepsDetector(), new DetectorContext("pom.xml", "xml", POM));
        }
    }

    // ==================== PublicApiDetector ====================
    @Nested
    class PublicApiTests {
        private static final String SAMPLE = """
                public class UserService {
                    public User findUser(String name) { return null; }
                    protected void process(Order order) {}
                    private void internal() {}
                    public String getName() { return name; }
                }
                """;

        @Test
        void detectsPublicApi() {
            var r = new PublicApiDetector().detect(ctx("java", SAMPLE));
            // findUser and process should be detected; internal (private) and getName (trivial getter) should not
            assertEquals(2, r.nodes().size());
        }

        @Test
        void ignoresEmptyClass() {
            assertTrue(new PublicApiDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new PublicApiDetector(), ctx("java", SAMPLE));
        }
    }

    // ==================== MicronautDetector ====================
    @Nested
    class MicronautTests {
        private static final String SAMPLE = """
                import io.micronaut.http.annotation.Controller;
                import io.micronaut.http.annotation.Get;
                @Controller("/api")
                public class HelloController {
                    @Get("/hello")
                    public String hello() { return "hi"; }
                    @Singleton
                    public void config() {}
                }
                """;

        @Test
        void detectsMicronaut() {
            var r = new MicronautDetector().detect(ctx("java", SAMPLE));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void ignoresPlainCode() {
            assertTrue(new MicronautDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new MicronautDetector(), ctx("java", SAMPLE));
        }
    }

    // ==================== QuarkusDetector ====================
    @Nested
    class QuarkusTests {
        private static final String SAMPLE = """
                import io.quarkus.runtime.annotations.ConfigProperty;
                @ApplicationScoped
                public class GreetingService {
                    @ConfigProperty(name = "greeting.message")
                    String message;
                    @Scheduled(every = "10s")
                    public void tick() {}
                }
                """;

        @Test
        void detectsQuarkus() {
            var r = new QuarkusDetector().detect(ctx("java", SAMPLE));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void ignoresPlainCode() {
            assertTrue(new QuarkusDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new QuarkusDetector(), ctx("java", SAMPLE));
        }
    }

    // ==================== CosmosDbDetector ====================
    @Nested
    class CosmosDbTests {
        private static final String SAMPLE = """
                public class CosmosService {
                    public void init() {
                        CosmosClient client = null;
                        client.getDatabase("mydb").getContainer("users");
                    }
                }
                """;

        @Test
        void detectsCosmosDb() {
            var r = new CosmosDbDetector().detect(ctx("java", SAMPLE));
            assertFalse(r.nodes().isEmpty());
            assertFalse(r.edges().isEmpty());
        }

        @Test
        void ignoresPlainCode() {
            assertTrue(new CosmosDbDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new CosmosDbDetector(), ctx("java", SAMPLE));
        }
    }

    // ==================== AzureFunctionsDetector ====================
    @Nested
    class AzureFunctionsTests {
        private static final String SAMPLE = """
                public class Functions {
                    @FunctionName("HttpExample")
                    public String run(@HttpTrigger(name = "req") String request) {
                        return "Hello";
                    }
                }
                """;

        @Test
        void detectsAzureFunctions() {
            var r = new AzureFunctionsDetector().detect(ctx("java", SAMPLE));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void ignoresPlainCode() {
            assertTrue(new AzureFunctionsDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new AzureFunctionsDetector(), ctx("java", SAMPLE));
        }
    }

    // ==================== AzureMessagingDetector ====================
    @Nested
    class AzureMessagingTests {
        private static final String SAMPLE = """
                public class MessageService {
                    ServiceBusSenderClient sender;
                    public void init() {
                        new ServiceBusClientBuilder().queueName("orders").buildClient();
                    }
                }
                """;

        @Test
        void detectsAzureMessaging() {
            var r = new AzureMessagingDetector().detect(ctx("java", SAMPLE));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void ignoresPlainCode() {
            assertTrue(new AzureMessagingDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new AzureMessagingDetector(), ctx("java", SAMPLE));
        }
    }

    // ==================== IbmMqDetector ====================
    @Nested
    class IbmMqTests {
        private static final String SAMPLE = """
                public class MqService {
                    public void connect() {
                        MQQueueManager qm = new MQQueueManager("QM1");
                        qm.accessQueue("ORDERS.QUEUE", openOptions);
                        queue.put(msg);
                    }
                }
                """;

        @Test
        void detectsIbmMq() {
            var r = new IbmMqDetector().detect(ctx("java", SAMPLE));
            assertFalse(r.nodes().isEmpty());
            assertFalse(r.edges().isEmpty());
        }

        @Test
        void ignoresPlainCode() {
            assertTrue(new IbmMqDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new IbmMqDetector(), ctx("java", SAMPLE));
        }
    }

    // ==================== TibcoEmsDetector ====================
    @Nested
    class TibcoEmsTests {
        private static final String SAMPLE = """
                public class EmsService {
                    TibjmsConnectionFactory factory = new TibjmsConnectionFactory();
                    public void setup() {
                        factory.setServerUrl("tcp://ems-server:7222");
                        session.createQueue("ORDER.QUEUE");
                        producer.send(msg);
                    }
                }
                """;

        @Test
        void detectsTibcoEms() {
            var r = new TibcoEmsDetector().detect(ctx("java", SAMPLE));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void ignoresPlainCode() {
            assertTrue(new TibcoEmsDetector().detect(ctx("java", "public class Foo {}")).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            DetectorTestUtils.assertDeterministic(new TibcoEmsDetector(), ctx("java", SAMPLE));
        }
    }

    // ==================== Helper ====================
    private static DetectorContext ctx(String language, String content) {
        return DetectorTestUtils.contextFor(language, content);
    }
}
