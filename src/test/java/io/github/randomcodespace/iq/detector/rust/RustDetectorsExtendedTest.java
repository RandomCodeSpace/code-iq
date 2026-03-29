package io.github.randomcodespace.iq.detector.rust;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RustDetectorsExtendedTest {

    // ==================== RustStructuresDetector ====================
    @Nested
    class StructuresExtended {
        private final RustStructuresDetector d = new RustStructuresDetector();

        @Test
        void detectsStructWithImpl() {
            String code = """
                    pub struct User {
                        name: String,
                        age: u32,
                    }
                    impl User {
                        pub fn new(name: String) -> Self {
                            Self { name, age: 0 }
                        }
                        pub fn get_name(&self) -> &str {
                            &self.name
                        }
                    }
                    """;
            var r = d.detect(ctx("rust", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsEnumAndTrait() {
            String code = """
                    pub enum Color {
                        Red,
                        Green,
                        Blue,
                    }
                    pub trait Drawable {
                        fn draw(&self);
                    }
                    impl Drawable for Color {
                        fn draw(&self) {}
                    }
                    """;
            var r = d.detect(ctx("rust", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsModAndUse() {
            String code = """
                    mod handlers;
                    mod models;
                    use std::collections::HashMap;
                    use crate::handlers::create_user;
                    pub fn main() {}
                    """;
            var r = d.detect(ctx("rust", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void emptyReturnsEmpty() {
            var r = d.detect(ctx("rust", ""));
            assertTrue(r.nodes().isEmpty());
        }
    }

    // ==================== ActixWebDetector ====================
    @Nested
    class ActixExtended {
        private final ActixWebDetector d = new ActixWebDetector();

        @Test
        void detectsMultipleHttpMethods() {
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
            var r = d.detect(ctx("rust", code));
            assertTrue(r.nodes().size() >= 4);
        }

        @Test
        void detectsHttpServerNew() {
            String code = """
                    HttpServer::new(|| {
                        App::new()
                            .service(web::resource("/api/items"))
                    })
                    """;
            var r = d.detect(ctx("rust", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsActixWebRoute() {
            String code = """
                    #[actix_web::main]
                    async fn main() -> std::io::Result<()> {
                        HttpServer::new(|| {
                            App::new()
                                .route("/hello", web::get().to(hello))
                        })
                    }
                    """;
            var r = d.detect(ctx("rust", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsAxumRoutes() {
            String code = """
                    fn app() -> Router {
                        Router::new()
                            .route("/api/users", get(list_users))
                            .route("/api/items", post(create_item))
                            .layer(AuthLayer)
                    }
                    """;
            var r = d.detect(ctx("rust", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsServiceResource() {
            String code = """
                    #[actix_web::main]
                    async fn main() {
                        HttpServer::new(|| {
                            App::new()
                                .service(web::resource("/api/users"))
                                .service(web::resource("/api/items"))
                        })
                    }
                    """;
            var r = d.detect(ctx("rust", code));
            assertFalse(r.nodes().isEmpty());
        }
    }

    private static DetectorContext ctx(String language, String content) {
        return DetectorTestUtils.contextFor(language, content);
    }
}
