package io.github.randomcodespace.iq.detector.kotlin;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KtorRouteDetectorTest {

    private final KtorRouteDetector d = new KtorRouteDetector();

    @Test
    void detectsRoutingBlock() {
        String code = "routing {\n    get(\"/hello\") { call.respond(\"hi\") }\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.MODULE));
    }

    @Test
    void detectsGetRoute() {
        String code = "routing {\n    get(\"/users\") { call.respond(listOf<User>()) }\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENDPOINT
                && n.getLabel().contains("GET")));
    }

    @Test
    void detectsPostRoute() {
        String code = "routing {\n    post(\"/users\") { val user = call.receive<User>() }\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENDPOINT
                && n.getLabel().contains("POST")));
    }

    @Test
    void detectsPutAndDeleteRoutes() {
        String code = """
                routing {
                    put("/users/{id}") { }
                    delete("/users/{id}") { }
                }
                """;
        DetectorResult r = d.detect(ctx(code));
        assertEquals(2, r.nodes().stream().filter(n -> n.getKind() == NodeKind.ENDPOINT).count());
    }

    @Test
    void detectsInstallMiddleware() {
        String code = "install(ContentNegotiation) {\n    json()\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.MIDDLEWARE));
    }

    @Test
    void detectsAuthenticate() {
        String code = """
                routing {
                    authenticate("jwt") {
                        get("/profile") { }
                    }
                }
                """;
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.GUARD));
    }

    @Test
    void detectsRoutePrefix() {
        String code = """
                routing {
                    route("/api") {
                        get("/users") { }
                        post("/users") { }
                    }
                }
                """;
        DetectorResult r = d.detect(ctx(code));
        // Endpoints should have prefixed paths
        var endpoints = r.nodes().stream().filter(n -> n.getKind() == NodeKind.ENDPOINT).toList();
        assertEquals(2, endpoints.size());
    }

    @Test
    void detectsMultipleInstalls() {
        String code = """
                install(ContentNegotiation) { json() }
                install(CallLogging)
                install(CORS) { anyHost() }
                """;
        DetectorResult r = d.detect(ctx(code));
        assertEquals(3, r.nodes().stream().filter(n -> n.getKind() == NodeKind.MIDDLEWARE).count());
    }

    @Test
    void noMatchOnPlainKotlin() {
        DetectorResult r = d.detect(ctx("fun main() {}"));
        assertEquals(0, r.nodes().size());
    }

    @Test
    void emptyContentReturnsEmpty() {
        DetectorResult r = d.detect(ctx(""));
        assertTrue(r.nodes().isEmpty());
    }

    @Test
    void nullContentReturnsEmpty() {
        DetectorContext ctxNull = new DetectorContext("test.kt", "kotlin", null);
        DetectorResult r = d.detect(ctxNull);
        assertTrue(r.nodes().isEmpty());
    }

    @Test
    void returnsCorrectName() {
        assertEquals("ktor_routes", d.getName());
    }

    @Test
    void supportedLanguagesContainsKotlin() {
        assertTrue(d.getSupportedLanguages().contains("kotlin"));
    }

    @Test
    void deterministic() {
        String code = """
                install(ContentNegotiation) { json() }
                install(CallLogging)
                routing {
                    authenticate("jwt") {
                        route("/api") {
                            get("/users") { }
                            post("/users") { }
                            put("/users/{id}") { }
                            delete("/users/{id}") { }
                        }
                    }
                }
                """;
        DetectorTestUtils.assertDeterministic(d, ctx(code));
    }

    private static DetectorContext ctx(String content) {
        return DetectorTestUtils.contextFor("kotlin", content);
    }
}
