package io.github.randomcodespace.iq.detector.go;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GoWebDetectorTest {

    private final GoWebDetector d = new GoWebDetector();

    @Test
    void detectsGinGetRoute() {
        String code = "r := gin.Default()\nr.GET(\"/users\", getUsers)\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENDPOINT));
        assertEquals(NodeKind.ENDPOINT, r.nodes().get(0).getKind());
    }

    @Test
    void detectsGinFramework() {
        String code = "r := gin.Default()\nr.GET(\"/ping\", pingHandler)\n";
        DetectorResult r = d.detect(ctx(code));
        var ep = r.nodes().stream().filter(n -> n.getKind() == NodeKind.ENDPOINT).findFirst().orElseThrow();
        assertEquals("gin", ep.getProperties().get("framework"));
        assertEquals("GET", ep.getProperties().get("http_method"));
        assertEquals("/ping", ep.getProperties().get("path"));
    }

    @Test
    void detectsAllGinMethods() {
        String code = """
                r := gin.New()
                r.GET("/items", list)
                r.POST("/items", create)
                r.PUT("/items/:id", update)
                r.DELETE("/items/:id", delete)
                r.PATCH("/items/:id", patch)
                """;
        DetectorResult r = d.detect(ctx(code));
        assertEquals(5, r.nodes().stream().filter(n -> n.getKind() == NodeKind.ENDPOINT).count());
    }

    @Test
    void detectsEchoRoutes() {
        String code = """
                e := echo.New()
                e.GET("/items", getItems)
                e.POST("/items", createItem)
                """;
        DetectorResult r = d.detect(ctx(code));
        assertEquals(2, r.nodes().stream().filter(n -> n.getKind() == NodeKind.ENDPOINT).count());
    }

    @Test
    void detectsEchoFramework() {
        String code = "e := echo.New()\ne.GET(\"/health\", healthCheck)\n";
        DetectorResult r = d.detect(ctx(code));
        var ep = r.nodes().stream().filter(n -> n.getKind() == NodeKind.ENDPOINT).findFirst().orElseThrow();
        assertEquals("echo", ep.getProperties().get("framework"));
    }

    @Test
    void detectsChiLowercaseRoutes() {
        String code = """
                r := chi.NewRouter()
                r.Get("/health", healthCheck)
                r.Post("/webhook", handleWebhook)
                r.Delete("/data/{id}", deleteData)
                """;
        DetectorResult r = d.detect(ctx(code));
        assertEquals(3, r.nodes().stream().filter(n -> n.getKind() == NodeKind.ENDPOINT).count());
    }

    @Test
    void detectsNetHttpHandleFunc() {
        String code = """
                func main() {
                    http.HandleFunc("/hello", helloHandler)
                    http.Handle("/static/", fs)
                }
                """;
        DetectorResult r = d.detect(ctx(code));
        assertEquals(2, r.nodes().stream().filter(n -> n.getKind() == NodeKind.ENDPOINT).count());
    }

    @Test
    void detectsMuxHandleFuncWithMethods() {
        String code = """
                r := mux.NewRouter()
                r.HandleFunc("/api/users", getUsers).Methods("GET")
                r.HandleFunc("/api/users", createUser).Methods("POST")
                """;
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().filter(n -> n.getKind() == NodeKind.ENDPOINT).count() >= 2);
    }

    @Test
    void detectsMiddlewareUse() {
        String code = """
                r := gin.Default()
                r.Use(cors)
                r.Use(authMiddleware)
                r.GET("/users", getUsers)
                """;
        DetectorResult r = d.detect(ctx(code));
        assertEquals(2, r.nodes().stream().filter(n -> n.getKind() == NodeKind.MIDDLEWARE).count());
    }

    @Test
    void noMatchOnPlainGoCode() {
        DetectorResult r = d.detect(ctx("package main\nfunc main() {}"));
        assertEquals(0, r.nodes().size());
    }

    @Test
    void emptyContentReturnsEmpty() {
        DetectorResult r = d.detect(ctx(""));
        assertTrue(r.nodes().isEmpty());
    }

    @Test
    void nullContentReturnsEmpty() {
        DetectorContext ctxNull = new DetectorContext("test.go", "go", null);
        DetectorResult r = d.detect(ctxNull);
        assertTrue(r.nodes().isEmpty());
    }

    @Test
    void returnsCorrectName() {
        assertEquals("go_web", d.getName());
    }

    @Test
    void supportedLanguagesContainsGo() {
        assertTrue(d.getSupportedLanguages().contains("go"));
    }

    @Test
    void deterministic() {
        String code = """
                r := gin.Default()
                r.Use(cors)
                r.GET("/a", a)
                r.POST("/b", b)
                r.PUT("/c/:id", c)
                r.DELETE("/d/:id", d)
                """;
        DetectorTestUtils.assertDeterministic(d, ctx(code));
    }

    private static DetectorContext ctx(String content) {
        return DetectorTestUtils.contextFor("go", content);
    }
}
