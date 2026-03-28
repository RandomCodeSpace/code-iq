"""Tests for CSharpEfcoreDetector."""

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.detectors.csharp.csharp_efcore import CSharpEfcoreDetector
from code_intelligence.models.graph import EdgeKind, NodeKind


def _ctx(content, path="AppDbContext.cs"):
    return DetectorContext(
        file_path=path,
        language="csharp",
        content=content.encode(),
    )


class TestCSharpEfcoreDetector:
    def setup_method(self):
        self.detector = CSharpEfcoreDetector()

    def test_name_and_languages(self):
        assert self.detector.name == "csharp_efcore"
        assert self.detector.supported_languages == ("csharp",)

    def test_returns_detector_result(self):
        ctx = _ctx("")
        result = self.detector.detect(ctx)
        assert isinstance(result, DetectorResult)

    def test_detects_dbcontext(self):
        src = """\
using Microsoft.EntityFrameworkCore;

public class AppDbContext : DbContext
{
    public DbSet<User> Users { get; set; }
}
"""
        ctx = _ctx(src)
        r = self.detector.detect(ctx)

        repos = [n for n in r.nodes if n.kind == NodeKind.REPOSITORY]
        assert len(repos) == 1
        assert repos[0].label == "AppDbContext"
        assert repos[0].id == "efcore:AppDbContext.cs:context:AppDbContext"
        assert repos[0].properties["framework"] == "efcore"

    def test_detects_dbset(self):
        src = """\
public class ShopContext : DbContext
{
    public DbSet<Product> Products { get; set; }
    public DbSet<Order> Orders { get; set; }
}
"""
        ctx = _ctx(src, "ShopContext.cs")
        r = self.detector.detect(ctx)

        entities = [n for n in r.nodes if n.kind == NodeKind.ENTITY]
        entity_labels = {n.label for n in entities}
        assert "Product" in entity_labels
        assert "Order" in entity_labels
        assert len(entities) == 2

        # Each entity should have a QUERIES edge from the context
        queries_edges = [e for e in r.edges if e.kind == EdgeKind.QUERIES]
        assert len(queries_edges) == 2
        for edge in queries_edges:
            assert edge.source == "efcore:ShopContext.cs:context:ShopContext"

    def test_detects_table_annotation(self):
        src = """\
public class MyContext : DbContext
{
    public DbSet<Customer> Customers { get; set; }
}

[Table("tbl_customers")]
public class Customer
{
    public int Id { get; set; }
}
"""
        ctx = _ctx(src)
        r = self.detector.detect(ctx)

        entities = [n for n in r.nodes if n.kind == NodeKind.ENTITY]
        assert len(entities) >= 1
        customer = next(n for n in entities if n.label == "Customer")
        assert customer.properties.get("table_name") == "tbl_customers"

    def test_detects_foreign_key(self):
        src = """\
public class BlogContext : DbContext
{
    public DbSet<Post> Posts { get; set; }
}

public class Post
{
    [ForeignKey("Author")]
    public int AuthorId { get; set; }
}
"""
        ctx = _ctx(src)
        r = self.detector.detect(ctx)

        fk_edges = [e for e in r.edges if e.kind == EdgeKind.DEPENDS_ON]
        assert len(fk_edges) >= 1
        assert any("Author" in e.target for e in fk_edges)

    def test_detects_fluent_api(self):
        src = """\
public class MyContext : DbContext
{
    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<Order>()
            .HasOne(o => o.Customer)
            .WithMany(c => c.Orders);
    }
}
"""
        ctx = _ctx(src)
        r = self.detector.detect(ctx)

        depends_edges = [e for e in r.edges if e.kind == EdgeKind.DEPENDS_ON]
        fluent_methods = {e.properties.get("fluent_method") for e in depends_edges if "fluent_method" in e.properties}
        assert "HasOne" in fluent_methods
        assert "WithMany" in fluent_methods

    def test_detects_migrations(self):
        src = """\
public partial class InitialCreate : Migration
{
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.CreateTable(
            name: "Users",
            columns: table => new { });
    }
}
"""
        ctx = _ctx(src, "Migrations/InitialCreate.cs")
        r = self.detector.detect(ctx)

        migrations = [n for n in r.nodes if n.kind == NodeKind.MIGRATION]
        assert len(migrations) == 1
        assert migrations[0].label == "InitialCreate"
        assert migrations[0].id == "efcore:Migrations/InitialCreate.cs:migration:InitialCreate"

        # CreateTable should produce an ENTITY node
        entities = [n for n in r.nodes if n.kind == NodeKind.ENTITY]
        assert len(entities) >= 1
        assert any(n.label == "Users" for n in entities)

    def test_empty_returns_empty(self):
        ctx = _ctx("")
        r = self.detector.detect(ctx)
        assert r.nodes == []
        assert r.edges == []

    def test_determinism(self):
        src = """\
public class AppDbContext : DbContext
{
    public DbSet<User> Users { get; set; }
    public DbSet<Role> Roles { get; set; }
}
"""
        ctx = _ctx(src)
        r1 = self.detector.detect(ctx)
        r2 = self.detector.detect(ctx)
        assert len(r1.nodes) == len(r2.nodes)
        assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]
        assert len(r1.edges) == len(r2.edges)
        assert [(e.source, e.target, e.kind) for e in r1.edges] == [
            (e.source, e.target, e.kind) for e in r2.edges
        ]

    def test_namespaced_dbcontext(self):
        src = "public class MyCtx : Microsoft.EntityFrameworkCore.DbContext {}"
        ctx = _ctx(src)
        r = self.detector.detect(ctx)
        repos = [n for n in r.nodes if n.kind == NodeKind.REPOSITORY]
        assert len(repos) == 1
        assert repos[0].label == "MyCtx"
