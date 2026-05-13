package golang

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const goWebGin = `package main

import "github.com/gin-gonic/gin"

func main() {
	r := gin.Default()
	r.Use(loggerMiddleware)
	r.GET("/users", listUsers)
	r.POST("/users", createUser)
	r.DELETE("/users/:id", deleteUser)
	r.Run()
}
`

const goWebEcho = `package main

import "github.com/labstack/echo/v4"

func main() {
	e := echo.New()
	e.GET("/health", health)
	e.PUT("/items/:id", updateItem)
}
`

const goWebChi = `package main

import "github.com/go-chi/chi/v5"

func main() {
	r := chi.NewRouter()
	r.Get("/api/items", listItems)
	r.Post("/api/items", createItem)
}
`

const goWebMux = `package main

import "github.com/gorilla/mux"

func main() {
	r := mux.NewRouter()
	r.HandleFunc("/api/products", listProducts).Methods("GET")
	r.HandleFunc("/api/products", createProduct).Methods("POST")
	r.HandleFunc("/api/admin", adminPage)
}
`

const goWebNetHttp = `package main

import "net/http"

func main() {
	http.HandleFunc("/", indexHandler)
	http.Handle("/static/", staticHandler)
	http.ListenAndServe(":8080", nil)
}
`

func TestGoWebGinEndpoints(t *testing.T) {
	d := NewWebDetector()
	r := d.Detect(&detector.Context{FilePath: "main.go", Language: "go", Content: goWebGin})
	endpoints := 0
	middlewares := 0
	for _, n := range r.Nodes {
		switch n.Kind {
		case model.NodeEndpoint:
			endpoints++
			if n.Properties["framework"] != "gin" {
				t.Errorf("framework = %v", n.Properties["framework"])
			}
		case model.NodeMiddleware:
			middlewares++
		}
	}
	if endpoints != 3 {
		t.Errorf("expected 3 endpoints, got %d", endpoints)
	}
	if middlewares != 1 {
		t.Errorf("expected 1 middleware, got %d", middlewares)
	}
}

func TestGoWebEcho(t *testing.T) {
	d := NewWebDetector()
	r := d.Detect(&detector.Context{FilePath: "main.go", Language: "go", Content: goWebEcho})
	endpoints := 0
	for _, n := range r.Nodes {
		if n.Kind == model.NodeEndpoint {
			endpoints++
			if n.Properties["framework"] != "echo" {
				t.Errorf("framework = %v, want echo", n.Properties["framework"])
			}
		}
	}
	if endpoints != 2 {
		t.Errorf("expected 2 endpoints, got %d", endpoints)
	}
}

func TestGoWebChiLowercase(t *testing.T) {
	d := NewWebDetector()
	r := d.Detect(&detector.Context{FilePath: "main.go", Language: "go", Content: goWebChi})
	endpoints := 0
	for _, n := range r.Nodes {
		if n.Kind == model.NodeEndpoint {
			endpoints++
			if n.Properties["framework"] != "chi" {
				t.Errorf("framework = %v, want chi", n.Properties["framework"])
			}
			method := n.Properties["http_method"]
			if method != "GET" && method != "POST" {
				t.Errorf("method should be uppercased, got %v", method)
			}
		}
	}
	if endpoints != 2 {
		t.Errorf("expected 2 endpoints, got %d", endpoints)
	}
}

func TestGoWebMuxWithAndWithoutMethods(t *testing.T) {
	d := NewWebDetector()
	r := d.Detect(&detector.Context{FilePath: "main.go", Language: "go", Content: goWebMux})
	endpoints := 0
	anyEndpointFound := false
	for _, n := range r.Nodes {
		if n.Kind == model.NodeEndpoint {
			endpoints++
			if n.Properties["framework"] != "mux" {
				t.Errorf("framework = %v, want mux", n.Properties["framework"])
			}
			if n.Properties["http_method"] == "ANY" {
				anyEndpointFound = true
			}
		}
	}
	if endpoints != 3 {
		t.Errorf("expected 3 endpoints, got %d", endpoints)
	}
	if !anyEndpointFound {
		t.Error("expected at least one ANY (no .Methods) endpoint")
	}
}

func TestGoWebNetHttp(t *testing.T) {
	d := NewWebDetector()
	r := d.Detect(&detector.Context{FilePath: "main.go", Language: "go", Content: goWebNetHttp})
	endpoints := 0
	for _, n := range r.Nodes {
		if n.Kind == model.NodeEndpoint && n.Properties["framework"] == "net_http" {
			endpoints++
		}
	}
	if endpoints != 2 {
		t.Errorf("expected 2 net/http endpoints, got %d", endpoints)
	}
}

func TestGoWebNegative(t *testing.T) {
	d := NewWebDetector()
	r := d.Detect(&detector.Context{FilePath: "x.go", Language: "go", Content: ""})
	if len(r.Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestGoWebDeterminism(t *testing.T) {
	d := NewWebDetector()
	ctx := &detector.Context{FilePath: "main.go", Language: "go", Content: goWebGin}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic counts")
	}
	for i := range r1.Nodes {
		if r1.Nodes[i].ID != r2.Nodes[i].ID {
			t.Fatalf("non-deterministic id at %d", i)
		}
	}
}
