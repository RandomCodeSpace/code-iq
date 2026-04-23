package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KafkaPythonDetectorTest {

    private final KafkaPythonDetector detector = new KafkaPythonDetector();

    @Test
    void detectsProducerAndSend() {
        String code = """
                from kafka import KafkaProducer
                producer = KafkaProducer()
                producer.send("my-topic", value=b"hello")
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("producer.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        // producer node + topic node
        assertTrue(result.nodes().stream().anyMatch(n -> n.getLabel().equals("kafka:producer")));
        assertTrue(result.nodes().stream().anyMatch(n -> n.getLabel().equals("kafka:my-topic")));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.PRODUCES));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.IMPORTS));
    }

    @Test
    void detectsConsumerAndSubscribe() {
        String code = """
                from kafka import KafkaConsumer
                consumer = KafkaConsumer()
                consumer.subscribe(["events"])
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("consumer.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getLabel().equals("kafka:consumer")));
        assertTrue(result.nodes().stream().anyMatch(n -> n.getLabel().equals("kafka:events")));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CONSUMES));
    }

    @Test
    void noMatchOnUnrelatedCode() {
        String code = """
                def process_data(data):
                    return data.upper()
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
        assertEquals(0, result.edges().size());
    }

    @Test
    void deterministic() {
        String code = """
                from kafka import KafkaProducer
                producer = KafkaProducer()
                producer.send("topic-a", value=b"msg")
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("producer.py", "python", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
