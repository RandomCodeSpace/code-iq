package io.github.randomcodespace.iq.detector.csharp;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage tests for C# detectors — branches not hit by existing tests.
 */
class CSharpDetectorsCoverageTest {

    // =====================================================================
    // CSharpStructuresDetector
    // =====================================================================
    @Nested
    class StructuresCoverage {
        private final CSharpStructuresDetector d = new CSharpStructuresDetector();

        @Test
        void detectsNamespaceNode() {
            String code = """
                    namespace MyApp.Controllers
                    {
                        public class HomeController {}
                    }
                    """;
            DetectorResult r = d.detect(ctx("csharp", code));
            assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.MODULE));
        }

        @Test
        void detectsUsingStatements() {
            String code = """
                    using System;
                    using System.Collections.Generic;
                    using Microsoft.AspNetCore.Mvc;

                    public class UserController {}
                    """;
            DetectorResult r = d.detect(ctx("csharp", code));
            // Edges for using statements
            List<io.github.randomcodespace.iq.model.CodeEdge> importEdges = r.edges().stream()
                    .filter(e -> e.getKind() == EdgeKind.IMPORTS)
                    .toList();
            assertFalse(importEdges.isEmpty());
        }

        @Test
        void detectsAbstractClass() {
            String code = """
                    public abstract class Animal
                    {
                        public abstract string Sound();
                    }
                    """;
            DetectorResult r = d.detect(ctx("csharp", code));
            assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ABSTRACT_CLASS));
        }

        @Test
        void detectsClassWithBaseClassAndInterface() {
            String code = """
                    public class UserService : BaseService, IUserService, IDisposable
                    {
                        public void Dispose() {}
                    }
                    """;
            DetectorResult r = d.detect(ctx("csharp", code));
            // Should have EXTENDS and IMPLEMENTS edges
            List<io.github.randomcodespace.iq.model.CodeEdge> extendsEdges = r.edges().stream()
                    .filter(e -> e.getKind() == EdgeKind.EXTENDS)
                    .toList();
            List<io.github.randomcodespace.iq.model.CodeEdge> implementsEdges = r.edges().stream()
                    .filter(e -> e.getKind() == EdgeKind.IMPLEMENTS)
                    .toList();
            assertFalse(extendsEdges.isEmpty());
            assertFalse(implementsEdges.isEmpty());
        }

        @Test
        void detectsApiControllerEndpoints() {
            String code = """
                    using Microsoft.AspNetCore.Mvc;

                    [ApiController]
                    [Route("api/[controller]")]
                    public class ProductsController : ControllerBase
                    {
                        [HttpGet]
                        public IActionResult GetAll() => Ok();

                        [HttpPost]
                        public IActionResult Create(Product p) => CreatedAtAction(nameof(GetAll), p);

                        [HttpGet("{id}")]
                        public IActionResult GetById(int id) => Ok();

                        [HttpDelete("{id}")]
                        public IActionResult Delete(int id) => NoContent();
                    }
                    """;
            DetectorResult r = d.detect(ctx("csharp", code));
            long endpoints = r.nodes().stream().filter(n -> n.getKind() == NodeKind.ENDPOINT).count();
            assertTrue(endpoints >= 3, "Expected at least 3 endpoint nodes, got " + endpoints);
        }

        @Test
        void detectsInterfaceWithGenericTypeParam() {
            String code = """
                    public interface IRepository<T> where T : class
                    {
                        T FindById(int id);
                    }
                    """;
            DetectorResult r = d.detect(ctx("csharp", code));
            assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.INTERFACE));
        }

        @Test
        void detectsEnum() {
            String code = """
                    public enum OrderStatus
                    {
                        Pending,
                        Processing,
                        Shipped,
                        Delivered
                    }
                    """;
            DetectorResult r = d.detect(ctx("csharp", code));
            assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENUM));
        }

        @Test
        void detectsMultipleClassesInSameFile() {
            String code = """
                    public class Request { }
                    public class Response { }
                    public class Handler { }
                    """;
            DetectorResult r = d.detect(ctx("csharp", code));
            long classes = r.nodes().stream().filter(n -> n.getKind() == NodeKind.CLASS).count();
            assertEquals(3, classes);
        }

        @Test
        void emptyContentReturnsEmpty() {
            DetectorResult r = d.detect(ctx("csharp", ""));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void nullContentReturnsEmpty() {
            DetectorResult r = d.detect(new DetectorContext("f.cs", "csharp", null));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void deterministic() {
            DetectorTestUtils.assertDeterministic(d, ctx("csharp", """
                    namespace App;
                    using System;
                    public class Foo : Bar, IBaz {}
                    public interface IBaz { void Run(); }
                    public enum Status { A, B, C }
                    """));
        }
    }

    // =====================================================================
    // CSharpEfcoreDetector
    // =====================================================================
    @Nested
    class EfcoreCoverage {
        private final CSharpEfcoreDetector d = new CSharpEfcoreDetector();

        @Test
        void detectsDbContextWithNamespaceQualifiedBase() {
            // "Microsoft.EntityFrameworkCore.DbContext"
            String code = """
                    public class OrderContext : Microsoft.EntityFrameworkCore.DbContext
                    {
                        public DbSet<Order> Orders { get; set; }
                    }
                    """;
            DetectorResult r = d.detect(ctx("csharp", code));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.REPOSITORY));
        }

        @Test
        void detectsDbSetAndCreatesQueriesEdge() {
            String code = """
                    public class AppContext : DbContext
                    {
                        public DbSet<Customer> Customers { get; set; }
                        public DbSet<Invoice> Invoices { get; set; }
                    }
                    """;
            DetectorResult r = d.detect(ctx("csharp", code));
            // Should have QUERIES edges
            List<io.github.randomcodespace.iq.model.CodeEdge> queriesEdges = r.edges().stream()
                    .filter(e -> e.getKind() == EdgeKind.QUERIES)
                    .toList();
            assertEquals(2, queriesEdges.size());
        }

        @Test
        void detectsMigrationClass() {
            String code = """
                    public class AddUserTable : Migration
                    {
                        protected override void Up(MigrationBuilder mb) {}
                        protected override void Down(MigrationBuilder mb) {}
                    }
                    """;
            DetectorResult r = d.detect(ctx("csharp", code));
            assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.MIGRATION));
            assertEquals("AddUserTable", r.nodes().stream()
                    .filter(n -> n.getKind() == NodeKind.MIGRATION)
                    .findFirst()
                    .orElseThrow()
                    .getLabel());
        }

        @Test
        void detectsCreateTableInMigration() {
            String code = """
                    public class InitialCreate : Migration
                    {
                        protected override void Up(MigrationBuilder mb)
                        {
                            mb.CreateTable(name: "Products", columns: table => new {});
                            mb.CreateTable(name: "Categories", columns: table => new {});
                        }
                    }
                    """;
            DetectorResult r = d.detect(ctx("csharp", code));
            long entities = r.nodes().stream()
                    .filter(n -> n.getKind() == NodeKind.ENTITY)
                    .count();
            assertEquals(2, entities);
        }

        @Test
        void createTableWithExistingEntityNotDuplicated() {
            // If DbSet<Products> already creates an ENTITY node, CreateTable("Products") should not duplicate
            String code = """
                    public class ShopCtx : DbContext
                    {
                        public DbSet<Products> Products { get; set; }
                    }
                    public class CreateProductsMigration : Migration
                    {
                        protected override void Up(MigrationBuilder mb)
                        {
                            mb.CreateTable(name: "Products", columns: table => new {});
                        }
                    }
                    """;
            DetectorResult r = d.detect(ctx("csharp", code));
            long productEntities = r.nodes().stream()
                    .filter(n -> n.getKind() == NodeKind.ENTITY && "Products".equals(n.getLabel()))
                    .count();
            // Should be 1 (no duplicate)
            assertEquals(1, productEntities);
        }

        @Test
        void noEfCorePatternReturnsEmpty() {
            String code = "public class RegularService { public void Run() {} }";
            DetectorResult r = d.detect(ctx("csharp", code));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void nullContentReturnsEmpty() {
            DetectorResult r = d.detect(new DetectorContext("f.cs", "csharp", null));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void deterministic() {
            DetectorTestUtils.assertDeterministic(d, ctx("csharp", """
                    public class Ctx : DbContext {
                        public DbSet<Foo> Foos { get; set; }
                    }
                    public class Init : Migration {}
                    """));
        }
    }

    // =====================================================================
    // CSharpMinimalApisDetector
    // =====================================================================
    @Nested
    class MinimalApisCoverage {
        private final CSharpMinimalApisDetector d = new CSharpMinimalApisDetector();

        @Test
        void detectsMapPatchEndpoint() {
            String code = """
                    var builder = WebApplication.CreateBuilder(args);
                    var app = builder.Build();
                    app.MapPatch("/api/users/{id}", (int id, User u) => Results.Ok(u));
                    """;
            DetectorResult r = d.detect(ctx("csharp", code));
            assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENDPOINT
                    && "PATCH".equals(n.getProperties().get("http_method"))));
        }

        @Test
        void webApplicationCreateBuilderCreatesModuleNode() {
            String code = """
                    var builder = WebApplication.CreateBuilder(args);
                    var app = builder.Build();
                    app.MapGet("/health", () => "ok");
                    """;
            DetectorResult r = d.detect(ctx("csharp", code));
            // Module node from WebApplication.CreateBuilder
            assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.MODULE));
            // Endpoint linked to module
            assertFalse(r.edges().stream().filter(e -> e.getKind() == EdgeKind.EXPOSES).toList().isEmpty());
        }

        @Test
        void detectsUseAuthenticationGuard() {
            String code = """
                    app.UseAuthentication();
                    app.UseAuthorization();
                    """;
            DetectorResult r = d.detect(ctx("csharp", code));
            long guards = r.nodes().stream().filter(n -> n.getKind() == NodeKind.GUARD).count();
            assertEquals(2, guards);
        }

        @Test
        void detectsAddAuthenticationGuard() {
            String code = """
                    builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme);
                    builder.Services.AddAuthorization();
                    """;
            DetectorResult r = d.detect(ctx("csharp", code));
            long guards = r.nodes().stream().filter(n -> n.getKind() == NodeKind.GUARD).count();
            assertEquals(2, guards);
        }

        @Test
        void endpointsWithoutBuilderHaveNoExposesEdge() {
            // No WebApplication.CreateBuilder => no module node, no EXPOSES edges
            String code = """
                    app.MapGet("/ping", () => "pong");
                    """;
            DetectorResult r = d.detect(ctx("csharp", code));
            assertEquals(1, r.nodes().size());
            assertTrue(r.edges().isEmpty());
        }

        @Test
        void detectsAllHttpMethods() {
            String code = """
                    app.MapGet("/a", () => {});
                    app.MapPost("/b", () => {});
                    app.MapPut("/c", () => {});
                    app.MapDelete("/d", () => {});
                    app.MapPatch("/e", () => {});
                    """;
            DetectorResult r = d.detect(ctx("csharp", code));
            long endpoints = r.nodes().stream().filter(n -> n.getKind() == NodeKind.ENDPOINT).count();
            assertEquals(5, endpoints);
        }

        @Test
        void noPatternReturnsEmpty() {
            String code = "var x = 1;";
            DetectorResult r = d.detect(ctx("csharp", code));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void nullContentReturnsEmpty() {
            DetectorResult r = d.detect(new DetectorContext("prog.cs", "csharp", null));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void deterministic() {
            DetectorTestUtils.assertDeterministic(d, ctx("csharp", """
                    var builder = WebApplication.CreateBuilder(args);
                    var app = builder.Build();
                    app.UseAuthentication();
                    app.MapGet("/a", () => {});
                    app.MapPost("/b", () => {});
                    """));
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static DetectorContext ctx(String language, String content) {
        return DetectorTestUtils.contextFor(language, content);
    }
}
