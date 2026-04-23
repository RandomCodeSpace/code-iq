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
 * Unit tests for {@link AzureFunctionsDetector}.
 *
 * <p>Covers every trigger branch: HTTP, Service Bus queue, Service Bus topic,
 * Event Hub, Timer, CosmosDB, and the unknown-trigger fallback.
 */
class AzureFunctionsDetectorTest {

    private final AzureFunctionsDetector detector = new AzureFunctionsDetector();

    // ---------------------------------------------------------------
    // Metadata
    // ---------------------------------------------------------------

    @Test
    void getName_returnsAzureFunctions() {
        assertEquals("azure_functions", detector.getName());
    }

    @Test
    void getSupportedLanguages_coversAllDeclaredLanguages() {
        Set<String> langs = detector.getSupportedLanguages();
        assertTrue(langs.containsAll(Set.of("java", "csharp", "typescript", "javascript")));
    }

    // ---------------------------------------------------------------
    // Early exits
    // ---------------------------------------------------------------

    @Test
    void emptyContent_returnsEmpty() {
        DetectorContext ctx = DetectorTestUtils.contextFor("Fn.java", "java", "");
        DetectorResult r = detector.detect(ctx);
        assertTrue(r.nodes().isEmpty());
    }

    @Test
    void nullContent_returnsEmpty() {
        DetectorContext ctx = new DetectorContext("Fn.java", "java", null);
        DetectorResult r = detector.detect(ctx);
        assertTrue(r.nodes().isEmpty());
    }

    @Test
    void fileWithoutFunctionNameOrHttpTrigger_returnsEmpty() {
        String code = """
                package app;
                public class Plain {
                    public void run() {}
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("Plain.java", "java", code);
        DetectorResult r = detector.detect(ctx);
        assertTrue(r.nodes().isEmpty());
    }

    // ---------------------------------------------------------------
    // HTTP trigger → AZURE_FUNCTION + ENDPOINT + EXPOSES edge
    // ---------------------------------------------------------------

    @Test
    void httpTrigger_producesFunctionEndpointAndEdge() {
        String code = """
                package app;
                public class HelloFn {
                    @FunctionName("hello")
                    @HttpTrigger(name = "req")
                    public String run() { return "hi"; }
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("HelloFn.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        // One AZURE_FUNCTION + one ENDPOINT node
        assertThat(r.nodes()).anyMatch(n -> n.getKind() == NodeKind.AZURE_FUNCTION
                && "hello".equals(n.getLabel())
                && "http".equals(n.getProperties().get("trigger_type")));
        assertThat(r.nodes()).anyMatch(n -> n.getKind() == NodeKind.ENDPOINT
                && Boolean.TRUE.equals(n.getProperties().get("http_trigger")));

        // EXPOSES edge from function to endpoint
        assertThat(r.edges()).anyMatch(e -> e.getKind() == EdgeKind.EXPOSES);
    }

    // ---------------------------------------------------------------
    // Service Bus Queue trigger
    // ---------------------------------------------------------------

    @Test
    void serviceBusQueueTrigger_producesQueueAndTriggersEdge() {
        String code = """
                package app;
                public class OrdersFn {
                    @FunctionName("onOrder")
                    public void run(
                        @ServiceBusQueueTrigger(name = "msg", queueName = "orders-q", connection = "c") String msg
                    ) {}
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("OrdersFn.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes()).anyMatch(n -> n.getKind() == NodeKind.AZURE_FUNCTION
                && "serviceBusQueue".equals(n.getProperties().get("trigger_type"))
                && "orders-q".equals(n.getProperties().get("queue_name")));
        assertThat(r.nodes()).anyMatch(n -> n.getKind() == NodeKind.QUEUE
                && "azure:servicebus:queue:orders-q".equals(n.getId()));
        assertThat(r.edges()).anyMatch(e -> e.getKind() == EdgeKind.TRIGGERS);
    }

    // ---------------------------------------------------------------
    // Service Bus Topic trigger
    // ---------------------------------------------------------------

    @Test
    void serviceBusTopicTrigger_producesTopicAndTriggersEdge() {
        String code = """
                package app;
                public class EventsFn {
                    @FunctionName("onEvent")
                    public void run(
                        @ServiceBusTopicTrigger(name = "msg", topicName = "events-t", subscriptionName = "s") String msg
                    ) {}
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("EventsFn.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes()).anyMatch(n -> n.getKind() == NodeKind.AZURE_FUNCTION
                && "serviceBusTopic".equals(n.getProperties().get("trigger_type")));
        assertThat(r.nodes()).anyMatch(n -> n.getKind() == NodeKind.TOPIC
                && "azure:servicebus:topic:events-t".equals(n.getId()));
    }

    // ---------------------------------------------------------------
    // Event Hub trigger
    // ---------------------------------------------------------------

    @Test
    void eventHubTrigger_producesHubNodeAndTriggersEdge() {
        String code = """
                package app;
                public class MetricsFn {
                    @FunctionName("onMetric")
                    public void run(
                        @EventHubTrigger(name = "m", eventHubName = "metrics-hub", connection = "c") String m
                    ) {}
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("MetricsFn.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes()).anyMatch(n -> n.getKind() == NodeKind.AZURE_FUNCTION
                && "eventHub".equals(n.getProperties().get("trigger_type"))
                && "metrics-hub".equals(n.getProperties().get("event_hub_name")));
        assertThat(r.nodes()).anyMatch(n -> "azure:eventhub:metrics-hub".equals(n.getId()));
    }

    // ---------------------------------------------------------------
    // Timer trigger
    // ---------------------------------------------------------------

    @Test
    void timerTrigger_capturesScheduleExpression() {
        String code = """
                package app;
                public class NightlyFn {
                    @FunctionName("nightly")
                    public void run(
                        @TimerTrigger(name = "t", schedule = "0 0 3 * * *") String timerInfo
                    ) {}
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("NightlyFn.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes()).anyMatch(n -> n.getKind() == NodeKind.AZURE_FUNCTION
                && "timer".equals(n.getProperties().get("trigger_type"))
                && "0 0 3 * * *".equals(n.getProperties().get("schedule")));
        // Timer trigger creates only a function node — no resource/edge
        assertTrue(r.edges().isEmpty(),
                "Timer trigger must not emit edges");
    }

    // ---------------------------------------------------------------
    // CosmosDB trigger
    // ---------------------------------------------------------------

    @Test
    void cosmosDBTrigger_producesResourceNodeAndEdge() {
        String code = """
                package app;
                public class DocsFn {
                    @FunctionName("onDoc")
                    public void run(
                        @CosmosDBTrigger(name = "items", databaseName = "db", collectionName = "c") String items
                    ) {}
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("DocsFn.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes()).anyMatch(n -> n.getKind() == NodeKind.AZURE_FUNCTION
                && "cosmosDB".equals(n.getProperties().get("trigger_type")));
        assertThat(r.nodes()).anyMatch(n -> n.getKind() == NodeKind.AZURE_RESOURCE
                && "azure:cosmos:func:onDoc".equals(n.getId()));
        assertThat(r.edges()).anyMatch(e -> e.getKind() == EdgeKind.TRIGGERS);
    }

    @Test
    void cosmosDBInputOutputBindings_alsoDetectedAsCosmos() {
        // The regex is (Trigger|Input|Output) — Input and Output should
        // also match the cosmosDB branch.
        String code = """
                package app;
                public class DocFn {
                    @FunctionName("onDoc")
                    public void run(@CosmosDBInput(name = "d") String d) {}
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("DocFn.java", "java", code);
        DetectorResult r = detector.detect(ctx);
        assertThat(r.nodes()).anyMatch(n -> "cosmosDB".equals(n.getProperties().get("trigger_type")));
    }

    // ---------------------------------------------------------------
    // Unknown trigger fallback
    // ---------------------------------------------------------------

    @Test
    void functionNameWithoutRecognizedTrigger_fallsBackToUnknown() {
        String code = """
                package app;
                public class WeirdFn {
                    @FunctionName("weirdo")
                    public String run() { return "x"; }
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("WeirdFn.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes()).anyMatch(n -> n.getKind() == NodeKind.AZURE_FUNCTION
                && "unknown".equals(n.getProperties().get("trigger_type")));
        assertTrue(r.edges().isEmpty());
    }

    // ---------------------------------------------------------------
    // FQN uses className when present, falls back to funcName otherwise
    // ---------------------------------------------------------------

    @Test
    void fqnIncludesClassNameWhenPresent() {
        String code = """
                package app;
                public class MyContainer {
                    @FunctionName("svc")
                    @HttpTrigger()
                    public String go() { return null; }
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("MyContainer.java", "java", code);
        DetectorResult r = detector.detect(ctx);

        var fn = r.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.AZURE_FUNCTION)
                .findFirst()
                .orElseThrow();
        assertEquals("MyContainer.svc", fn.getFqn());
    }

    // ---------------------------------------------------------------
    // Determinism
    // ---------------------------------------------------------------

    @Test
    void deterministic_acrossMultipleTriggers() {
        String code = """
                package app;
                public class Multi {
                    @FunctionName("a")
                    @HttpTrigger()
                    public void a() {}
                    @FunctionName("b")
                    public void b(@TimerTrigger(name = "t", schedule = "* * * * * *") String s) {}
                    @FunctionName("c")
                    public void c(@ServiceBusQueueTrigger(name = "m", queueName = "q") String s) {}
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("Multi.java", "java", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
