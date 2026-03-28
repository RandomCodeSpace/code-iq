"""Tests for CSharpMinimalApisDetector."""

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.detectors.csharp.csharp_minimal_apis import CSharpMinimalApisDetector
from code_intelligence.models.graph import EdgeKind, NodeKind


def _ctx(content, path="Program.cs"):
    return DetectorContext(
        file_path=path,
        language="csharp",
        content=content.encode(),
    )


class TestCSharpMinimalApisDetector:
    def setup_method(self):
        self.detector = CSharpMinimalApisDetector()

    def test_name_and_languages(self):
        assert self.detector.name == "csharp_minimal_apis"
        assert self.detector.supported_languages == ("csharp",)

    def test_returns_detector_result(self):
        ctx = _ctx("")
        result = self.detector.detect(ctx)
        assert isinstance(result, DetectorResult)

    def test_detects_mapget(self):
        src = 'app.MapGet("/users", GetUsers);\napp.MapPost("/users", CreateUser);'
        ctx = _ctx(src, "Program.cs")
        r = self.detector.detect(ctx)

        endpoints = [n for n in r.nodes if n.kind == NodeKind.ENDPOINT]
        assert len(endpoints) == 2

        get_ep = next(n for n in endpoints if n.properties["http_method"] == "GET")
        assert get_ep.properties["path"] == "/users"
        assert get_ep.label == "GET /users"

        post_ep = next(n for n in endpoints if n.properties["http_method"] == "POST")
        assert post_ep.properties["path"] == "/users"
        assert post_ep.label == "POST /users"

    def test_detects_all_http_methods(self):
        src = """\
app.MapGet("/items", ListItems);
app.MapPost("/items", CreateItem);
app.MapPut("/items/{id}", UpdateItem);
app.MapDelete("/items/{id}", DeleteItem);
app.MapPatch("/items/{id}", PatchItem);
"""
        ctx = _ctx(src)
        r = self.detector.detect(ctx)

        endpoints = [n for n in r.nodes if n.kind == NodeKind.ENDPOINT]
        methods = {n.properties["http_method"] for n in endpoints}
        assert methods == {"GET", "POST", "PUT", "DELETE", "PATCH"}

    def test_detects_route_groups(self):
        src = 'group.MapGet("/details", GetDetails);'
        ctx = _ctx(src)
        r = self.detector.detect(ctx)

        endpoints = [n for n in r.nodes if n.kind == NodeKind.ENDPOINT]
        assert len(endpoints) == 1
        assert endpoints[0].properties["http_method"] == "GET"
        assert endpoints[0].properties["path"] == "/details"

    def test_detects_builder(self):
        src = "var builder = WebApplication.CreateBuilder(args);"
        ctx = _ctx(src)
        r = self.detector.detect(ctx)

        modules = [n for n in r.nodes if n.kind == NodeKind.MODULE]
        assert len(modules) == 1
        assert "WebApplication" in modules[0].label
        assert modules[0].properties["framework"] == "dotnet_minimal_api"

    def test_detects_auth_middleware(self):
        src = """\
app.UseAuthentication();
app.UseAuthorization();
builder.Services.AddAuthentication();
"""
        ctx = _ctx(src)
        r = self.detector.detect(ctx)

        guards = [n for n in r.nodes if n.kind == NodeKind.GUARD]
        assert len(guards) == 3
        labels = {n.label for n in guards}
        assert "UseAuthentication" in labels
        assert "UseAuthorization" in labels
        assert "AddAuthentication" in labels

    def test_detects_di_registration(self):
        src = """\
builder.Services.AddScoped<IUserService, UserService>();
builder.Services.AddTransient<IEmailService, EmailService>();
builder.Services.AddSingleton<ICacheService, CacheService>();
"""
        ctx = _ctx(src)
        r = self.detector.detect(ctx)

        di_edges = [e for e in r.edges if e.kind == EdgeKind.DEPENDS_ON]
        assert len(di_edges) == 3

        lifetimes = {e.properties["lifetime"] for e in di_edges}
        assert lifetimes == {"scoped", "transient", "singleton"}

        # Check source->target mapping
        scoped = next(e for e in di_edges if e.properties["lifetime"] == "scoped")
        assert "UserService" in scoped.source
        assert "IUserService" in scoped.target

    def test_detects_di_self_registration(self):
        src = "builder.Services.AddScoped<MyService>();"
        ctx = _ctx(src)
        r = self.detector.detect(ctx)

        di_edges = [e for e in r.edges if e.kind == EdgeKind.DEPENDS_ON]
        assert len(di_edges) == 1
        assert "MyService" in di_edges[0].target

    def test_endpoint_links_to_app_module(self):
        src = """\
var builder = WebApplication.CreateBuilder(args);
var app = builder.Build();
app.MapGet("/health", () => "ok");
"""
        ctx = _ctx(src)
        r = self.detector.detect(ctx)

        exposes_edges = [e for e in r.edges if e.kind == EdgeKind.EXPOSES]
        assert len(exposes_edges) == 1
        assert exposes_edges[0].source == "dotnet:Program.cs:app"

    def test_empty_returns_empty(self):
        ctx = _ctx("")
        r = self.detector.detect(ctx)
        assert r.nodes == []
        assert r.edges == []

    def test_determinism(self):
        src = 'app.MapGet("/a", A);\napp.MapPost("/b", B);'
        ctx = _ctx(src)
        r1 = self.detector.detect(ctx)
        r2 = self.detector.detect(ctx)
        assert len(r1.nodes) == len(r2.nodes)
        assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]
        assert len(r1.edges) == len(r2.edges)
        assert [(e.source, e.target, e.kind) for e in r1.edges] == [
            (e.source, e.target, e.kind) for e in r2.edges
        ]

    def test_full_program(self):
        src = """\
var builder = WebApplication.CreateBuilder(args);
builder.Services.AddAuthentication();
builder.Services.AddAuthorization();
builder.Services.AddScoped<IUserRepo, UserRepo>();

var app = builder.Build();
app.UseAuthentication();
app.UseAuthorization();

app.MapGet("/users", GetUsers);
app.MapPost("/users", CreateUser);
app.MapDelete("/users/{id}", DeleteUser);
"""
        ctx = _ctx(src)
        r = self.detector.detect(ctx)

        modules = [n for n in r.nodes if n.kind == NodeKind.MODULE]
        assert len(modules) == 1

        endpoints = [n for n in r.nodes if n.kind == NodeKind.ENDPOINT]
        assert len(endpoints) == 3

        guards = [n for n in r.nodes if n.kind == NodeKind.GUARD]
        assert len(guards) == 4  # 2 Use + 2 Add

        di_edges = [e for e in r.edges if e.kind == EdgeKind.DEPENDS_ON]
        assert len(di_edges) == 1
