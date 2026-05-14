package rust

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const actixSource = `use actix_web::{web, HttpServer, App};

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    HttpServer::new(|| {
        App::new()
            .route("/health", web::get().to(health))
            .service(web::resource("/users"))
    }).bind("0.0.0.0:8080")?.run().await
}

#[get("/items")]
async fn list_items() -> &'static str { "ok" }

#[post("/items")]
async fn create_item() -> &'static str { "ok" }
`

const axumSource = `use axum::{Router, routing::get, routing::post};

fn make_router() -> Router {
    Router::new()
        .route("/health", get(health))
        .route("/users", post(create_user))
        .layer(TraceLayer::new_for_http())
}
`

func TestActixWebPositive(t *testing.T) {
	d := NewActixWebDetector()
	r := d.Detect(&detector.Context{FilePath: "src/main.rs", Language: "rust", Content: actixSource})
	endpoints := 0
	modules := 0
	for _, n := range r.Nodes {
		if n.Kind == model.NodeEndpoint && n.Properties["framework"] == "actix_web" {
			endpoints++
		}
		if n.Kind == model.NodeModule {
			modules++
		}
	}
	// 2 attribute macros (get/post) + 1 route web::get + 1 web::resource = 4 endpoints
	if endpoints != 4 {
		t.Errorf("expected 4 actix endpoints, got %d", endpoints)
	}
	// HttpServer::new + #[actix_web::main] = 2 modules
	if modules != 2 {
		t.Errorf("expected 2 modules, got %d", modules)
	}
}

func TestActixWebAxum(t *testing.T) {
	d := NewActixWebDetector()
	r := d.Detect(&detector.Context{FilePath: "src/router.rs", Language: "rust", Content: axumSource})
	axumEndpoints := 0
	middlewares := 0
	for _, n := range r.Nodes {
		if n.Kind == model.NodeEndpoint && n.Properties["framework"] == "axum" {
			axumEndpoints++
		}
		if n.Kind == model.NodeMiddleware {
			middlewares++
		}
	}
	if axumEndpoints != 2 {
		t.Errorf("expected 2 axum endpoints, got %d", axumEndpoints)
	}
	if middlewares != 1 {
		t.Errorf("expected 1 middleware (layer), got %d", middlewares)
	}
}

func TestActixWebNegative(t *testing.T) {
	d := NewActixWebDetector()
	r := d.Detect(&detector.Context{
		FilePath: "x.rs", Language: "rust",
		Content: "fn main() {}",
	})
	if len(r.Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestActixWebDeterminism(t *testing.T) {
	d := NewActixWebDetector()
	ctx := &detector.Context{FilePath: "src/main.rs", Language: "rust", Content: actixSource}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic counts")
	}
}
