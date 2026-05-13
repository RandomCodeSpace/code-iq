package kotlin

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const ktorRoutesSample = `import io.ktor.server.routing.*

fun Application.module() {
    routing {
        route("/api") {
            get("/users") { }
            post("/users") { }
            authenticate("auth-jwt") {
                get("/admin") { }
            }
        }
        install(ContentNegotiation)
    }
}
`

func TestKtorRoutesPositive(t *testing.T) {
	d := NewKtorRouteDetector()
	ctx := &detector.Context{FilePath: "src/Routes.kt", Language: "kotlin", Content: ktorRoutesSample}
	r := d.Detect(ctx)
	if r == nil || len(r.Nodes) == 0 {
		t.Fatal("expected nodes")
	}
	var hasRouting, hasGET, hasAuth, hasInstall bool
	for _, n := range r.Nodes {
		switch {
		case n.Kind == model.NodeModule && n.Label == "routing":
			hasRouting = true
		case n.Kind == model.NodeEndpoint && n.Properties["http_method"] == "GET":
			hasGET = true
		case n.Kind == model.NodeGuard && n.Properties["auth_name"] == "auth-jwt":
			hasAuth = true
		case n.Kind == model.NodeMiddleware && n.Properties["feature"] == "ContentNegotiation":
			hasInstall = true
		}
	}
	if !hasRouting {
		t.Error("missing routing node")
	}
	if !hasGET {
		t.Error("missing GET endpoint node")
	}
	if !hasAuth {
		t.Error("missing authenticate guard node")
	}
	if !hasInstall {
		t.Error("missing install middleware node")
	}

	// All nodes should carry framework=ktor
	for _, n := range r.Nodes {
		if n.Properties["framework"] != "ktor" {
			t.Errorf("node %q missing framework=ktor, got %v", n.Label, n.Properties["framework"])
		}
	}
}

func TestKtorRoutesPathPrefixing(t *testing.T) {
	d := NewKtorRouteDetector()
	ctx := &detector.Context{FilePath: "src/Routes.kt", Language: "kotlin", Content: ktorRoutesSample}
	r := d.Detect(ctx)
	// `get("/users")` inside `route("/api") {` should be `/api/users`
	var hasPrefixed bool
	for _, n := range r.Nodes {
		if n.Kind == model.NodeEndpoint && n.Properties["path_pattern"] == "/api/users" {
			hasPrefixed = true
			break
		}
	}
	if !hasPrefixed {
		t.Error("expected route-prefixed endpoint /api/users")
	}
}

func TestKtorRoutesNegative(t *testing.T) {
	d := NewKtorRouteDetector()
	ctx := &detector.Context{FilePath: "src/Plain.kt", Language: "kotlin", Content: "fun main() {}"}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes on plain code, got %d", len(r.Nodes))
	}
}

// TestKtorRoutesNoFireOnPlainDSL verifies the import discriminator prevents false
// positives when plain Kotlin code uses DSL patterns that visually resemble Ktor
// (routing {}, get("/path") {}, install(...)) but has no io.ktor import.
func TestKtorRoutesNoFireOnPlainDSL(t *testing.T) {
	d := NewKtorRouteDetector()
	plainWithDSL := `package com.example

fun runTest() {
    routing { println("not ktor") }
    get("/fake") { doSomething() }
    post("/fake") { doSomething() }
    install(Something)
}
`
	ctx := &detector.Context{FilePath: "src/PlainUtils.kt", Language: "kotlin", Content: plainWithDSL}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes on plain Kotlin DSL without io.ktor import, got %d nodes", len(r.Nodes))
	}
}

// TestKtorRoutesNoFireOnFixturePlainUtils verifies zero framework emissions on
// the PlainUtils.kt fixture (stdlib only, no framework imports).
func TestKtorRoutesNoFireOnFixturePlainUtils(t *testing.T) {
	d := NewKtorRouteDetector()
	plainUtils := `package com.example.utils

fun add(a: Int, b: Int): Int = a + b

fun greet(name: String): String = "Hello, $name!"

class Counter(initial: Int) {
    private var count = initial
    fun increment() { count++ }
    fun value(): Int = count
}
`
	ctx := &detector.Context{FilePath: "src/PlainUtils.kt", Language: "kotlin", Content: plainUtils}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 framework nodes on PlainUtils.kt, got %d", len(r.Nodes))
	}
}

func TestKtorRoutesDeterminism(t *testing.T) {
	d := NewKtorRouteDetector()
	ctx := &detector.Context{FilePath: "src/Routes.kt", Language: "kotlin", Content: ktorRoutesSample}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatalf("nondeterministic node count: %d vs %d", len(r1.Nodes), len(r2.Nodes))
	}
}
