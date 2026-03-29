package io.github.randomcodespace.iq.detector.go;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GoDetectorsExtendedTest {

    // ==================== GoStructuresDetector ====================
    @Nested
    class StructuresExtended {
        private final GoStructuresDetector d = new GoStructuresDetector();

        @Test
        void detectsMethodsOnStruct() {
            String code = """
                    package service
                    type UserService struct {
                        db *sql.DB
                    }
                    func (s *UserService) GetUser(id int) User {
                        return User{}
                    }
                    func (s *UserService) DeleteUser(id int) error {
                        return nil
                    }
                    """;
            var r = d.detect(ctx("go", code));
            assertTrue(r.nodes().size() >= 3);
        }

        @Test
        void detectsStandaloneFunc() {
            String code = """
                    package main
                    func main() {
                        fmt.Println("hello")
                    }
                    func helper(x int) int {
                        return x + 1
                    }
                    """;
            var r = d.detect(ctx("go", code));
            assertTrue(r.nodes().size() >= 3); // package + 2 funcs
        }

        @Test
        void detectsImports() {
            String code = """
                    package main
                    import (
                        "fmt"
                        "net/http"
                        "github.com/gorilla/mux"
                    )
                    import "os"
                    type App struct {}
                    """;
            var r = d.detect(ctx("go", code));
            assertFalse(r.nodes().isEmpty());
            assertFalse(r.edges().isEmpty());
        }

        @Test
        void detectsInterface() {
            String code = """
                    package repo
                    type Repository interface {
                        FindAll() []Entity
                        FindByID(id int) Entity
                    }
                    """;
            var r = d.detect(ctx("go", code));
            assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.INTERFACE));
        }

        @Test
        void emptyContentReturnsEmpty() {
            var r = d.detect(ctx("go", ""));
            assertTrue(r.nodes().isEmpty());
        }
    }

    // ==================== GoWebDetector ====================
    @Nested
    class WebExtended {
        private final GoWebDetector d = new GoWebDetector();

        @Test
        void detectsGinRoutes() {
            String code = """
                    package main
                    func setupRouter() {
                        r := gin.Default()
                        r.GET("/api/users", getUsers)
                        r.POST("/api/users", createUser)
                        r.PUT("/api/users/:id", updateUser)
                        r.DELETE("/api/users/:id", deleteUser)
                    }
                    """;
            var r = d.detect(ctx("go", code));
            assertTrue(r.nodes().size() >= 4);
        }

        @Test
        void detectsEchoRoutes() {
            String code = """
                    package main
                    func main() {
                        e := echo.New()
                        e.GET("/items", getItems)
                        e.POST("/items", createItem)
                        e.Use(middleware.Logger())
                    }
                    """;
            var r = d.detect(ctx("go", code));
            assertTrue(r.nodes().size() >= 2);
        }

        @Test
        void detectsChiRoutes() {
            String code = """
                    package main
                    func main() {
                        r := chi.NewRouter()
                        r.Get("/health", healthCheck)
                        r.Post("/webhook", handleWebhook)
                    }
                    """;
            var r = d.detect(ctx("go", code));
            assertTrue(r.nodes().size() >= 2);
        }

        @Test
        void detectsHttpHandleFunc() {
            String code = """
                    package main
                    func main() {
                        http.HandleFunc("/hello", helloHandler)
                        http.Handle("/static/", staticHandler)
                    }
                    """;
            var r = d.detect(ctx("go", code));
            assertTrue(r.nodes().size() >= 2);
        }

        @Test
        void detectsMuxRoutes() {
            String code = """
                    package main
                    func main() {
                        r := mux.NewRouter()
                        r.HandleFunc("/api/users", getUsers).Methods("GET")
                    }
                    """;
            var r = d.detect(ctx("go", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void emptyContentReturnsEmpty() {
            var r = d.detect(ctx("go", ""));
            assertTrue(r.nodes().isEmpty());
        }
    }

    // ==================== GoOrmDetector ====================
    @Nested
    class OrmExtended {
        private final GoOrmDetector d = new GoOrmDetector();

        @Test
        void detectsSqlxQueries() {
            String code = """
                    import "github.com/jmoiron/sqlx"
                    func main() {
                        db := sqlx.Connect("postgres", dsn)
                        db.Select(&users, "SELECT * FROM users")
                        db.Get(&user, "SELECT * FROM users WHERE id=$1", 1)
                        db.NamedExec("INSERT INTO users VALUES (:name)", user)
                    }
                    """;
            var r = d.detect(ctx("go", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsDatabaseSql() {
            String code = """
                    import "database/sql"
                    func main() {
                        db := sql.Open("mysql", dsn)
                        db.Query("SELECT * FROM items")
                        db.QueryRow("SELECT * FROM items WHERE id = ?", 1)
                        db.Exec("DELETE FROM items WHERE id = ?", 1)
                    }
                    """;
            var r = d.detect(ctx("go", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsGormOperations() {
            String code = """
                    import "gorm.io/gorm"
                    type Product struct {
                        gorm.Model
                        Name string
                    }
                    func main() {
                        db.AutoMigrate(&Product{})
                        db.Create(&Product{Name: "test"})
                        db.Find(&products)
                        db.Where("name = ?", "test").First(&product)
                        db.Save(&product)
                        db.Delete(&product)
                    }
                    """;
            var r = d.detect(ctx("go", code));
            assertTrue(r.nodes().size() >= 1);
        }
    }

    private static DetectorContext ctx(String language, String content) {
        return DetectorTestUtils.contextFor(language, content);
    }
}
