package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CeleryTaskDetectorTest {

    private final CeleryTaskDetector detector = new CeleryTaskDetector();

    @Test
    void detectsTaskDefinition() {
        String code = """
                @app.task
                def send_email(to, subject):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(2, result.nodes().size());
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.QUEUE));
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.METHOD));
        assertEquals(1, result.edges().size());
        assertEquals(EdgeKind.CONSUMES, result.edges().get(0).getKind());
    }

    @Test
    void detectsTaskWithExplicitName() {
        String code = """
                @shared_task(name='emails.send')
                def send_email(to, subject):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(2, result.nodes().size());
        var queueNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.QUEUE)
                .findFirst().orElseThrow();
        assertEquals("celery:emails.send", queueNode.getLabel());
    }

    @Test
    void detectsTaskInvocation() {
        String code = """
                result = send_email.delay("user@test.com", "Hello")
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("views.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
        assertEquals(1, result.edges().size());
        assertEquals(EdgeKind.PRODUCES, result.edges().get(0).getKind());
    }

    @Test
    void noMatchOnPlainFunction() {
        String code = """
                def send_email(to, subject):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
        assertEquals(0, result.edges().size());
    }

    @Test
    void deterministic() {
        String code = """
                @app.task
                def process_data(data):
                    pass

                result = process_data.delay(42)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }

    @Test
    void detectsSharedTask() {
        String code = """
                @shared_task
                def cleanup():
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(2, result.nodes().size());
        var queueNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.QUEUE).findFirst().orElseThrow();
        assertEquals("celery", queueNode.getProperties().get("broker"));
    }

    @Test
    void taskQueueNodeHasTaskNameProperty() {
        String code = """
                @app.task
                def process(data):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var queueNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.QUEUE).findFirst().orElseThrow();
        assertEquals("process", queueNode.getProperties().get("task_name"));
        assertEquals("process", queueNode.getProperties().get("function"));
    }

    @Test
    void taskMethodNodeHasFqn() {
        String code = """
                @app.task
                def my_task():
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var methodNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.METHOD).findFirst().orElseThrow();
        assertNotNull(methodNode.getFqn());
        assertTrue(methodNode.getFqn().contains("my_task"));
    }

    @Test
    void consumesEdgeGoesFromMethodToQueue() {
        String code = """
                @app.task
                def my_task():
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var consumesEdge = result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.CONSUMES).findFirst().orElseThrow();
        assertNotNull(consumesEdge.getSourceId());
        assertTrue(consumesEdge.getSourceId().startsWith("method:"));
    }

    @Test
    void detectsApplyAsync() {
        String code = """
                send_email.apply_async(args=["user@test.com"], countdown=60)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("views.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.edges().size());
        assertEquals(EdgeKind.PRODUCES, result.edges().get(0).getKind());
    }

    @Test
    void detectsSignatureCall() {
        String code = """
                task_sig = my_task.s(arg1)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("views.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.edges().size());
        assertEquals(EdgeKind.PRODUCES, result.edges().get(0).getKind());
    }

    @Test
    void multipleTaskDefinitions() {
        String code = """
                @app.task
                def task_a():
                    pass

                @shared_task
                def task_b():
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        long queueCount = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.QUEUE).count();
        long methodCount = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.METHOD).count();
        assertEquals(2, queueCount);
        assertEquals(2, methodCount);
    }

    @Test
    void noMatchOnEmptyContent() {
        DetectorContext ctx = DetectorTestUtils.contextFor("python", "");
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
        assertEquals(0, result.edges().size());
    }

    @Test
    void explicitTaskNameOverridesFunctionName() {
        String code = """
                @app.task(name='myapp.tasks.send_notification')
                def notify_user(user_id):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var queueNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.QUEUE).findFirst().orElseThrow();
        assertEquals("celery:myapp.tasks.send_notification", queueNode.getLabel());
    }

    // ---- Regex fallback path (content > 500KB) ----

    private static String pad(String code) {
        return code + "\n" + "#\n".repeat(260_000);
    }

    @Test
    void regexFallback_detectsSharedTask() {
        String code = pad("""
                @shared_task
                def send_email(to, subject):
                    pass
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.QUEUE),
                "regex fallback should detect @shared_task queue node");
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.METHOD && "send_email".equals(n.getLabel())),
                "regex fallback should detect task method node");
    }

    @Test
    void regexFallback_detectsNamedTask() {
        String code = pad("""
                @celery_app.task(name='myapp.process_data')
                def process_data(payload):
                    pass
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.QUEUE),
                "regex fallback should detect named task queue node");
    }

    @Test
    void regexFallback_detectsTaskCall() {
        String code = pad("""
                def trigger_report():
                    generate_report.delay(user_id=42)
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.PRODUCES),
                "regex fallback should detect .delay() call as PRODUCES edge");
    }

    @Test
    void regexFallback_consumesEdgeCreated() {
        String code = pad("""
                @app.task
                def process(item):
                    pass
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CONSUMES),
                "regex fallback should create CONSUMES edge between method and queue");
    }
}
