package io.github.randomcodespace.iq.detector.rust;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActixWebDetectorTest {

    private final ActixWebDetector d = new ActixWebDetector();

    @Test
    void detectsActixGetRoute() {
        String code = "#[get(\"/hello\")]\nasync fn hello() -> impl Responder {}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().size() >= 1);
        assertEquals(NodeKind.ENDPOINT, r.nodes().get(0).getKind());
    }

    @Test
    void detectsActixRouteHttpMethod() {
        String code = "#[post(\"/users\")]\nasync fn create_user() -> impl Responder {}\n";
        DetectorResult r = d.detect(ctx(code));
        var ep = r.nodes().stream().filter(n -> n.getKind() == NodeKind.ENDPOINT).findFirst().orElseThrow();
        assertEquals("POST", ep.getProperties().get("http_method"));
        assertEquals("actix_web", ep.getProperties().get("framework"));
    }

    @Test
    void detectsAllHttpMethods() {
        String code = """
                #[get("/items")]
                async fn list_items() -> impl Responder {}
                #[post("/items")]
                async fn create_item() -> impl Responder {}
                #[put("/items/{id}")]
                async fn update_item() -> impl Responder {}
                #[delete("/items/{id}")]
                async fn delete_item() -> impl Responder {}
                """;
        DetectorResult r = d.detect(ctx(code));
        assertEquals(4, r.nodes().stream().filter(n -> n.getKind() == NodeKind.ENDPOINT).count());
    }

    @Test
    void detectsHttpServerNew() {
        String code = "HttpServer::new(|| { App::new() })\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.MODULE
                && "HttpServer".equals(n.getLabel())));
    }

    @Test
    void detectsWebRoute() {
        String code = "#[actix_web::main]\nasync fn main() {\n    HttpServer::new(|| {\n        App::new().route(\"/hello\", web::get().to(hello))\n    })\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertFalse(r.nodes().isEmpty());
    }

    @Test
    void detectsServiceResource() {
        String code = "#[actix_web::main]\nasync fn main() {\n    HttpServer::new(|| {\n        App::new().service(web::resource(\"/api/users\"))\n    })\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENDPOINT));
    }

    @Test
    void detectsActixWebMainAttr() {
        String code = "#[actix_web::main]\nasync fn main() -> std::io::Result<()> {}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.MODULE));
    }

    @Test
    void detectsTokioMainAttr() {
        String code = "#[tokio::main]\nasync fn main() {}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.MODULE));
    }

    @Test
    void detectsAxumRoute() {
        String code = "fn app() -> Router {\n    Router::new().route(\"/api/users\", get(list_users))\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENDPOINT
                && "axum".equals(n.getProperties().get("framework"))));
    }

    @Test
    void detectsAxumLayer() {
        String code = "fn app() -> Router {\n    Router::new().layer(AuthLayer)\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.MIDDLEWARE));
    }

    @Test
    void noMatchOnPlainRust() {
        DetectorResult r = d.detect(ctx("fn main() {}"));
        assertEquals(0, r.nodes().size());
    }

    @Test
    void emptyContentReturnsEmpty() {
        DetectorResult r = d.detect(ctx(""));
        assertTrue(r.nodes().isEmpty());
    }

    @Test
    void nullContentReturnsEmpty() {
        DetectorContext ctxNull = new DetectorContext("test.rs", "rust", null);
        DetectorResult r = d.detect(ctxNull);
        assertTrue(r.nodes().isEmpty());
    }

    @Test
    void returnsCorrectName() {
        assertEquals("actix_web", d.getName());
    }

    @Test
    void supportedLanguagesContainsRust() {
        assertTrue(d.getSupportedLanguages().contains("rust"));
    }

    @Test
    void deterministic() {
        String code = """
                #[actix_web::main]
                async fn main() -> std::io::Result<()> {
                    HttpServer::new(|| {
                        App::new()
                            .route("/hello", web::get().to(hello))
                            .service(web::resource("/api/users"))
                    })
                    .bind("127.0.0.1:8080")?
                    .run()
                    .await
                }
                #[get("/items")]
                async fn list_items() -> impl Responder {}
                #[post("/items")]
                async fn create_item() -> impl Responder {}
                """;
        DetectorTestUtils.assertDeterministic(d, ctx(code));
    }

    private static DetectorContext ctx(String content) {
        return DetectorTestUtils.contextFor("rust", content);
    }
}
