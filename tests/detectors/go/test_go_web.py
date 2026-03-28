"""Tests for Go web framework detector."""

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.detectors.go.go_web import GoWebDetector
from code_intelligence.models.graph import NodeKind


def _ctx(content: str, path: str = "main.go") -> DetectorContext:
    return DetectorContext(
        file_path=path, language="go", content=content.encode(), module_name="test"
    )


class TestGoWebDetector:
    def setup_method(self):
        self.detector = GoWebDetector()

    def test_name_and_languages(self):
        assert self.detector.name == "go_web"
        assert self.detector.supported_languages == ("go",)

    # --- Gin ---

    def test_detects_gin_routes(self):
        source = """\
package main

import "github.com/gin-gonic/gin"

func main() {
    r := gin.Default()
    r.GET("/users", GetUsers)
    r.POST("/users", CreateUser)
    r.PUT("/users/:id", UpdateUser)
    r.DELETE("/users/:id", DeleteUser)
}
"""
        result = self.detector.detect(_ctx(source))
        endpoints = [n for n in result.nodes if n.kind == NodeKind.ENDPOINT]
        assert len(endpoints) == 4
        methods = {n.properties["http_method"] for n in endpoints}
        assert methods == {"GET", "POST", "PUT", "DELETE"}
        for ep in endpoints:
            assert ep.properties["framework"] == "gin"

    def test_detects_gin_middleware(self):
        source = """\
package main

import "github.com/gin-gonic/gin"

func main() {
    r := gin.Default()
    r.Use(Logger)
    r.Use(Recovery)
    r.GET("/ping", Ping)
}
"""
        result = self.detector.detect(_ctx(source))
        middlewares = [n for n in result.nodes if n.kind == NodeKind.MIDDLEWARE]
        assert len(middlewares) == 2
        mw_labels = {n.label for n in middlewares}
        assert mw_labels == {"Logger", "Recovery"}

    # --- Echo ---

    def test_detects_echo_routes(self):
        source = """\
package main

import "github.com/labstack/echo/v4"

func main() {
    e := echo.New()
    e.GET("/items", GetItems)
    e.POST("/items", CreateItem)
    e.PATCH("/items/:id", PatchItem)
}
"""
        result = self.detector.detect(_ctx(source))
        endpoints = [n for n in result.nodes if n.kind == NodeKind.ENDPOINT]
        assert len(endpoints) == 3
        methods = {n.properties["http_method"] for n in endpoints}
        assert methods == {"GET", "POST", "PATCH"}
        for ep in endpoints:
            assert ep.properties["framework"] == "echo"

    # --- Chi ---

    def test_detects_chi_routes(self):
        source = """\
package main

import "github.com/go-chi/chi/v5"

func main() {
    r := chi.NewRouter()
    r.Get("/articles", ListArticles)
    r.Post("/articles", CreateArticle)
    r.Delete("/articles/{id}", DeleteArticle)
}
"""
        result = self.detector.detect(_ctx(source))
        endpoints = [n for n in result.nodes if n.kind == NodeKind.ENDPOINT]
        assert len(endpoints) == 3
        methods = {n.properties["http_method"] for n in endpoints}
        assert methods == {"GET", "POST", "DELETE"}
        for ep in endpoints:
            assert ep.properties["framework"] == "chi"

    # --- gorilla/mux ---

    def test_detects_mux_routes(self):
        source = """\
package main

import "github.com/gorilla/mux"

func main() {
    r := mux.NewRouter()
    r.HandleFunc("/products", GetProducts).Methods("GET")
    r.HandleFunc("/products", CreateProduct).Methods("POST")
}
"""
        result = self.detector.detect(_ctx(source))
        endpoints = [n for n in result.nodes if n.kind == NodeKind.ENDPOINT]
        # Should match the HandleFunc with .Methods() pattern
        mux_endpoints = [n for n in endpoints if n.properties["framework"] == "mux"]
        assert len(mux_endpoints) >= 2
        methods = {n.properties["http_method"] for n in mux_endpoints}
        assert "GET" in methods
        assert "POST" in methods

    # --- net/http ---

    def test_detects_net_http_routes(self):
        source = """\
package main

import "net/http"

func main() {
    http.HandleFunc("/hello", HelloHandler)
    http.Handle("/static/", http.FileServer(http.Dir("./static")))
    http.ListenAndServe(":8080", nil)
}
"""
        result = self.detector.detect(_ctx(source))
        endpoints = [n for n in result.nodes if n.kind == NodeKind.ENDPOINT]
        assert len(endpoints) == 2
        paths = {n.properties["path"] for n in endpoints}
        assert "/hello" in paths
        assert "/static/" in paths
        for ep in endpoints:
            assert ep.properties["framework"] == "net_http"

    # --- Negative ---

    def test_empty_returns_nothing(self):
        result = self.detector.detect(_ctx("package main\n\nfunc main() {}\n"))
        endpoints = [n for n in result.nodes if n.kind == NodeKind.ENDPOINT]
        assert len(endpoints) == 0

    def test_no_routes(self):
        source = """\
package main

import "fmt"

func main() {
    fmt.Println("no routes here")
}
"""
        result = self.detector.detect(_ctx(source))
        endpoints = [n for n in result.nodes if n.kind == NodeKind.ENDPOINT]
        assert len(endpoints) == 0

    # --- Determinism ---

    def test_determinism(self):
        source = """\
package main

import "github.com/gin-gonic/gin"

func main() {
    r := gin.Default()
    r.GET("/a", HandlerA)
    r.POST("/b", HandlerB)
    r.PUT("/c", HandlerC)
}
"""
        ctx = _ctx(source)
        r1 = self.detector.detect(ctx)
        r2 = self.detector.detect(ctx)
        assert len(r1.nodes) == len(r2.nodes)
        assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]

    def test_returns_detector_result(self):
        result = self.detector.detect(_ctx("package main\n"))
        assert isinstance(result, DetectorResult)

    def test_endpoint_node_id_format(self):
        source = 'r.GET("/users", GetUsers)\n'
        result = self.detector.detect(_ctx(source, path="server.go"))
        endpoints = [n for n in result.nodes if n.kind == NodeKind.ENDPOINT]
        assert len(endpoints) == 1
        assert endpoints[0].id.startswith("go_web:server.go:")

    def test_line_numbers_are_correct(self):
        source = """\
package main

import "github.com/gin-gonic/gin"

func main() {
    r := gin.Default()
    r.GET("/first", First)
    r.POST("/second", Second)
}
"""
        result = self.detector.detect(_ctx(source))
        endpoints = [n for n in result.nodes if n.kind == NodeKind.ENDPOINT]
        lines = sorted(n.location.line_start for n in endpoints)
        assert lines[0] == 7
        assert lines[1] == 8
