package auth

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestSessionHeaderSession(t *testing.T) {
	d := NewSessionHeaderAuthDetector()
	src := `const session = require('express-session');
app.use(session({ secret: 's' }));
`
	r := d.Detect(&detector.Context{FilePath: "app.ts", Language: "typescript", Content: src})
	hasSession := false
	for _, n := range r.Nodes {
		if n.Properties["auth_type"] == "session" {
			hasSession = true
			if n.Kind != model.NodeMiddleware {
				t.Errorf("expected MIDDLEWARE for express-session, got %v", n.Kind)
			}
		}
	}
	if !hasSession {
		t.Error("expected session guard")
	}
}

func TestSessionHeaderApiKey(t *testing.T) {
	d := NewSessionHeaderAuthDetector()
	src := `const key = req.headers['x-api-key'];
def validate_api_key(k): pass
`
	r := d.Detect(&detector.Context{FilePath: "h.ts", Language: "typescript", Content: src})
	hasApiKey := false
	for _, n := range r.Nodes {
		if n.Properties["auth_type"] == "api_key" {
			hasApiKey = true
		}
	}
	if !hasApiKey {
		t.Error("expected api_key guard")
	}
}

func TestSessionHeaderCsrf(t *testing.T) {
	d := NewSessionHeaderAuthDetector()
	src := `from django.views.decorators.csrf import csrf_exempt

@csrf_exempt
def view(request): pass
`
	r := d.Detect(&detector.Context{FilePath: "v.py", Language: "python", Content: src})
	hasCsrf := false
	for _, n := range r.Nodes {
		if n.Properties["auth_type"] == "csrf" {
			hasCsrf = true
		}
	}
	if !hasCsrf {
		t.Error("expected csrf guard")
	}
}

func TestSessionHeaderHeader(t *testing.T) {
	d := NewSessionHeaderAuthDetector()
	src := `const auth = req.headers['authorization'];`
	r := d.Detect(&detector.Context{FilePath: "h.ts", Language: "typescript", Content: src})
	hasHeader := false
	for _, n := range r.Nodes {
		if n.Properties["auth_type"] == "header" {
			hasHeader = true
		}
	}
	if !hasHeader {
		t.Error("expected header guard")
	}
}

func TestSessionHeaderUnsupportedLanguage(t *testing.T) {
	d := NewSessionHeaderAuthDetector()
	r := d.Detect(&detector.Context{
		FilePath: "x.rs", Language: "rust",
		Content: "HttpSession s;",
	})
	if len(r.Nodes) != 0 {
		t.Error("rust not supported")
	}
}

func TestSessionHeaderPreScreenSkip(t *testing.T) {
	d := NewSessionHeaderAuthDetector()
	r := d.Detect(&detector.Context{
		FilePath: "x.java", Language: "java",
		Content: "public class Foo {}",
	})
	if len(r.Nodes) != 0 {
		t.Error("pre-screen should short-circuit")
	}
}

func TestSessionHeaderDeterminism(t *testing.T) {
	d := NewSessionHeaderAuthDetector()
	ctx := &detector.Context{
		FilePath: "a.ts", Language: "typescript",
		Content: "const auth = req.headers['authorization'];",
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
}
