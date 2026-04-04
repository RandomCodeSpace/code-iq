package io.github.randomcodespace.iq.detector.go;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GoStructuresDetectorTest {

    private final GoStructuresDetector d = new GoStructuresDetector();

    @Test
    void detectsPackageNode() {
        DetectorResult r = d.detect(ctx("package main\n"));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.MODULE));
    }

    @Test
    void detectsStruct() {
        String code = """
                package models
                type User struct {
                    ID   int
                    Name string
                }
                """;
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CLASS && "User".equals(n.getLabel())));
    }

    @Test
    void detectsExportedStructFlagTrue() {
        String code = "package p\ntype PublicStruct struct {}\n";
        DetectorResult r = d.detect(ctx(code));
        var node = r.nodes().stream().filter(n -> n.getKind() == NodeKind.CLASS).findFirst().orElseThrow();
        assertEquals(true, node.getProperties().get("exported"));
    }

    @Test
    void detectsUnexportedStructFlagFalse() {
        String code = "package p\ntype privateStruct struct {}\n";
        DetectorResult r = d.detect(ctx(code));
        var node = r.nodes().stream().filter(n -> n.getKind() == NodeKind.CLASS).findFirst().orElseThrow();
        assertEquals(false, node.getProperties().get("exported"));
    }

    @Test
    void detectsInterface() {
        String code = """
                package repository
                type UserRepository interface {
                    FindAll() []User
                    FindByID(id int) User
                }
                """;
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.INTERFACE && "UserRepository".equals(n.getLabel())));
    }

    @Test
    void detectsMethodOnReceiver() {
        String code = """
                package service
                type UserService struct{}
                func (s *UserService) GetUser(id int) User {
                    return User{}
                }
                func (s *UserService) CreateUser(u User) error {
                    return nil
                }
                """;
        DetectorResult r = d.detect(ctx(code));
        assertEquals(2, r.nodes().stream().filter(n -> n.getKind() == NodeKind.METHOD).count());
        // Methods have receiver_type property
        assertTrue(r.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.METHOD)
                .allMatch(n -> "UserService".equals(n.getProperties().get("receiver_type"))));
    }

    @Test
    void detectsMethodProducesDefinesEdge() {
        String code = """
                package svc
                type Svc struct{}
                func (s *Svc) Do() {}
                """;
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEFINES));
    }

    @Test
    void detectsTopLevelFunctions() {
        String code = """
                package main
                func main() {}
                func helper() int { return 42 }
                """;
        DetectorResult r = d.detect(ctx(code));
        assertEquals(2, r.nodes().stream().filter(n -> n.getKind() == NodeKind.METHOD).count());
    }

    @Test
    void detectsBlockImports() {
        String code = """
                package main
                import (
                    "fmt"
                    "net/http"
                    "github.com/gorilla/mux"
                )
                """;
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.IMPORTS));
        assertEquals(3, r.edges().stream().filter(e -> e.getKind() == EdgeKind.IMPORTS).count());
    }

    @Test
    void detectsSingleImport() {
        String code = "package main\nimport \"os\"\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.IMPORTS
                && "os".equals(e.getTarget().getLabel())));
    }

    @Test
    void detectsStructAndInterface() {
        DetectorResult r = d.detect(ctx("package main\ntype User struct {\n}\ntype Reader interface {\n}"));
        assertTrue(r.nodes().size() >= 3);
    }

    @Test
    void emptyContentReturnsEmpty() {
        DetectorResult r = d.detect(ctx(""));
        assertTrue(r.nodes().isEmpty());
        assertTrue(r.edges().isEmpty());
    }

    @Test
    void nullContentReturnsEmpty() {
        DetectorContext ctxNull = new DetectorContext("test.go", "go", null);
        DetectorResult r = d.detect(ctxNull);
        assertTrue(r.nodes().isEmpty());
    }

    @Test
    void returnsCorrectName() {
        assertEquals("go_structures", d.getName());
    }

    @Test
    void supportedLanguagesContainsGo() {
        assertTrue(d.getSupportedLanguages().contains("go"));
    }

    @Test
    void deterministic() {
        String code = """
                package main
                import (
                    "fmt"
                    "os"
                )
                type Config struct {
                    Name string
                    Port int
                }
                type Configurable interface {
                    Configure() error
                }
                func (c *Config) Apply() error { return nil }
                func run() { fmt.Println("start") }
                """;
        DetectorTestUtils.assertDeterministic(d, ctx(code));
    }

    private static DetectorContext ctx(String content) {
        return DetectorTestUtils.contextFor("go", content);
    }
}
