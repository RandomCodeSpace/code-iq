package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlaskRouteDetectorTest {

    private final FlaskRouteDetector detector = new FlaskRouteDetector();

    @Test
    void detectsSimpleRoute() {
        String code = """
                @app.route('/hello')
                def hello():
                    return 'Hello'
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals(NodeKind.ENDPOINT, result.nodes().get(0).getKind());
        assertEquals("GET /hello", result.nodes().get(0).getLabel());
        assertEquals("flask", result.nodes().get(0).getProperties().get("framework"));
        assertEquals(1, result.edges().size());
        assertEquals(EdgeKind.EXPOSES, result.edges().get(0).getKind());
    }

    @Test
    void detectsRouteWithMethods() {
        String code = """
                @app.route('/items', methods=['GET', 'POST'])
                def items():
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(2, result.nodes().size());
        assertTrue(result.nodes().stream().anyMatch(n -> n.getLabel().equals("GET /items")));
        assertTrue(result.nodes().stream().anyMatch(n -> n.getLabel().equals("POST /items")));
    }

    @Test
    void noMatchOnNonRoute() {
        String code = """
                def hello():
                    return 'world'
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
    }

    @Test
    void deterministic() {
        String code = """
                @app.route('/hello')
                def hello():
                    return 'Hello'

                @bp.route('/items', methods=['GET', 'POST'])
                def items():
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }

    @Test
    void detectsBlueprintRoute() {
        String code = """
                @bp.route('/users')
                def list_users():
                    return []
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("GET /users", result.nodes().get(0).getLabel());
        assertEquals("bp", result.nodes().get(0).getProperties().get("blueprint"));
    }

    @Test
    void routeHasProtocolRest() {
        String code = """
                @app.route('/api/data')
                def data():
                    return {}
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals("REST", result.nodes().get(0).getProperties().get("protocol"));
    }

    @Test
    void routeHasHttpMethodProperty() {
        String code = """
                @app.route('/submit', methods=['POST'])
                def submit():
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals("POST", result.nodes().get(0).getProperties().get("http_method"));
    }

    @Test
    void routeHasPathPattern() {
        String code = """
                @app.route('/user/<int:id>')
                def user(id):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals("/user/<int:id>", result.nodes().get(0).getProperties().get("path_pattern"));
    }

    @Test
    void exposesEdgeSourceIsBlueprint() {
        String code = """
                @app.route('/ping')
                def ping():
                    return 'pong'
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        var edge = result.edges().get(0);
        assertTrue(edge.getSourceId().contains("app"));
    }

    @Test
    void multipleMethodsGenerateMultipleNodes() {
        String code = """
                @api.route('/resource', methods=['GET', 'PUT', 'DELETE'])
                def resource():
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(3, result.nodes().size());
        assertEquals(3, result.edges().size());
    }

    @Test
    void noMatchOnEmptyContent() {
        DetectorContext ctx = DetectorTestUtils.contextFor("python", "");
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
        assertEquals(0, result.edges().size());
    }

    @Test
    void fqnIncludesFunctionName() {
        String code = """
                @app.route('/health')
                def health_check():
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("api.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertNotNull(result.nodes().get(0).getFqn());
        assertTrue(result.nodes().get(0).getFqn().contains("health_check"));
    }

    @Test
    void defaultMethodIsGet() {
        String code = """
                @app.route('/list')
                def list_items():
                    return []
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals("GET", result.nodes().get(0).getProperties().get("http_method"));
    }

    // ---- Regex fallback path (content > 500KB) ----

    private static String pad(String code) {
        return code + "\n" + "#\n".repeat(260_000);
    }

    @Test
    void regexFallback_detectsRoute() {
        String code = pad("""
                @bp.route('/users', methods=['GET'])
                def list_users():
                    return jsonify(users)
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("api/users.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENDPOINT
                && "/users".equals(n.getProperties().get("path_pattern"))),
                "regex fallback should detect @bp.route endpoint");
        assertEquals("flask", result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENDPOINT).findFirst().orElseThrow()
                .getProperties().get("framework"));
    }

    @Test
    void regexFallback_detectsRouteWithMultipleMethods() {
        String code = pad("""
                @api.route('/items/<int:id>', methods=['GET', 'PUT'])
                def item_detail(id):
                    pass
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("api/items.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        long endpointCount = result.nodes().stream().filter(n -> n.getKind() == NodeKind.ENDPOINT).count();
        assertTrue(endpointCount >= 2, "regex fallback should detect one endpoint per HTTP method");
    }

    @Test
    void regexFallback_createsExposesEdge() {
        String code = pad("""
                @bp.route('/ping')
                def ping():
                    return 'pong'
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("api/health.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.EXPOSES),
                "regex fallback should create EXPOSES edge from blueprint class to endpoint");
    }

    @Test
    void regexFallback_noMatch_returnsEmpty() {
        String code = pad("""
                def plain_function():
                    return True
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("utils.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().stream().filter(n -> n.getKind() == NodeKind.ENDPOINT).count());
    }
}
