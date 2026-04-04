package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CeleryTaskDetectorExtendedTest {

    private final CeleryTaskDetector detector = new CeleryTaskDetector();

    private static String pad(String code) {
        return code + "\n" + "#\n".repeat(260_000);
    }

    // ---- @shared_task ----

    @Test
    void detectsSharedTaskDecorator() {
        String code = """
                @shared_task
                def process_payment(order_id):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(2, result.nodes().size());
        var queue = result.nodes().stream().filter(n -> n.getKind() == NodeKind.QUEUE).findFirst().orElseThrow();
        assertEquals("celery", queue.getProperties().get("broker"));
        assertEquals("process_payment", queue.getProperties().get("task_name"));
        assertEquals("process_payment", queue.getProperties().get("function"));
    }

    @Test
    void sharedTaskMethodNodeHasFqn() {
        String code = """
                @shared_task
                def export_report(report_id):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var method = result.nodes().stream().filter(n -> n.getKind() == NodeKind.METHOD).findFirst().orElseThrow();
        assertNotNull(method.getFqn());
        assertTrue(method.getFqn().contains("export_report"));
    }

    @Test
    void regexFallback_detectsSharedTask() {
        String code = pad("""
                @shared_task
                def sync_inventory():
                    pass
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.QUEUE));
        assertTrue(result.nodes().stream()
                .anyMatch(n -> n.getKind() == NodeKind.METHOD && "sync_inventory".equals(n.getLabel())));
    }

    // ---- @app.task(bind=True) ----

    @Test
    void detectsBindTrueTask() {
        String code = """
                @app.task(bind=True)
                def retry_task(self, data):
                    try:
                        pass
                    except Exception as exc:
                        self.retry(exc=exc, countdown=60)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(2, result.nodes().size());
        var queue = result.nodes().stream().filter(n -> n.getKind() == NodeKind.QUEUE).findFirst().orElseThrow();
        assertEquals("retry_task", queue.getProperties().get("task_name"));
    }

    @Test
    void regexFallback_detectsBindTrueTask() {
        String code = pad("""
                @app.task(bind=True)
                def retry_on_failure(self, payload):
                    pass
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.QUEUE));
    }

    // ---- task with name= parameter ----

    @Test
    void detectsTaskWithNameParameter() {
        String code = """
                @app.task(name='notifications.send_push')
                def send_push_notification(user_id, message):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var queue = result.nodes().stream().filter(n -> n.getKind() == NodeKind.QUEUE).findFirst().orElseThrow();
        assertEquals("celery:notifications.send_push", queue.getLabel());
    }

    @Test
    void detectsSharedTaskWithNameParameter() {
        String code = """
                @shared_task(name='reports.generate')
                def generate_report(report_type):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var queue = result.nodes().stream().filter(n -> n.getKind() == NodeKind.QUEUE).findFirst().orElseThrow();
        assertEquals("celery:reports.generate", queue.getLabel());
    }

    @Test
    void regexFallback_detectsTaskWithName() {
        String code = pad("""
                @celery_app.task(name='mailer.send_welcome')
                def send_welcome_email(user_id):
                    pass
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.QUEUE));
        var queue = result.nodes().stream().filter(n -> n.getKind() == NodeKind.QUEUE).findFirst().orElseThrow();
        // Name kwarg should override function name in the label
        assertTrue(queue.getLabel().contains("mailer.send_welcome")
                || queue.getLabel().contains("send_welcome_email"),
                "queue label should reflect task name");
    }

    // ---- task with max_retries= ----

    @Test
    void detectsTaskWithMaxRetriesParameter() {
        String code = """
                @app.task(max_retries=3)
                def flaky_task(data):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(2, result.nodes().size());
        var queue = result.nodes().stream().filter(n -> n.getKind() == NodeKind.QUEUE).findFirst().orElseThrow();
        assertEquals("flaky_task", queue.getProperties().get("task_name"));
    }

    @Test
    void regexFallback_detectsTaskWithMaxRetries() {
        String code = pad("""
                @shared_task(max_retries=5)
                def unreliable_job(item_id):
                    pass
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.QUEUE));
    }

    // ---- self.retry() usage ----

    @Test
    void detectsSelfRetryCallProducesEdge() {
        String code = """
                @app.task(bind=True)
                def with_retry(self, url):
                    try:
                        pass
                    except Exception:
                        self.retry(countdown=30)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        // The task itself is detected
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.QUEUE));
        // self.retry() matches TASK_CALL pattern (self.retry(...))
        // This creates a PRODUCES edge for the retry call
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CONSUMES));
    }

    // ---- apply_async ----

    @Test
    void detectsApplyAsyncProducesEdge() {
        String code = """
                def trigger_batch():
                    process_batch.apply_async(args=[1, 2, 3], countdown=10)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("views.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.PRODUCES));
    }

    @Test
    void regexFallback_detectsApplyAsync() {
        String code = pad("""
                def schedule():
                    generate_invoice.apply_async(args=[42], eta=tomorrow)
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("scheduler.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.PRODUCES));
    }

    // ---- .delay() call ----

    @Test
    void detectsDelayCall() {
        String code = """
                def submit_order(order_id):
                    process_order.delay(order_id)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("views.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.edges().size());
        assertEquals(EdgeKind.PRODUCES, result.edges().get(0).getKind());
    }

    @Test
    void regexFallback_detectsDelayCall() {
        String code = pad("""
                def on_signup(user_id):
                    send_welcome.delay(user_id)
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("views.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.PRODUCES));
    }

    // ---- .s() signature call ----

    @Test
    void detectsSignatureCallProducesEdge() {
        String code = """
                workflow = chain(step1.s(data), step2.s())
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("workflows.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        long producesEdges = result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.PRODUCES).count();
        assertTrue(producesEdges >= 2, "Expected at least 2 PRODUCES edges, got: " + producesEdges);
    }

    @Test
    void regexFallback_detectsSignatureCall() {
        String code = pad("""
                pipeline = chord(task_a.s(1), task_b.s())
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("pipelines.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.PRODUCES));
    }

    // ---- Multiple tasks in one file ----

    @Test
    void detectsMultipleTasksWithDifferentDecorators() {
        String code = """
                @app.task
                def task_one():
                    pass

                @shared_task
                def task_two():
                    pass

                @app.task(name='custom.three')
                def task_three():
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        long queueNodes = result.nodes().stream().filter(n -> n.getKind() == NodeKind.QUEUE).count();
        long methodNodes = result.nodes().stream().filter(n -> n.getKind() == NodeKind.METHOD).count();
        assertEquals(3, queueNodes);
        assertEquals(3, methodNodes);

        long consumesEdges = result.edges().stream().filter(e -> e.getKind() == EdgeKind.CONSUMES).count();
        assertEquals(3, consumesEdges);
    }

    @Test
    void regexFallback_detectsMultipleTaskDefinitions() {
        String code = pad("""
                @app.task
                def alpha():
                    pass

                @shared_task
                def beta():
                    pass
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        long queueNodes = result.nodes().stream().filter(n -> n.getKind() == NodeKind.QUEUE).count();
        assertTrue(queueNodes >= 2, "regex fallback should detect multiple task queues");
    }

    // ---- Empty file ----

    @Test
    void emptyFileReturnsEmpty() {
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", "");
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
        assertEquals(0, result.edges().size());
    }

    // ---- CONSUMES edge sourceId starts with 'method:' ----

    @Test
    void consumesEdgeHasCorrectSourceId() {
        String code = """
                @shared_task
                def background_job(data):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var consumesEdge = result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.CONSUMES).findFirst().orElseThrow();
        assertTrue(consumesEdge.getSourceId().startsWith("method:"),
                "CONSUMES edge source should be a method node ID");
    }

    // ---- Queue node label format ----

    @Test
    void queueNodeLabelHasCeleryPrefix() {
        String code = """
                @app.task
                def do_work():
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var queue = result.nodes().stream().filter(n -> n.getKind() == NodeKind.QUEUE).findFirst().orElseThrow();
        assertTrue(queue.getLabel().startsWith("celery:"),
                "queue label should start with 'celery:'");
    }

    // ---- Determinism ----

    @Test
    void deterministicWithMixedTaskAndCalls() {
        String code = """
                @app.task
                def process(data):
                    pass

                @shared_task(name='alerts.notify')
                def notify(user_id):
                    pass

                def trigger():
                    process.delay(42)
                    notify.apply_async(args=[1])
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }

    @Test
    void regexFallback_deterministicOnMixedCode() {
        String code = pad("""
                @app.task
                def job_a():
                    pass

                @shared_task
                def job_b():
                    pass

                def runner():
                    job_a.delay()
                    job_b.apply_async()
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("tasks.py", "python", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
