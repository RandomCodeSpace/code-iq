package io.github.randomcodespace.iq.detector.csharp;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CSharpDetectorsExtendedTest {

    // ==================== CSharpStructuresDetector ====================
    @Nested
    class StructuresExtended {
        private final CSharpStructuresDetector d = new CSharpStructuresDetector();

        @Test
        void detectsClassWithInheritance() {
            String code = """
                    namespace MyApp.Services
                    {
                        public class UserService : BaseService, IUserService
                        {
                            public void CreateUser(string name) {}
                            public void DeleteUser(int id) {}
                        }
                    }
                    """;
            var r = d.detect(ctx("csharp", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsPartialClass() {
            String code = """
                    public partial class UserService : IService
                    {
                        public void Save(User user) {}
                    }
                    """;
            var r = d.detect(ctx("csharp", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsInterface() {
            String code = """
                    public interface IRepository<T>
                    {
                        T FindById(int id);
                        IEnumerable<T> FindAll();
                    }
                    """;
            var r = d.detect(ctx("csharp", code));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.INTERFACE));
        }

        @Test
        void detectsStructAndEnum() {
            String code = """
                    public struct Vector2D
                    {
                        public double X;
                        public double Y;
                    }
                    public enum Status
                    {
                        Active,
                        Inactive,
                        Pending
                    }
                    """;
            var r = d.detect(ctx("csharp", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsAbstractAndSealed() {
            String code = """
                    public abstract class Shape
                    {
                        public abstract double Area();
                    }
                    public sealed class Circle : Shape
                    {
                        public override double Area() => Math.PI * R * R;
                    }
                    """;
            var r = d.detect(ctx("csharp", code));
            assertTrue(r.nodes().size() >= 2);
        }

        @Test
        void detectsStaticClass() {
            String code = """
                    public static class StringExtensions
                    {
                        public static string Capitalize(this string s) => s;
                    }
                    """;
            var r = d.detect(ctx("csharp", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void emptyReturnsEmpty() {
            var r = d.detect(ctx("csharp", ""));
            assertTrue(r.nodes().isEmpty());
        }
    }

    // ==================== CSharpEfcoreDetector ====================
    @Nested
    class EfcoreExtended {
        private final CSharpEfcoreDetector d = new CSharpEfcoreDetector();

        @Test
        void detectsDbContext() {
            String code = """
                    public class AppDbContext : DbContext
                    {
                        public DbSet<User> Users { get; set; }
                        public DbSet<Order> Orders { get; set; }
                        protected override void OnModelCreating(ModelBuilder modelBuilder) {}
                    }
                    """;
            var r = d.detect(ctx("csharp", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsMultipleDbSets() {
            String code = """
                    public class ShopContext : DbContext
                    {
                        public DbSet<Product> Products { get; set; }
                        public DbSet<Category> Categories { get; set; }
                        public DbSet<Review> Reviews { get; set; }
                    }
                    """;
            var r = d.detect(ctx("csharp", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsMigrations() {
            String code = """
                    public class InitialCreate : Migration
                    {
                        protected override void Up(MigrationBuilder migrationBuilder)
                        {
                            migrationBuilder.CreateTable("Users", table => new {});
                        }
                    }
                    """;
            var r = d.detect(ctx("csharp", code));
            assertFalse(r.nodes().isEmpty());
        }
    }

    // ==================== CSharpMinimalApisDetector ====================
    @Nested
    class MinimalApisExtended {
        private final CSharpMinimalApisDetector d = new CSharpMinimalApisDetector();

        @Test
        void detectsMinimalApiEndpoints() {
            String code = """
                    app.MapGet("/api/users", () => Results.Ok(users));
                    app.MapPost("/api/users", (User user) => Results.Created($"/api/users/{user.Id}", user));
                    app.MapPut("/api/users/{id}", (int id, User user) => Results.Ok(user));
                    app.MapDelete("/api/users/{id}", (int id) => Results.NoContent());
                    """;
            var r = d.detect(ctx("csharp", code));
            assertTrue(r.nodes().size() >= 4);
        }

        @Test
        void detectsMinimalApiWithAuth() {
            String code = """
                    var builder = WebApplication.CreateBuilder(args);
                    builder.Services.AddAuthentication();
                    builder.Services.AddAuthorization();
                    var app = builder.Build();
                    app.UseAuthentication();
                    app.UseAuthorization();
                    app.MapGet("/secure", () => "secret");
                    """;
            var r = d.detect(ctx("csharp", code));
            assertFalse(r.nodes().isEmpty());
        }
    }

    private static DetectorContext ctx(String language, String content) {
        return DetectorTestUtils.contextFor(language, content);
    }
}
