package io.github.randomcodespace.iq.detector.jvm.java;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ActiveMqDetector}. Covers ActiveMQ Classic and
 * Artemis flavour disambiguation, named queue/topic instantiation, common
 * transport URLs, the JMS-style {@code session.createQueue/createTopic}
 * API, Spring Boot {@code spring.activemq.*} / {@code spring.artemis.*}
 * config-key forms, and determinism on a mixed fixture.
 */
class ActiveMqDetectorTest {

    private final ActiveMqDetector detector = new ActiveMqDetector();

    // ---------------------------------------------------------------
    // Metadata
    // ---------------------------------------------------------------

    @Test
    void getName_returnsActiveMq() {
        assertEquals("active_mq", detector.getName());
    }

    @Test
    void getSupportedLanguages_isJavaOnly() {
        Set<String> langs = detector.getSupportedLanguages();
        assertEquals(Set.of("java"), langs);
    }

    // ---------------------------------------------------------------
    // Early exit
    // ---------------------------------------------------------------

    @Test
    void emptyContent_returnsEmpty() {
        DetectorContext ctx = DetectorTestUtils.contextFor("Foo.java", "java", "");
        DetectorResult r = detector.detect(ctx);
        assertTrue(r.nodes().isEmpty());
        assertTrue(r.edges().isEmpty());
    }

    @Test
    void nullContent_returnsEmpty() {
        DetectorContext ctx = new DetectorContext("Foo.java", "java", null);
        DetectorResult r = detector.detect(ctx);
        assertTrue(r.nodes().isEmpty());
        assertTrue(r.edges().isEmpty());
    }

    @Test
    void fileWithoutActiveMqKeywords_returnsEmpty() {
        String code = """
                package app;
                public class Plain {
                    public int add(int a, int b) { return a + b; }
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("Plain.java", "java", code);
        DetectorResult r = detector.detect(ctx);
        assertTrue(r.nodes().isEmpty());
        assertTrue(r.edges().isEmpty());
    }

    // ---------------------------------------------------------------
    // ActiveMQ Classic: connection factory + queue + topic
    // ---------------------------------------------------------------

    @Test
    void detectsClassicConnectionFactoryWithBrokerUrl() {
        String code = """
                package app;
                import org.apache.activemq.ActiveMQConnectionFactory;
                public class OrdersClient {
                    private final ActiveMQConnectionFactory cf =
                        new ActiveMQConnectionFactory("tcp://localhost:61616");
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("OrdersClient.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        // MESSAGE_QUEUE node for the broker, broker=activemq, broker_url present
        assertThat(r.nodes()).anyMatch(n ->
                n.getKind() == NodeKind.MESSAGE_QUEUE
                        && "activemq".equals(n.getProperties().get("broker"))
                        && "tcp://localhost:61616".equals(n.getProperties().get("broker_url")));
        // CONNECTS_TO from class to broker
        assertThat(r.edges()).anyMatch(e -> e.getKind() == EdgeKind.CONNECTS_TO);
    }

    @Test
    void detectsClassicNamedQueueProducer() {
        String code = """
                package app;
                import org.apache.activemq.ActiveMQConnectionFactory;
                import org.apache.activemq.command.ActiveMQQueue;
                public class OrdersProducer {
                    private final ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory();
                    private final ActiveMQQueue q = new ActiveMQQueue("orders");
                    public void publish() { producer.send(msg); }
                    private javax.jms.MessageProducer producer;
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("OrdersProducer.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes()).anyMatch(n ->
                n.getKind() == NodeKind.QUEUE
                        && "amq:queue:activemq:orders".equals(n.getId()));
        assertThat(r.edges()).anyMatch(e ->
                e.getKind() == EdgeKind.SENDS_TO
                        && "amq:queue:activemq:orders".equals(e.getTarget().getId()));
    }

    @Test
    void detectsClassicNamedTopicConsumer() {
        String code = """
                package app;
                import org.apache.activemq.ActiveMQConnectionFactory;
                import org.apache.activemq.command.ActiveMQTopic;
                public class TickerListener {
                    private final ActiveMQTopic t = new ActiveMQTopic("ticker");
                    public void onMessage(javax.jms.Message m) { /* ... */ }
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("TickerListener.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes()).anyMatch(n ->
                n.getKind() == NodeKind.TOPIC
                        && "amq:topic:activemq:ticker".equals(n.getId()));
        assertThat(r.edges()).anyMatch(e -> e.getKind() == EdgeKind.RECEIVES_FROM);
    }

    @Test
    void detectsSessionCreateQueueWhenAmqContext() {
        String code = """
                package app;
                import org.apache.activemq.ActiveMQConnectionFactory;
                public class JmsApi {
                    public void send(javax.jms.Session s) {
                        javax.jms.Queue q = s.createQueue("workers");
                        producer.send(msg);
                    }
                    private javax.jms.MessageProducer producer;
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("JmsApi.java", "java", code);
        DetectorResult r = detector.detect(ctx);
        assertThat(r.nodes()).anyMatch(n ->
                n.getKind() == NodeKind.QUEUE
                        && "amq:queue:activemq:workers".equals(n.getId()));
    }

    // ---------------------------------------------------------------
    // ActiveMQ Artemis: distinguished by package path
    // ---------------------------------------------------------------

    @Test
    void detectsArtemisFlavourViaImport() {
        String code = """
                package app;
                import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
                public class ArtemisClient {
                    private final ActiveMQConnectionFactory cf =
                        new ActiveMQConnectionFactory("tcp://artemis:61616");
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("ArtemisClient.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes()).anyMatch(n ->
                n.getKind() == NodeKind.MESSAGE_QUEUE
                        && "activemq_artemis".equals(n.getProperties().get("broker")));
    }

    @Test
    void detectsArtemisJmsConnectionFactoryClass() {
        String code = """
                package app;
                import org.apache.activemq.artemis.api.jms.ActiveMQJMSConnectionFactory;
                public class ArtemisJms {
                    private final ActiveMQJMSConnectionFactory cf =
                        new ActiveMQJMSConnectionFactory("tcp://artemis:61616");
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("ArtemisJms.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes()).anyMatch(n ->
                n.getKind() == NodeKind.MESSAGE_QUEUE
                        && "activemq_artemis".equals(n.getProperties().get("broker"))
                        && "ActiveMQJMSConnectionFactory".equals(n.getProperties().get("factory_type")));
    }

    // ---------------------------------------------------------------
    // Transport URL variants
    // ---------------------------------------------------------------

    @Test
    void capturesFailoverTransportUrl() {
        String code = """
                package app;
                import org.apache.activemq.ActiveMQConnectionFactory;
                public class HaClient {
                    private final ActiveMQConnectionFactory cf =
                        new ActiveMQConnectionFactory("failover:(tcp://a:61616,tcp://b:61616)");
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("HaClient.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes()).anyMatch(n -> {
            Object url = n.getProperties().get("broker_url");
            return url != null && url.toString().startsWith("failover:");
        });
    }

    @Test
    void capturesPooledConnectionFactory() {
        String code = """
                package app;
                import org.apache.activemq.pool.PooledConnectionFactory;
                public class PoolClient {
                    private final PooledConnectionFactory pcf = new PooledConnectionFactory();
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("PoolClient.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes()).anyMatch(n ->
                "PooledConnectionFactory".equals(n.getProperties().get("factory_type")));
    }

    // ---------------------------------------------------------------
    // Spring Boot config keys (no class context — config files / property files)
    // ---------------------------------------------------------------

    @Test
    void detectsSpringActivemqBrokerUrl_emitsBrokerNodeOnly() {
        // application.properties content — no class declaration; detector
        // emits a broker node but no class-context CONNECTS_TO edge.
        String props = "spring.activemq.broker-url=tcp://broker:61616\n";
        DetectorContext ctx = DetectorTestUtils.contextFor("application.properties", "java", props);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes()).anyMatch(n ->
                n.getKind() == NodeKind.MESSAGE_QUEUE
                        && "activemq".equals(n.getProperties().get("broker"))
                        && "tcp://broker:61616".equals(n.getProperties().get("broker_url")));
        assertTrue(r.edges().isEmpty(),
                "No class context in a .properties file → no edges expected");
    }

    @Test
    void detectsSpringArtemisBrokerUrl() {
        String props = "spring.artemis.broker-url=tcp://artemis-broker:61616\n";
        DetectorContext ctx = DetectorTestUtils.contextFor("application.properties", "java", props);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes()).anyMatch(n ->
                n.getKind() == NodeKind.MESSAGE_QUEUE
                        && "activemq_artemis".equals(n.getProperties().get("broker")));
    }

    // ---------------------------------------------------------------
    // Negative: JMS without ActiveMQ context should NOT be claimed
    // ---------------------------------------------------------------

    @Test
    void plainJmsCreateQueue_withoutActiveMqContext_isIgnored() {
        // JmsDetector handles bare JMS; ActiveMqDetector should only claim
        // createQueue/createTopic when an ActiveMQ import or class ref is
        // present in the same file. This avoids double-counting.
        String code = """
                package app;
                public class PlainJms {
                    public void send(javax.jms.Session s) {
                        javax.jms.Queue q = s.createQueue("anywhere");
                    }
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("PlainJms.java", "java", code);
        DetectorResult r = detector.detect(ctx);
        assertTrue(r.nodes().isEmpty(),
                "createQueue without an AMQ import/class ref must not be attributed to ActiveMQ");
    }

    // ---------------------------------------------------------------
    // Determinism
    // ---------------------------------------------------------------

    @Test
    void deterministic_sameInputSameOutput() {
        String code = """
                package app;
                import org.apache.activemq.ActiveMQConnectionFactory;
                import org.apache.activemq.command.ActiveMQQueue;
                import org.apache.activemq.command.ActiveMQTopic;
                public class Mixed {
                    ActiveMQConnectionFactory cf =
                        new ActiveMQConnectionFactory("tcp://localhost:61616");
                    ActiveMQQueue q = new ActiveMQQueue("orders");
                    ActiveMQTopic t = new ActiveMQTopic("events");
                    public void publish() { producer.send(msg); }
                    public void onMessage(javax.jms.Message m) { /* ... */ }
                    private javax.jms.MessageProducer producer;
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("Mixed.java", "java", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
