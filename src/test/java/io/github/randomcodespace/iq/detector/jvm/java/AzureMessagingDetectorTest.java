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
 * Unit tests for {@link AzureMessagingDetector}.
 *
 * <p>Covers Service Bus (sender/receiver/processor/client) and Event Hub
 * (producer/consumer/processor) variants, including named-queue/topic
 * detection, named-eventhub detection, and generic fallbacks when no names
 * are declared in the source.
 */
class AzureMessagingDetectorTest {

    private final AzureMessagingDetector detector = new AzureMessagingDetector();

    // ---------------------------------------------------------------
    // Metadata
    // ---------------------------------------------------------------

    @Test
    void getName_returnsAzureMessaging() {
        assertEquals("azure_messaging", detector.getName());
    }

    @Test
    void getSupportedLanguages_includesJavaAndTypeScript() {
        Set<String> langs = detector.getSupportedLanguages();
        assertTrue(langs.contains("java"));
        assertTrue(langs.contains("typescript"));
        assertTrue(langs.contains("javascript"));
    }

    // ---------------------------------------------------------------
    // Early-exit: no azure keywords
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
    void fileWithoutAzureMessagingKeywords_returnsEmpty() {
        String code = """
                package app;
                public class Plain {
                    public int add(int a, int b) { return a + b; }
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("Plain.java", "java", code);
        DetectorResult r = detector.detect(ctx);
        assertTrue(r.nodes().isEmpty());
    }

    @Test
    void fileWithAzureKeywordsButNoClass_returnsEmpty() {
        // text contains ServiceBus keyword so we don't early-exit at the top,
        // but there's no "class X" declaration, so detector can't build a
        // classNodeId and must bail.
        String code = "// comment mentioning ServiceBus but no class here\n";
        DetectorContext ctx = DetectorTestUtils.contextFor("snippet.txt", "java", code);
        DetectorResult r = detector.detect(ctx);
        assertTrue(r.nodes().isEmpty());
    }

    // ---------------------------------------------------------------
    // Service Bus: named queue / sender + receiver
    // ---------------------------------------------------------------

    @Test
    void detectsServiceBusSenderWithNamedQueue() {
        String code = """
                package app;
                import com.azure.messaging.servicebus.ServiceBusSenderClient;
                public class OrdersPublisher {
                    private final ServiceBusSenderClient client =
                        new ServiceBusClientBuilder()
                            .sender()
                            .queueName("orders")
                            .buildClient();
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("OrdersPublisher.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes()).anyMatch(n ->
                n.getKind() == NodeKind.QUEUE && "azure:servicebus:orders".equals(n.getId()));
        assertThat(r.edges()).anyMatch(e ->
                e.getKind() == EdgeKind.SENDS_TO && "azure:servicebus:orders".equals(e.getTarget().getId()));
    }

    @Test
    void detectsServiceBusReceiverWithNamedQueue() {
        String code = """
                package app;
                import com.azure.messaging.servicebus.ServiceBusReceiverClient;
                public class OrdersConsumer {
                    private final ServiceBusReceiverClient client =
                        new ServiceBusClientBuilder()
                            .receiver()
                            .queueName("orders-in")
                            .buildClient();
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("OrdersConsumer.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes()).anyMatch(n ->
                n.getKind() == NodeKind.QUEUE && "azure:servicebus:orders-in".equals(n.getId()));
        assertThat(r.edges()).anyMatch(e -> e.getKind() == EdgeKind.RECEIVES_FROM);
    }

    @Test
    void detectsServiceBusProcessorTreatedAsReceiver() {
        String code = """
                package app;
                import com.azure.messaging.servicebus.ServiceBusProcessorClient;
                public class OrdersProcessor {
                    private final ServiceBusProcessorClient client =
                        new ServiceBusClientBuilder()
                            .processor()
                            .queueName("orders-stream")
                            .buildProcessorClient();
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("OrdersProcessor.java", "java", code);
        DetectorResult r = detector.detect(ctx);
        // Processor is treated as receiver → RECEIVES_FROM
        assertThat(r.edges()).anyMatch(e -> e.getKind() == EdgeKind.RECEIVES_FROM);
    }

    // ---------------------------------------------------------------
    // Service Bus: named topic
    // ---------------------------------------------------------------

    @Test
    void detectsServiceBusSenderWithNamedTopic() {
        String code = """
                package app;
                import com.azure.messaging.servicebus.ServiceBusSenderClient;
                public class EventPublisher {
                    private final ServiceBusSenderClient client =
                        new ServiceBusClientBuilder()
                            .sender()
                            .topicName("events")
                            .buildClient();
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("EventPublisher.java", "java", code);
        DetectorResult r = detector.detect(ctx);
        // Topic nodes should be NodeKind.TOPIC
        assertThat(r.nodes()).anyMatch(n ->
                n.getKind() == NodeKind.TOPIC && "azure:servicebus:events".equals(n.getId()));
        assertThat(r.edges()).anyMatch(e -> e.getKind() == EdgeKind.SENDS_TO);
    }

    // ---------------------------------------------------------------
    // Event Hub: producer / consumer / processor
    // ---------------------------------------------------------------

    @Test
    void detectsEventHubProducerWithNamedEventHub() {
        String code = """
                package app;
                import com.azure.messaging.eventhubs.EventHubProducerClient;
                public class EventHubPublisher {
                    private final EventHubProducerClient client =
                        new EventHubClientBuilder()
                            .eventHubName("telemetry")
                            .buildProducerClient();
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("EventHubPublisher.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes()).anyMatch(n ->
                n.getKind() == NodeKind.TOPIC && "azure:eventhub:telemetry".equals(n.getId()));
        assertThat(r.edges()).anyMatch(e ->
                e.getKind() == EdgeKind.SENDS_TO && "azure:eventhub:telemetry".equals(e.getTarget().getId()));
    }

    @Test
    void detectsEventHubConsumerWithNamedEventHub() {
        String code = """
                package app;
                import com.azure.messaging.eventhubs.EventHubConsumerClient;
                public class EventHubSubscriber {
                    private final EventHubConsumerClient client =
                        new EventHubClientBuilder()
                            .eventHubName("logs")
                            .buildConsumerClient();
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("EventHubSubscriber.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes()).anyMatch(n -> "azure:eventhub:logs".equals(n.getId()));
        assertThat(r.edges()).anyMatch(e -> e.getKind() == EdgeKind.RECEIVES_FROM);
    }

    @Test
    void detectsEventProcessorClientAsConsumer() {
        String code = """
                package app;
                import com.azure.messaging.eventhubs.EventProcessorClient;
                public class EventHubProcessor {
                    private final EventProcessorClient client =
                        new EventProcessorClientBuilder()
                            .eventHubName("metrics")
                            .buildEventProcessorClient();
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("EventHubProcessor.java", "java", code);
        DetectorResult r = detector.detect(ctx);
        assertThat(r.edges()).anyMatch(e -> e.getKind() == EdgeKind.RECEIVES_FROM);
    }

    // ---------------------------------------------------------------
    // Generic fallbacks: sender/receiver/client without names
    // ---------------------------------------------------------------

    @Test
    void genericSender_whenNoQueueOrTopicNameDeclared() {
        String code = """
                package app;
                import com.azure.messaging.servicebus.ServiceBusSenderClient;
                public class UnnamedSender {
                    private final ServiceBusSenderClient client = build();
                    private ServiceBusSenderClient build() { return null; }
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("UnnamedSender.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        // Falls back to generic __sender__ placeholder
        assertThat(r.nodes()).anyMatch(n -> "azure:servicebus:__sender__".equals(n.getId()));
        assertThat(r.edges()).anyMatch(e ->
                e.getKind() == EdgeKind.SENDS_TO
                        && "azure:servicebus:__sender__".equals(e.getTarget().getId()));
    }

    @Test
    void genericReceiver_whenNoQueueOrTopicNameDeclared() {
        String code = """
                package app;
                import com.azure.messaging.servicebus.ServiceBusReceiverClient;
                public class UnnamedReceiver {
                    private final ServiceBusReceiverClient client = build();
                    private ServiceBusReceiverClient build() { return null; }
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("UnnamedReceiver.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes()).anyMatch(n -> "azure:servicebus:__receiver__".equals(n.getId()));
        assertThat(r.edges()).anyMatch(e -> e.getKind() == EdgeKind.RECEIVES_FROM);
    }

    @Test
    void genericClient_whenServiceBusClientWithoutSenderOrReceiver() {
        String code = """
                package app;
                import com.azure.messaging.servicebus.ServiceBusClient;
                public class Connector {
                    private final ServiceBusClient c = new ServiceBusClient();
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("Connector.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes()).anyMatch(n -> "azure:servicebus:__client__".equals(n.getId()));
        assertThat(r.edges()).anyMatch(e -> e.getKind() == EdgeKind.CONNECTS_TO);
    }

    @Test
    void genericClient_withServiceBusClientBuilder() {
        String code = """
                package app;
                import com.azure.messaging.servicebus.ServiceBusClientBuilder;
                public class ConnectorBuilder {
                    private final ServiceBusClientBuilder b = new ServiceBusClientBuilder();
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("ConnectorBuilder.java", "java", code);
        DetectorResult r = detector.detect(ctx);
        assertThat(r.edges()).anyMatch(e -> e.getKind() == EdgeKind.CONNECTS_TO);
    }

    @Test
    void genericEventHubProducer_whenNoEventHubNameDeclared() {
        String code = """
                package app;
                import com.azure.messaging.eventhubs.EventHubProducerClient;
                public class UnnamedProducer {
                    private final EventHubProducerClient c = build();
                    private EventHubProducerClient build() { return null; }
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("UnnamedProducer.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes()).anyMatch(n -> "azure:eventhub:__producer__".equals(n.getId()));
        assertThat(r.edges()).anyMatch(e ->
                e.getKind() == EdgeKind.SENDS_TO
                        && "azure:eventhub:__producer__".equals(e.getTarget().getId()));
    }

    @Test
    void genericEventHubConsumer_whenNoEventHubNameDeclared() {
        String code = """
                package app;
                import com.azure.messaging.eventhubs.EventHubConsumerClient;
                public class UnnamedConsumer {
                    private final EventHubConsumerClient c = build();
                    private EventHubConsumerClient build() { return null; }
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("UnnamedConsumer.java", "java", code);
        DetectorResult r = detector.detect(ctx);
        assertThat(r.nodes()).anyMatch(n -> "azure:eventhub:__consumer__".equals(n.getId()));
        assertThat(r.edges()).anyMatch(e -> e.getKind() == EdgeKind.RECEIVES_FROM);
    }

    // ---------------------------------------------------------------
    // Deduplication: repeated names must not create duplicate nodes
    // ---------------------------------------------------------------

    @Test
    void sameQueueNameDeclaredTwice_producesSingleNode() {
        String code = """
                package app;
                import com.azure.messaging.servicebus.ServiceBusSenderClient;
                public class Dupes {
                    void a() { sender().queueName("orders"); }
                    void b() { sender().queueName("orders"); }
                    private ServiceBusSenderClient sender() { return null; }
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("Dupes.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        long count = r.nodes().stream()
                .filter(n -> "azure:servicebus:orders".equals(n.getId()))
                .count();
        assertEquals(1, count, "Duplicate queue names must be deduplicated");
    }

    // ---------------------------------------------------------------
    // Determinism
    // ---------------------------------------------------------------

    @Test
    void deterministic_sameInputSameOutput() {
        String code = """
                package app;
                import com.azure.messaging.servicebus.ServiceBusSenderClient;
                import com.azure.messaging.eventhubs.EventHubProducerClient;
                public class Mixed {
                    ServiceBusSenderClient sb = build().queueName("a");
                    EventHubProducerClient eh = eh().eventHubName("b");
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("Mixed.java", "java", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
