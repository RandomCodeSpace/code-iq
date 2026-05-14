package csharp

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const minimalApisSource = `var builder = WebApplication.CreateBuilder(args);
builder.Services.AddAuthentication();
builder.Services.AddAuthorization();
var app = builder.Build();
app.UseAuthentication();
app.UseAuthorization();
app.MapGet("/health", () => "ok");
app.MapPost("/users", CreateUser);
app.MapDelete("/users/{id}", DeleteUser);
app.Run();
`

func TestCSharpMinimalApisPositive(t *testing.T) {
	d := NewMinimalApisDetector()
	r := d.Detect(&detector.Context{FilePath: "Program.cs", Language: "csharp", Content: minimalApisSource})

	kinds := map[model.NodeKind]int{}
	for _, n := range r.Nodes {
		kinds[n.Kind]++
	}
	if kinds[model.NodeModule] != 1 {
		t.Errorf("expected 1 MODULE (web application), got %d", kinds[model.NodeModule])
	}
	if kinds[model.NodeEndpoint] != 3 {
		t.Errorf("expected 3 ENDPOINTs, got %d", kinds[model.NodeEndpoint])
	}
	// 2 UseAuth + 2 AddAuth = 4 guards
	if kinds[model.NodeGuard] != 4 {
		t.Errorf("expected 4 GUARDs, got %d", kinds[model.NodeGuard])
	}

	// EXPOSES edges: 3 endpoints from one app
	exposeEdges := 0
	for _, e := range r.Edges {
		if e.Kind == model.EdgeExposes {
			exposeEdges++
		}
	}
	if exposeEdges != 3 {
		t.Errorf("expected 3 EXPOSES edges, got %d", exposeEdges)
	}
}

func TestCSharpMinimalApisHttpMethodUppercase(t *testing.T) {
	d := NewMinimalApisDetector()
	r := d.Detect(&detector.Context{FilePath: "Program.cs", Language: "csharp", Content: minimalApisSource})
	for _, n := range r.Nodes {
		if n.Kind == model.NodeEndpoint {
			method := n.Properties["http_method"].(string)
			if method != "GET" && method != "POST" && method != "DELETE" {
				t.Errorf("unexpected http_method %q", method)
			}
		}
	}
}

func TestCSharpMinimalApisNegative(t *testing.T) {
	d := NewMinimalApisDetector()
	r := d.Detect(&detector.Context{FilePath: "x.cs", Language: "csharp", Content: "var x = 1;"})
	if len(r.Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestCSharpMinimalApisDeterminism(t *testing.T) {
	d := NewMinimalApisDetector()
	ctx := &detector.Context{FilePath: "Program.cs", Language: "csharp", Content: minimalApisSource}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
		t.Fatal("non-deterministic counts")
	}
}
