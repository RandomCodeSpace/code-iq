package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FastAPIRouteDetectorTest {

    private final FastAPIRouteDetector detector = new FastAPIRouteDetector();

    @Test
    void detectsGetRoute() {
        String code = """
                @app.get("/items")
                async def list_items():
                    return []
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals(NodeKind.ENDPOINT, result.nodes().get(0).getKind());
        assertEquals("GET /items", result.nodes().get(0).getLabel());
        assertEquals("fastapi", result.nodes().get(0).getProperties().get("framework"));
    }

    @Test
    void detectsPostRoute() {
        String code = """
                @router.post("/items")
                async def create_item(item: Item):
                    return item
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("POST /items", result.nodes().get(0).getLabel());
    }

    @Test
    void detectsRouteWithPrefix() {
        String code = """
                router = APIRouter(prefix="/api/v1")

                @router.get("/users")
                def list_users():
                    return []
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("GET /api/v1/users", result.nodes().get(0).getLabel());
    }

    @Test
    void noMatchOnPlainFunction() {
        String code = """
                def get_users():
                    return []
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
    }

    @Test
    void deterministic() {
        String code = """
                @app.get("/items")
                async def list_items():
                    return []

                @app.post("/items")
                async def create_item():
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }

    @Test
    void detectsPutRoute() {
        String code = """
                @router.put("/items/{id}")
                async def update_item(id: int):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("PUT /items/{id}", result.nodes().get(0).getLabel());
        assertEquals("PUT", result.nodes().get(0).getProperties().get("http_method"));
    }

    @Test
    void detectsDeleteRoute() {
        String code = """
                @app.delete("/items/{id}")
                async def delete_item(id: int):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("DELETE /items/{id}", result.nodes().get(0).getLabel());
    }

    @Test
    void detectsPatchRoute() {
        String code = """
                @app.patch("/items/{id}")
                async def patch_item(id: int):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("PATCH /items/{id}", result.nodes().get(0).getLabel());
    }

    @Test
    void routeHasProtocolRest() {
        String code = """
                @app.get("/health")
                def health():
                    return {"status": "ok"}
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals("REST", result.nodes().get(0).getProperties().get("protocol"));
    }

    @Test
    void routeHasRouterName() {
        String code = """
                @myrouter.get("/api/data")
                def get_data():
                    return {}
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals("myrouter", result.nodes().get(0).getProperties().get("router"));
    }

    @Test
    void routeWithPrefixCombinesFullPath() {
        String code = """
                router = APIRouter(prefix="/v2")

                @router.post("/articles")
                def create_article():
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals("POST /v2/articles", result.nodes().get(0).getLabel());
        assertEquals("/v2/articles", result.nodes().get(0).getProperties().get("path_pattern"));
    }

    @Test
    void noMatchOnEmptyContent() {
        DetectorContext ctx = DetectorTestUtils.contextFor("python", "");
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
    }

    @Test
    void fqnIncludesFunctionName() {
        String code = """
                @app.get("/ping")
                def ping():
                    return "pong"
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("routes.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertNotNull(result.nodes().get(0).getFqn());
        assertTrue(result.nodes().get(0).getFqn().contains("ping"));
    }

    @Test
    void multipleRoutes() {
        String code = """
                @app.get("/a")
                async def route_a(): pass

                @app.post("/b")
                async def route_b(): pass

                @app.delete("/c")
                async def route_c(): pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(3, result.nodes().size());
    }

    // ---- Regex fallback path (content > 500KB) ----

    private static String pad(String code) {
        return code + "\n" + "#\n".repeat(260_000);
    }

    @Test
    void regexFallback_detectsGetRoute() {
        String code = pad("""
                @router.get('/items')
                async def list_items():
                    return []
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("api/items.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENDPOINT
                && "GET".equals(n.getProperties().get("http_method"))),
                "regex fallback should detect @router.get endpoint");
        assertEquals("fastapi", result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENDPOINT).findFirst().orElseThrow()
                .getProperties().get("framework"));
    }

    @Test
    void regexFallback_detectsPostRoute() {
        String code = pad("""
                @app.post('/users')
                async def create_user(user: UserCreate):
                    return user
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("api/users.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENDPOINT
                && "POST".equals(n.getProperties().get("http_method"))),
                "regex fallback should detect @app.post endpoint");
    }

    @Test
    void regexFallback_appliesRouterPrefix() {
        String code = pad("""
                api_router = APIRouter(prefix="/api/v1")

                @api_router.get('/health')
                async def health():
                    return {"status": "ok"}
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("api/health.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENDPOINT
                && n.getProperties().get("path_pattern") != null
                && n.getProperties().get("path_pattern").toString().contains("/health")),
                "regex fallback should detect route with prefix applied");
    }

    @Test
    void regexFallback_noMatch_returnsEmpty() {
        String code = pad("""
                def helper():
                    return True
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("utils.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().stream().filter(n -> n.getKind() == NodeKind.ENDPOINT).count());
    }
}
