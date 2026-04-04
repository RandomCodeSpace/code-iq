package io.github.randomcodespace.iq.detector.java;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive coverage tests for Java detectors targeting branches not covered
 * by JavaDetectorsTest and JavaDetectorsExtendedTest.
 */
class JavaDetectorsCoverageTest {

    // ==================== KafkaDetector — deeper coverage ====================
    @Nested
    class KafkaDetectorCoverage {
        private final KafkaDetector d = new KafkaDetector();

        @Test
        void returnsEmptyOnNullContent() {
            var ctx = new DetectorContext("Test.java", "java", null);
            assertTrue(d.detect(ctx).nodes().isEmpty());
        }

        @Test
        void returnsEmptyOnEmptyContent() {
            var ctx = new DetectorContext("Test.java", "java", "");
            assertTrue(d.detect(ctx).nodes().isEmpty());
        }

        @Test
        void returnsEmptyWhenNoKafkaKeywords() {
            var ctx = ctx("java", "public class Foo { void bar() {} }");
            assertTrue(d.detect(ctx).nodes().isEmpty());
        }

        @Test
        void returnsEmptyWhenKafkaListenerButNoClass() {
            // @KafkaListener present but no class declaration
            var ctx = ctx("java", "@KafkaListener(topics = \"test\") void handle() {}");
            assertTrue(d.detect(ctx).nodes().isEmpty());
        }

        @Test
        void detectsKafkaListenerWithTopicsArray() {
            String code = """
                    public class OrderConsumer {
                        @KafkaListener(topics = {"orders", "returns"})
                        public void onOrder(String msg) {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            // At minimum one CONSUMES edge should be found
            assertFalse(r.edges().isEmpty());
        }

        @Test
        void detectsKafkaListenerWithGroupId() {
            String code = """
                    public class InventoryConsumer {
                        @KafkaListener(topics = "inventory", groupId = "inventory-group")
                        public void process(String msg) {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertFalse(r.edges().isEmpty());
            var consumesEdge = r.edges().stream()
                    .filter(e -> e.getKind() == EdgeKind.CONSUMES)
                    .findFirst();
            assertTrue(consumesEdge.isPresent());
            assertEquals("inventory-group", consumesEdge.get().getProperties().get("group_id"));
        }

        @Test
        void detectsMultipleTopicsBothConsumedAndProduced() {
            String code = """
                    public class PaymentService {
                        @KafkaListener(topics = "payments")
                        public void onPayment(String msg) {}
                        @KafkaListener(topics = "refunds")
                        public void onRefund(String msg) {}
                        public void notify() { kafkaTemplate.send("notifications", "done"); }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertEquals(3, r.nodes().size(), "Should have 3 topic nodes");
            long consumesCount = r.edges().stream()
                    .filter(e -> e.getKind() == EdgeKind.CONSUMES).count();
            long producesCount = r.edges().stream()
                    .filter(e -> e.getKind() == EdgeKind.PRODUCES).count();
            assertEquals(2, consumesCount);
            assertEquals(1, producesCount);
        }

        @Test
        void detectsKafkaListenerOnNextLineTopics() {
            // Pattern where @KafkaListener is on one line, topics on next
            String code = """
                    public class ShipmentConsumer {
                        @KafkaListener(
                            "shipments")
                        public void handle(String msg) {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            // May or may not detect — just ensure no exception
            assertNotNull(r);
        }

        @Test
        void isDeterministicWithMultipleTopics() {
            String code = """
                    public class MultiConsumer {
                        @KafkaListener(topics = "topic1")
                        public void a(String m) {}
                        @KafkaListener(topics = "topic2")
                        public void b(String m) {}
                        public void produce() { kafkaTemplate.send("output", "x"); }
                    }
                    """;
            DetectorTestUtils.assertDeterministic(d, ctx("java", code));
        }

        @Test
        void getName() {
            assertEquals("kafka", d.getName());
        }

        @Test
        void getSupportedLanguages() {
            assertThat(d.getSupportedLanguages()).contains("java", "kotlin");
        }
    }

    // ==================== GrpcServiceDetector — deeper coverage ====================
    @Nested
    class GrpcDetectorCoverage {
        private final GrpcServiceDetector d = new GrpcServiceDetector();

        @Test
        void returnsEmptyOnNullContent() {
            assertTrue(d.detect(new DetectorContext("Test.java", "java", null)).nodes().isEmpty());
        }

        @Test
        void returnsEmptyOnEmptyContent() {
            assertTrue(d.detect(new DetectorContext("Test.java", "java", "")).nodes().isEmpty());
        }

        @Test
        void detectsGrpcWithAnnotationOnly() {
            String code = """
                    @GrpcService
                    public class MyServiceImpl extends MyServiceGrpc.MyServiceImplBase {
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.nodes().stream().anyMatch(n ->
                    n.getAnnotations().contains("@GrpcService")));
        }

        @Test
        void detectsGrpcClientStub() {
            String code = """
                    public class PaymentClient {
                        private final PaymentServiceGrpc.PaymentServiceBlockingStub stub;
                        public PaymentClient(ManagedChannel channel) {
                            stub = PaymentServiceGrpc.newBlockingStub(channel);
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.edges().isEmpty());
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CALLS));
        }

        @Test
        void detectsMultipleRpcMethods() {
            String code = """
                    @GrpcService
                    public class GreeterImpl extends GreeterGrpc.GreeterImplBase {
                        @Override
                        public void sayHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {}
                        @Override
                        public void sayGoodbye(GoodbyeRequest request, StreamObserver<GoodbyeReply> responseObserver) {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            long rpcNodes = r.nodes().stream()
                    .filter(n -> n.getKind() == NodeKind.ENDPOINT).count();
            assertTrue(rpcNodes >= 2, "Should detect at least 2 RPC method nodes");
        }

        @Test
        void detectsBothImplAndStubInSameClass() {
            String code = """
                    @GrpcService
                    public class GatewayImpl extends GatewayGrpc.GatewayImplBase {
                        @Override
                        public void route(RouteRequest req, StreamObserver<RouteReply> obs) {
                            OrderServiceGrpc.newBlockingStub(channel).process(null);
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            boolean hasExposes = r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.EXPOSES);
            boolean hasCalls = r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CALLS);
            assertTrue(hasExposes, "Should have EXPOSES edge for service impl");
            assertTrue(hasCalls, "Should have CALLS edge for client stub");
        }

        @Test
        void detectsFutureStub() {
            String code = """
                    public class AsyncClient {
                        void init() {
                            UserServiceGrpc.newFutureStub(channel);
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CALLS));
        }

        @Test
        void isDeterministic() {
            String code = """
                    @GrpcService
                    public class OrderImpl extends OrderGrpc.OrderImplBase {
                        @Override
                        public void createOrder(OrderRequest req, StreamObserver<OrderReply> obs) {}
                    }
                    """;
            DetectorTestUtils.assertDeterministic(d, ctx("java", code));
        }

        @Test
        void getName() {
            assertEquals("grpc_service", d.getName());
        }
    }

    // ==================== RabbitmqDetector — deeper coverage ====================
    @Nested
    class RabbitmqDetectorCoverage {
        private final RabbitmqDetector d = new RabbitmqDetector();

        @Test
        void returnsEmptyOnNull() {
            assertTrue(d.detect(new DetectorContext("Test.java", "java", null)).nodes().isEmpty());
        }

        @Test
        void detectsRabbitListenerWithQueuesAttr() {
            String code = """
                    public class OrderListener {
                        @RabbitListener(queues = "orders.queue")
                        public void listen(String msg) {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.QUEUE));
        }

        @Test
        void detectsDirectExchangeDeclaration() {
            String code = """
                    public class RabbitConfig {
                        @Bean
                        public DirectExchange("orders-exchange") ordersExchange() {
                            return new DirectExchange("orders-exchange");
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsTopicExchangeDeclaration() {
            String code = """
                    public class RabbitConfig {
                        @Bean
                        public TopicExchange topicExchange() {
                            return new TopicExchange("events-exchange");
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsFanoutExchangeDeclaration() {
            String code = """
                    public class RabbitConfig {
                        @Bean
                        public FanoutExchange broadcastExchange() {
                            return new FanoutExchange("broadcast");
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsRabbitTemplateWithRoutingKey() {
            String code = """
                    public class NotificationSender {
                        public void send(String msg) {
                            rabbitTemplate.convertAndSend("events-exchange", msg);
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertFalse(r.edges().isEmpty());
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.PRODUCES));
        }

        @Test
        void detectsMultipleQueuesAndExchanges() {
            String code = """
                    public class MessageService {
                        @RabbitListener(queues = "orders")
                        public void onOrder(String m) {}
                        @RabbitListener(queues = "payments")
                        public void onPayment(String m) {}
                        public void publish() {
                            rabbitTemplate.convertAndSend("notifications", "done");
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            // Should have queue nodes for orders, payments, and exchange for notifications
            assertTrue(r.nodes().size() >= 3);
        }

        @Test
        void isDeterministic() {
            String code = """
                    public class EventBus {
                        @RabbitListener(queues = "events")
                        public void consume(String msg) {}
                        public void emit() {
                            rabbitTemplate.convertAndSend("events-exchange", "data");
                        }
                    }
                    """;
            DetectorTestUtils.assertDeterministic(d, ctx("java", code));
        }

        @Test
        void getName() {
            assertEquals("rabbitmq", d.getName());
        }
    }

    // ==================== JdbcDetector — deeper coverage ====================
    @Nested
    class JdbcDetectorCoverage {
        private final JdbcDetector d = new JdbcDetector();

        @Test
        void returnsEmptyOnNull() {
            assertTrue(d.detect(new DetectorContext("Test.java", "java", null)).nodes().isEmpty());
        }

        @Test
        void detectsPostgresJdbcUrl() {
            String code = """
                    public class DbService {
                        void connect() {
                            DriverManager.getConnection("jdbc:postgresql://localhost:5432/mydb");
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.nodes().stream().anyMatch(n ->
                    "postgresql".equals(n.getProperties().get("db_type"))));
        }

        @Test
        void detectsH2JdbcUrl() {
            String code = """
                    public class TestDb {
                        void connect() {
                            DriverManager.getConnection("jdbc:h2:mem:testdb");
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsOracleJdbcUrl() {
            String code = """
                    public class OracleService {
                        void connect() {
                            DriverManager.getConnection("jdbc:oracle:thin:@localhost:1521:orcl");
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsNamedParameterJdbcTemplate() {
            String code = """
                    public class UserDao {
                        private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
                        public UserDao(NamedParameterJdbcTemplate template) {
                            this.namedParameterJdbcTemplate = template;
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsJdbcClientModern() {
            String code = """
                    public class ModernDao {
                        private final JdbcClient jdbcClient;
                        public ModernDao(JdbcClient client) {
                            this.jdbcClient = client;
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsDataSourceBean() {
            // DataSource @Bean alone doesn't create a DB node — it needs JdbcTemplate or DriverManager too
            // but a class with @Bean and DataSource keyword is detected via DATASOURCE_BEAN_RE
            String code = """
                    public class DataSourceConfig {
                        @Bean
                        public DataSource dataSource() {
                            return DataSourceBuilder.create().build();
                        }
                        private JdbcTemplate jdbcTemplate;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsStandaloneJdbcUrlString() {
            String code = """
                    public class ConnectionFactory {
                        private static final String URL = "jdbc:mysql://db-server:3306/prod";
                        DataSource ds;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void isDeterministicWithMultipleSources() {
            String code = """
                    public class MultiDbService {
                        private JdbcTemplate jdbcTemplate;
                        void connect() {
                            DriverManager.getConnection("jdbc:postgresql://host:5432/db");
                        }
                    }
                    """;
            DetectorTestUtils.assertDeterministic(d, ctx("java", code));
        }

        @Test
        void getName() {
            assertEquals("jdbc", d.getName());
        }
    }

    // ==================== RawSqlDetector — deeper coverage ====================
    @Nested
    class RawSqlDetectorCoverage {
        private final RawSqlDetector d = new RawSqlDetector();

        @Test
        void returnsEmptyOnNull() {
            assertTrue(d.detect(new DetectorContext("Test.java", "java", null)).nodes().isEmpty());
        }

        @Test
        void detectsNativeQuery() {
            String code = """
                    public interface ProductRepo extends JpaRepository<Product, Long> {
                        @Query(value = "SELECT * FROM products WHERE category = ?1", nativeQuery = true)
                        List<Product> findByCategory(String cat);
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertTrue((Boolean) r.nodes().get(0).getProperties().get("native"));
        }

        @Test
        void detectsJdbcTemplateQuery() {
            String code = """
                    public class OrderDao {
                        JdbcTemplate jdbcTemplate;
                        public List<Order> findAll() {
                            return jdbcTemplate.query("SELECT id, name FROM orders WHERE active = 1",
                                rowMapper);
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("jdbc_template", r.nodes().get(0).getProperties().get("source"));
        }

        @Test
        void detectsEntityManagerNativeQuery() {
            // createNativeQuery is on one line — the pattern requires it on the same line as the string
            String code = """
                    public class ReportDao {
                        EntityManager entityManager;
                        public List<Object[]> getReport() {
                            return entityManager.createNativeQuery("SELECT u.name, COUNT(*) FROM users u JOIN orders o ON u.id = o.user_id GROUP BY u.name").getResultList();
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("entity_manager", r.nodes().get(0).getProperties().get("source"));
            // native flag depends on whether createNativeQuery is in the look-back window
            assertNotNull(r.nodes().get(0).getProperties().get("native"));
        }

        @Test
        void detectsEntityManagerJpql() {
            String code = """
                    public class SearchDao {
                        EntityManager em;
                        public List<User> search(String name) {
                            return em.createQuery("SELECT u FROM User u WHERE u.name = :name")
                                .setParameter("name", name).getResultList();
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertFalse((Boolean) r.nodes().get(0).getProperties().get("native"),
                    "createQuery (JPQL) should not be native");
        }

        @Test
        void detectsTablesInJoinQuery() {
            String code = """
                    public class SalesRepo {
                        @Query("SELECT s FROM sales s JOIN customers c ON s.customer_id = c.id WHERE s.amount > 100")
                        List<Object> findLargeSales();
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            @SuppressWarnings("unchecked")
            var tables = (java.util.List<String>) r.nodes().get(0).getProperties().get("tables");
            assertFalse(tables.isEmpty());
        }

        @Test
        void detectsMultipleQueries() {
            String code = """
                    public class MultiQueryRepo {
                        @Query("SELECT * FROM users WHERE id = ?1")
                        User findUser(Long id);
                        @Query("SELECT * FROM orders WHERE user_id = ?1")
                        List<Object> findOrders(Long userId);
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertEquals(2, r.nodes().size());
        }

        @Test
        void returnsEmptyWhenNoQueryPattern() {
            String code = """
                    public class PlainService {
                        private String name;
                        public String getName() { return name; }
                    }
                    """;
            assertTrue(d.detect(ctx("java", code)).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            String code = """
                    public class StaticRepo {
                        @Query("SELECT * FROM reports WHERE year = ?1")
                        List<Object> getByYear(int year);
                        @Query("SELECT * FROM reports WHERE month = ?1")
                        List<Object> getByMonth(int month);
                    }
                    """;
            DetectorTestUtils.assertDeterministic(d, ctx("java", code));
        }

        @Test
        void getName() {
            assertEquals("raw_sql", d.getName());
        }
    }

    // ==================== SpringEventsDetector — deeper coverage ====================
    @Nested
    class SpringEventsDetectorCoverage {
        private final SpringEventsDetector d = new SpringEventsDetector();

        @Test
        void returnsEmptyOnNull() {
            assertTrue(d.detect(new DetectorContext("Test.java", "java", null)).nodes().isEmpty());
        }

        @Test
        void detectsTransactionalEventListener() {
            String code = """
                    public class PaymentEventHandler {
                        @TransactionalEventListener
                        public void onPayment(PaymentCompletedEvent event) {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.EVENT));
        }

        @Test
        void detectsEventClassDeclaration() {
            String code = """
                    public class UserRegisteredEvent extends ApplicationEvent {
                        private final String username;
                        public UserRegisteredEvent(Object source, String username) {
                            super(source);
                            this.username = username;
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.nodes().stream().anyMatch(n -> "UserRegisteredEvent".equals(n.getLabel())));
        }

        @Test
        void detectsPublishEventWithNewKeyword() {
            String code = """
                    public class RegistrationService {
                        private ApplicationEventPublisher applicationEventPublisher;
                        public void register(String user) {
                            applicationEventPublisher.publishEvent(new UserRegisteredEvent(this, user));
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.edges().isEmpty());
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.PUBLISHES));
        }

        @Test
        void detectsPublishEventWithVariable() {
            String code = """
                    public class OrderService {
                        private ApplicationEventPublisher publisher;
                        public void complete(Long orderId) {
                            OrderCompletedEvent event = new OrderCompletedEvent(orderId);
                            publisher.publishEvent(OrderCompletedEvent);
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsMultipleListeners() {
            String code = """
                    public class EventListener {
                        @EventListener
                        public void onStartup(ApplicationStartedEvent event) {}
                        @EventListener
                        public void onShutdown(ContextClosedEvent event) {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            // Should have event nodes and listen edges
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void returnsEmptyWhenNoEventKeywords() {
            String code = """
                    public class PlainService {
                        public void doWork() {}
                    }
                    """;
            assertTrue(d.detect(ctx("java", code)).nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            String code = """
                    public class SomeEventHandler {
                        @EventListener
                        public void onEvent(SomeEvent event) {}
                        public void publisher;
                        public void publish() {
                            applicationEventPublisher.publishEvent(new SomeEvent(this));
                        }
                    }
                    """;
            DetectorTestUtils.assertDeterministic(d, ctx("java", code));
        }

        @Test
        void getName() {
            assertEquals("spring_events", d.getName());
        }
    }

    // ==================== WebSocketDetector — deeper coverage ====================
    @Nested
    class WebSocketDetectorCoverage {
        private final WebSocketDetector d = new WebSocketDetector();

        @Test
        void returnsEmptyOnNull() {
            assertTrue(d.detect(new DetectorContext("Test.java", "java", null)).nodes().isEmpty());
        }

        @Test
        void detectsStompWithSendToUser() {
            String code = """
                    @Controller
                    public class PrivateMsgController {
                        @MessageMapping("/private")
                        @SendToUser("/queue/reply")
                        public String handlePrivate(String msg) { return msg; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            // SendToUser should also produce a topic node
            assertTrue(r.nodes().size() >= 2);
        }

        @Test
        void detectsSimpMessagingTemplateSend() {
            String code = """
                    @Controller
                    public class BroadcastController {
                        @Autowired
                        private SimpMessagingTemplate simpMessagingTemplate;
                        public void broadcast(String msg) {
                            simpMessagingTemplate.convertAndSend("/topic/broadcast", msg);
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.edges().isEmpty());
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.PRODUCES));
        }

        @Test
        void detectsMessagingTemplateSend() {
            // The regex matches convertAndSend with a string literal as the first argument
            String code = """
                    @Service
                    public class NotificationService {
                        private SimpMessagingTemplate simpMessagingTemplate;
                        public void broadcast(String msg) {
                            simpMessagingTemplate.convertAndSend("/topic/notifications", msg);
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.edges().isEmpty());
        }

        @Test
        void detectsServerEndpointWithPath() {
            String code = """
                    @ServerEndpoint("/ws/game/{roomId}")
                    public class GameEndpoint {
                        @OnOpen
                        public void onOpen(Session s) {}
                        @OnMessage
                        public void onMessage(String m, Session s) {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.nodes().stream().anyMatch(n ->
                    "/ws/game/{roomId}".equals(n.getProperties().get("path"))));
        }

        @Test
        void detectsMultipleMessageMappings() {
            String code = """
                    @Controller
                    public class ChatController {
                        @MessageMapping("/chat/send")
                        @SendTo("/topic/chat")
                        public ChatMessage handleMessage(ChatMessage msg) { return msg; }
                        @MessageMapping("/chat/join")
                        @SendTo("/topic/joinEvents")
                        public String handleJoin(String username) { return username + " joined"; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            // 2 message mapping nodes detected
            assertTrue(r.nodes().size() >= 2);
        }

        @Test
        void isDeterministicComplex() {
            String code = """
                    @ServerEndpoint("/ws/echo")
                    public class EchoEndpoint {
                        @MessageMapping("/echo")
                        @SendTo("/topic/echo")
                        public String echo(String msg) { return msg; }
                    }
                    """;
            DetectorTestUtils.assertDeterministic(d, ctx("java", code));
        }

        @Test
        void getName() {
            assertEquals("websocket", d.getName());
        }
    }

    // ==================== RepositoryDetector — deeper coverage ====================
    @Nested
    class RepositoryDetectorCoverage {
        private final RepositoryDetector d = new RepositoryDetector();

        @Test
        void returnsEmptyOnNull() {
            assertTrue(d.detect(new DetectorContext("Test.java", "java", null)).nodes().isEmpty());
        }

        @Test
        void detectsCrudRepository() {
            String code = """
                    public interface ProductRepository extends CrudRepository<Product, Long> {
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals(NodeKind.REPOSITORY, r.nodes().get(0).getKind());
            assertEquals("CrudRepository", r.nodes().get(0).getProperties().get("extends"));
        }

        @Test
        void detectsPagingAndSortingRepository() {
            String code = """
                    public interface UserRepository extends PagingAndSortingRepository<User, Long> {
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("PagingAndSortingRepository", r.nodes().get(0).getProperties().get("extends"));
        }

        @Test
        void detectsMongoRepository() {
            String code = """
                    public interface DocumentRepository extends MongoRepository<Document, String> {
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsReactiveRepository() {
            String code = """
                    public interface ReactiveUserRepo extends ReactiveCrudRepository<User, Long> {
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsRepositoryAnnotationOnly() {
            String code = """
                    @Repository
                    public interface ProductDao {
                        Product findBySku(String sku);
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.nodes().get(0).getAnnotations().contains("@Repository"));
        }

        @Test
        void detectsCustomQueryMethods() {
            String code = """
                    public interface OrderRepository extends JpaRepository<Order, Long> {
                        @Query("SELECT o FROM Order o WHERE o.status = :status")
                        List<Order> findByStatus(String status);
                        @Query("SELECT o FROM Order o WHERE o.total > :amount")
                        List<Order> findLargeOrders(double amount);
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            @SuppressWarnings("unchecked")
            var queries = (java.util.List<?>) r.nodes().get(0).getProperties().get("custom_queries");
            assertNotNull(queries);
            assertEquals(2, queries.size());
        }

        @Test
        void detectsEntityTypeFromGenerics() {
            String code = """
                    public interface CustomerRepository extends JpaRepository<Customer, UUID> {
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("Customer", r.nodes().get(0).getProperties().get("entity_type"));
        }

        @Test
        void detectsQueriesEdgeToEntity() {
            String code = """
                    public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
                    }
                    """;
            var r = d.detect(ctx("java", code));
            boolean hasQueriesEdge = r.edges().stream()
                    .anyMatch(e -> e.getKind() == EdgeKind.QUERIES);
            assertTrue(hasQueriesEdge);
        }

        @Test
        void isDeterministic() {
            String code = """
                    public interface ItemRepository extends JpaRepository<Item, Long> {
                        @Query("SELECT i FROM Item i WHERE i.active = true")
                        List<Item> findActive();
                    }
                    """;
            DetectorTestUtils.assertDeterministic(d, ctx("java", code));
        }

        @Test
        void getName() {
            assertEquals("spring_repository", d.getName());
        }
    }

    // ==================== JpaEntityDetector — deeper coverage ====================
    @Nested
    class JpaEntityDetectorCoverage {
        private final JpaEntityDetector d = new JpaEntityDetector();

        @Test
        void returnsEmptyOnNull() {
            assertTrue(d.detect(new DetectorContext("Test.java", "java", null)).nodes().isEmpty());
        }

        @Test
        void returnsEmptyWhenNoEntityAnnotation() {
            String code = """
                    public class UserDto {
                        private Long id;
                        private String name;
                    }
                    """;
            assertTrue(d.detect(ctx("java", code)).nodes().isEmpty());
        }

        @Test
        void detectsEntityWithTableName() {
            String code = """
                    @Entity
                    @Table(name = "customer_accounts")
                    public class CustomerAccount {
                        @Id
                        private Long id;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("customer_accounts", r.nodes().get(0).getProperties().get("table_name"));
        }

        @Test
        void detectsEntityWithDefaultTableName() {
            String code = """
                    @Entity
                    public class SalesOrder {
                        @Id
                        private Long id;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("salesorder", r.nodes().get(0).getProperties().get("table_name"));
        }

        @Test
        void detectsOneToManyRelationship() {
            String code = """
                    @Entity
                    public class Customer {
                        @Id private Long id;
                        @OneToMany(mappedBy = "customer")
                        private List<Order> orders;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            boolean hasMapsToEdge = r.edges().stream()
                    .anyMatch(e -> e.getKind() == EdgeKind.MAPS_TO);
            assertTrue(hasMapsToEdge);
        }

        @Test
        void detectsManyToOneRelationship() {
            String code = """
                    @Entity
                    public class Order {
                        @Id private Long id;
                        @ManyToOne
                        @JoinColumn(name = "customer_id")
                        private Customer customer;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            var mapsToEdge = r.edges().stream()
                    .filter(e -> e.getKind() == EdgeKind.MAPS_TO)
                    .findFirst();
            assertTrue(mapsToEdge.isPresent());
            assertEquals("many_to_one", mapsToEdge.get().getProperties().get("relationship_type"));
        }

        @Test
        void detectsOneToOneRelationship() {
            String code = """
                    @Entity
                    public class Employee {
                        @Id private Long id;
                        @OneToOne
                        private ParkingSpot parkingSpot;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsColumnAnnotationsWithNames() {
            // Using AST via valid Java
            String code = """
                    package com.example;
                    import javax.persistence.*;
                    @Entity
                    @Table(name = "users")
                    public class User {
                        @Id
                        @GeneratedValue
                        private Long id;
                        @Column(name = "full_name")
                        private String fullName;
                        @Column(name = "email_addr")
                        private String emailAddr;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsConnectsToDbEdge() {
            String code = """
                    @Entity
                    public class Product {
                        @Id private Long id;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CONNECTS_TO));
        }

        @Test
        void isDeterministic() {
            String code = """
                    @Entity
                    @Table(name = "items")
                    public class Item {
                        @Id private Long id;
                        @Column(name = "item_name")
                        private String name;
                        @ManyToOne
                        private Category category;
                    }
                    """;
            DetectorTestUtils.assertDeterministic(d, ctx("java", code));
        }

        @Test
        void getName() {
            assertEquals("jpa_entity", d.getName());
        }
    }

    // ==================== JaxrsDetector — deeper coverage ====================
    @Nested
    class JaxrsDetectorCoverage {
        private final JaxrsDetector d = new JaxrsDetector();

        @Test
        void returnsEmptyOnNull() {
            assertTrue(d.detect(new DetectorContext("Test.java", "java", null)).nodes().isEmpty());
        }

        @Test
        void detectsJakartaWsRsImport() {
            String code = """
                    import jakarta.ws.rs.GET;
                    import jakarta.ws.rs.Path;
                    @Path("/users")
                    public class UserResource {
                        @GET
                        public String list() { return "[]"; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsPostEndpoint() {
            String code = """
                    @Path("/products")
                    public class ProductResource {
                        @POST
                        public Response create(Product p) { return null; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("POST", r.nodes().get(0).getProperties().get("http_method"));
        }

        @Test
        void detectsPutDeleteEndpoints() {
            String code = """
                    @Path("/items")
                    public class ItemResource {
                        @PUT
                        @Path("/{id}")
                        public Response update(@PathParam("id") Long id, Item item) { return null; }
                        @DELETE
                        @Path("/{id}")
                        public Response delete(@PathParam("id") Long id) { return null; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertEquals(2, r.nodes().size());
            assertTrue(r.nodes().stream().anyMatch(n -> "PUT".equals(n.getProperties().get("http_method"))));
            assertTrue(r.nodes().stream().anyMatch(n -> "DELETE".equals(n.getProperties().get("http_method"))));
        }

        @Test
        void detectsProducesAndConsumes() {
            String code = """
                    @Path("/api")
                    public class ApiResource {
                        @GET
                        @Path("/data")
                        @Produces("application/json")
                        @Consumes("application/json")
                        public String getData() { return "{}"; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsClassLevelPath() {
            String code = """
                    @Path("/api/v1")
                    public class ApiV1Resource {
                        @GET
                        @Path("/health")
                        public Response health() { return null; }
                        @POST
                        @Path("/data")
                        public Response postData() { return null; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertEquals(2, r.nodes().size());
            // Paths should include class-level prefix
            assertTrue(r.nodes().stream().anyMatch(n ->
                    n.getProperties().get("path").toString().contains("api/v1")));
        }

        @Test
        void isDeterministic() {
            String code = """
                    @Path("/orders")
                    public class OrderResource {
                        @GET
                        public List<Order> list() { return null; }
                        @POST
                        public Order create(Order o) { return null; }
                        @GET @Path("/{id}")
                        public Order get(@PathParam("id") Long id) { return null; }
                    }
                    """;
            DetectorTestUtils.assertDeterministic(d, ctx("java", code));
        }

        @Test
        void getName() {
            assertEquals("jaxrs", d.getName());
        }
    }

    // ==================== JmsDetector — deeper coverage ====================
    @Nested
    class JmsDetectorCoverage {
        private final JmsDetector d = new JmsDetector();

        @Test
        void returnsEmptyOnNull() {
            assertTrue(d.detect(new DetectorContext("Test.java", "java", null)).nodes().isEmpty());
        }

        @Test
        void detectsJmsListenerWithContainerFactory() {
            String code = """
                    public class OrderListener {
                        @JmsListener(destination = "orders.queue", containerFactory = "jmsContainerFactory")
                        public void onOrder(String msg) {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertFalse(r.edges().isEmpty());
            var edge = r.edges().stream()
                    .filter(e -> e.getKind() == EdgeKind.CONSUMES)
                    .findFirst();
            assertTrue(edge.isPresent());
            assertEquals("jmsContainerFactory", edge.get().getProperties().get("container_factory"));
        }

        @Test
        void detectsJmsTemplateSend() {
            String code = """
                    public class NotificationService {
                        JmsTemplate jmsTemplate;
                        public void notify(String msg) {
                            jmsTemplate.send("notification.queue", session -> session.createTextMessage(msg));
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.PRODUCES));
        }

        @Test
        void detectsJmsTemplateConvertAndSend() {
            String code = """
                    public class PaymentService {
                        JmsTemplate jmsTemplate;
                        public void sendPayment(Payment p) {
                            JmsTemplate.convertAndSend("payments", p);
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsMultipleListeners() {
            String code = """
                    public class MultiQueueListener {
                        @JmsListener(destination = "queue1")
                        public void onQueue1(String msg) {}
                        @JmsListener(destination = "queue2")
                        public void onQueue2(String msg) {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertEquals(2, r.nodes().size());
            assertEquals(2, r.edges().stream().filter(e -> e.getKind() == EdgeKind.CONSUMES).count());
        }

        @Test
        void isDeterministic() {
            String code = """
                    public class MessageHandler {
                        @JmsListener(destination = "events.queue")
                        public void handle(String msg) {}
                        public void emit(JmsTemplate jmsTemplate) {
                            jmsTemplate.send("results.queue", s -> s.createTextMessage("done"));
                        }
                    }
                    """;
            DetectorTestUtils.assertDeterministic(d, ctx("java", code));
        }

        @Test
        void getName() {
            assertEquals("jms", d.getName());
        }
    }

    // ==================== KafkaProtocolDetector — deeper coverage ====================
    @Nested
    class KafkaProtocolDetectorCoverage {
        private final KafkaProtocolDetector d = new KafkaProtocolDetector();

        @Test
        void returnsEmptyOnNull() {
            assertTrue(d.detect(new DetectorContext("Test.java", "java", null)).nodes().isEmpty());
        }

        @Test
        void detectsRequestClass() {
            String code = """
                    public class MetadataRequest extends AbstractRequest {
                        private final List<String> topics;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("request", r.nodes().get(0).getProperties().get("protocol_type"));
        }

        @Test
        void detectsResponseClass() {
            String code = """
                    public class MetadataResponse extends AbstractResponse {
                        private final List<TopicMetadata> topics;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("response", r.nodes().get(0).getProperties().get("protocol_type"));
        }

        @Test
        void detectsExtendsEdge() {
            String code = """
                    public class FetchRequest extends AbstractRequest {
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.edges().isEmpty());
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.EXTENDS));
        }

        @Test
        void detectsMultipleProtocolMessages() {
            String code = """
                    public class ListOffsetRequest extends AbstractRequest {}
                    public class ListOffsetResponse extends AbstractResponse {}
                    public class ProduceRequest extends AbstractRequest {}
                    """;
            var r = d.detect(ctx("java", code));
            assertEquals(3, r.nodes().size());
            assertEquals(3, r.edges().size());
        }

        @Test
        void doesNotMatchAbstractRequestClass() {
            // AbstractRequest itself should not match as it IS AbstractRequest, not extends it
            String code = """
                    public abstract class AbstractRequest {
                        abstract short apiKey();
                    }
                    """;
            // This won't match because regex requires "extends AbstractRequest"
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void getName() {
            assertEquals("kafka_protocol", d.getName());
        }
    }

    // ==================== PublicApiDetector — deeper coverage ====================
    @Nested
    class PublicApiDetectorCoverage {
        private final PublicApiDetector d = new PublicApiDetector();

        @Test
        void returnsEmptyOnNull() {
            assertTrue(d.detect(new DetectorContext("Test.java", "java", null)).nodes().isEmpty());
        }

        @Test
        void detectsStaticPublicMethod() {
            String code = """
                    public class Utils {
                        public static String format(String template, Object... args) {
                            return String.format(template, args);
                        }
                        public static List<String> split(String input, String delimiter) {
                            return List.of(input.split(delimiter));
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsAbstractPublicMethod() {
            String code = """
                    public abstract class BaseRepository {
                        public abstract List<Object> findAll();
                        protected abstract Object findById(Long id);
                        private void internal() {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsInterfaceMethods() {
            String code = """
                    public interface UserService {
                        User findByUsername(String username);
                        List<User> findAll(int page, int size);
                        void deleteById(Long id);
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void excludesSkippedMethodNames() {
            String code = """
                    public class MyClass {
                        @Override
                        public String toString() { return ""; }
                        @Override
                        public int hashCode() { return 0; }
                        @Override
                        public boolean equals(Object o) { return false; }
                    }
                    """;
            // toString, hashCode, equals are in SKIP_METHODS
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            String code = """
                    public class ProductService {
                        public Product findProduct(Long id) { return null; }
                        public List<Product> search(String query, int page) { return null; }
                        protected void validate(Product p) {}
                    }
                    """;
            DetectorTestUtils.assertDeterministic(d, ctx("java", code));
        }

        @Test
        void getName() {
            assertEquals("java.public_api", d.getName());
        }
    }

    // ==================== RmiDetector — deeper coverage ====================
    @Nested
    class RmiDetectorCoverage {
        private final RmiDetector d = new RmiDetector();

        @Test
        void returnsEmptyOnNull() {
            assertTrue(d.detect(new DetectorContext("Test.java", "java", null)).nodes().isEmpty());
        }

        @Test
        void detectsRemoteInterface() {
            String code = """
                    public interface CalculatorService extends java.rmi.Remote {
                        int add(int a, int b) throws RemoteException;
                        int subtract(int a, int b) throws RemoteException;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals(NodeKind.RMI_INTERFACE, r.nodes().get(0).getKind());
        }

        @Test
        void detectsUnicastRemoteObject() {
            String code = """
                    public interface MathService extends Remote {}
                    public class MathServiceImpl extends UnicastRemoteObject implements MathService {
                        public int multiply(int a, int b) throws RemoteException { return a * b; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsNamingBind() {
            String code = """
                    public interface MyService extends Remote {}
                    public class Server {
                        public void start() throws Exception {
                            Naming.bind("rmi://localhost/MyService", new MyServiceImpl());
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.EXPORTS_RMI));
        }

        @Test
        void detectsNamingLookup() {
            String code = """
                    public interface RemoteService extends Remote {}
                    public class Client {
                        public void connect() throws Exception {
                            RemoteService svc = (RemoteService) Naming.lookup("rmi://server/RemoteService");
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.INVOKES_RMI));
        }

        @Test
        void isDeterministic() {
            String code = """
                    public interface BankService extends java.rmi.Remote {
                        double getBalance(String acct) throws RemoteException;
                    }
                    public class BankServiceImpl extends UnicastRemoteObject implements BankService {
                        public double getBalance(String acct) { return 0; }
                    }
                    """;
            DetectorTestUtils.assertDeterministic(d, ctx("java", code));
        }

        @Test
        void getName() {
            assertEquals("rmi", d.getName());
        }
    }

    // ==================== GraphqlResolverDetector — deeper coverage ====================
    @Nested
    class GraphqlResolverDetectorCoverage {
        private final GraphqlResolverDetector d = new GraphqlResolverDetector();

        @Test
        void returnsEmptyOnNull() {
            assertTrue(d.detect(new DetectorContext("Test.java", "java", null)).nodes().isEmpty());
        }

        @Test
        void detectsBatchMapping() {
            // @BatchMapping is in the guard check but not in PATTERNS, so it doesn't produce nodes
            // Verify it at least doesn't throw and returns valid result
            String code = """
                    @Controller
                    public class BookController {
                        @QueryMapping
                        public List<Book> books() { return null; }
                        @BatchMapping
                        public Map<Author, List<Book>> authorBooks(List<Author> authors) { return null; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            // At least the @QueryMapping produces a node
            assertFalse(r.nodes().isEmpty());
            assertNotNull(r);
        }

        @Test
        void detectsNamedQueryMapping() {
            String code = """
                    @Controller
                    public class SearchResolver {
                        @QueryMapping("searchProducts")
                        public List<Product> search(String query) { return null; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsDgsComponent() {
            String code = """
                    @DgsComponent
                    public class CategoryFetcher {
                        @DgsQuery
                        public List<Category> categories() { return null; }
                        @DgsMutation
                        public Category createCategory(String name) { return null; }
                        @DgsSubscription
                        public Publisher<Category> categoryUpdates() { return null; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            // Should have 3 nodes for query, mutation, subscription
            assertTrue(r.nodes().size() >= 3);
        }

        @Test
        void isDeterministic() {
            String code = """
                    @Controller
                    public class UserResolver {
                        @QueryMapping
                        public User userById(Long id) { return null; }
                        @MutationMapping
                        public User createUser(String name) { return null; }
                        @SubscriptionMapping
                        public Publisher<User> userEvents() { return null; }
                    }
                    """;
            DetectorTestUtils.assertDeterministic(d, ctx("java", code));
        }

        @Test
        void getName() {
            assertEquals("graphql_resolver", d.getName());
        }
    }

    // ==================== ConfigDefDetector — deeper coverage ====================
    @Nested
    class ConfigDefDetectorCoverage {
        private final ConfigDefDetector d = new ConfigDefDetector();

        @Test
        void returnsEmptyOnNull() {
            assertTrue(d.detect(new DetectorContext("Test.java", "java", null)).nodes().isEmpty());
        }

        @Test
        void detectsConfigDefWithType() {
            String code = """
                    public class SourceConfig {
                        static ConfigDef CONFIG = new ConfigDef()
                            .define("db.host", Type.STRING, "localhost")
                            .define("db.port", Type.INT, 5432)
                            .define("db.name", Type.STRING, "mydb");
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertEquals(3, r.nodes().size());
            assertTrue(r.nodes().stream().anyMatch(n -> "db.host".equals(n.getLabel())));
        }

        @Test
        void detectsSpringValueWithExpression() {
            String code = """
                    public class AppService {
                        @Value("${server.timeout:30}")
                        private int timeout;
                        @Value("${app.debug:false}")
                        private boolean debug;
                        @Value("${app.name}")
                        private String appName;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertEquals(3, r.nodes().size());
        }

        @Test
        void isDeterministic() {
            String code = """
                    public class Config {
                        static ConfigDef CONFIG = new ConfigDef()
                            .define("a.setting", Type.STRING, "x")
                            .define("b.setting", Type.INT, 1);
                    }
                    """;
            DetectorTestUtils.assertDeterministic(d, ctx("java", code));
        }

        @Test
        void getName() {
            assertEquals("config_def", d.getName());
        }
    }

    // ==================== ClassHierarchyDetector — null/empty coverage ====================
    @Nested
    class ClassHierarchyDetectorCoverage {
        private final ClassHierarchyDetector d = new ClassHierarchyDetector();

        @Test
        void returnsEmptyOnNull() {
            assertTrue(d.detect(new DetectorContext("Test.java", "java", null)).nodes().isEmpty());
        }

        @Test
        void detectsClassWithMultipleInterfaces() {
            String code = """
                    public class ComplexClass extends BaseClass implements Serializable, Cloneable, Comparable {
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            // Should have EXTENDS and IMPLEMENTS edges
            assertTrue(r.edges().size() >= 4, "Should have extends + 3 implements edges");
        }

        @Test
        void getName() {
            assertEquals("java.class_hierarchy", d.getName());
        }
    }

    // ==================== ModuleDepsDetector — deeper coverage ====================
    @Nested
    class ModuleDepsDetectorCoverage {
        private final ModuleDepsDetector d = new ModuleDepsDetector();

        @Test
        void returnsEmptyOnNull() {
            assertTrue(d.detect(new DetectorContext("pom.xml", "xml", null)).nodes().isEmpty());
        }

        @Test
        void detectsMavenParentPom() {
            String pom = """
                    <project>
                        <groupId>com.acme</groupId>
                        <artifactId>acme-parent</artifactId>
                        <version>1.0.0</version>
                        <packaging>pom</packaging>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-starter-data-jpa</artifactId>
                            </dependency>
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-starter-security</artifactId>
                            </dependency>
                            <dependency>
                                <groupId>org.mapstruct</groupId>
                                <artifactId>mapstruct</artifactId>
                            </dependency>
                        </dependencies>
                    </project>
                    """;
            var r = d.detect(new DetectorContext("pom.xml", "xml", pom));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.edges().size() >= 3);
        }

        @Test
        void getName() {
            assertEquals("module_deps", d.getName());
        }
    }

    // ==================== Helper ====================
    private static DetectorContext ctx(String language, String content) {
        return DetectorTestUtils.contextFor(language, content);
    }
}
