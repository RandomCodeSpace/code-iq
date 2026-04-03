package io.github.randomcodespace.iq.intelligence;

import io.github.randomcodespace.iq.analyzer.Analyzer;
import io.github.randomcodespace.iq.analyzer.AnalysisResult;
import io.github.randomcodespace.iq.model.CodeNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Edge-case tests for provenance pipeline.
 * Covers: empty repos, single-file repos, unsupported-language-only repos,
 * mixed-language repos, and repos with no git history.
 */
@SpringBootTest
@ActiveProfiles("indexing")
class ProvenanceEdgeCasesTest {

    @Autowired
    Analyzer analyzer;

    // ------------------------------------------------------------------
    // Empty repo — no source files, pipeline should not throw
    // ------------------------------------------------------------------

    @Test
    void emptyDirectory_producesNoNodes_noException(@TempDir Path dir) {
        assertThatCode(() -> {
            AnalysisResult result = analyzer.run(dir, msg -> {});
            assertThat(result).isNotNull();
            // Empty dir may produce zero nodes — that is acceptable
        }).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // Single-file repo — exactly one Java file
    // ------------------------------------------------------------------

    @Test
    void singleJavaFile_allNodesHaveProvenance(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("Greeter.java");
        Files.writeString(src, """
                public class Greeter {
                    public String greet(String name) {
                        return "Hello, " + name;
                    }
                }
                """);

        AnalysisResult result = analyzer.run(dir, msg -> {});
        assertThat(result.nodes()).isNotEmpty();

        for (CodeNode node : result.nodes()) {
            assertThat(node.getProvenance())
                    .as("Node %s must carry provenance", node.getId())
                    .isNotNull();
            assertThat(node.getProvenance().extractorVersion()).isNotBlank();
            assertThat(node.getProvenance().confidence()).isNotNull();
        }
    }

    // ------------------------------------------------------------------
    // Unsupported-language-only repo — e.g., only .rb files
    // Pipeline should complete gracefully; any nodes produced must have provenance.
    // ------------------------------------------------------------------

    @Test
    void unsupportedLanguageOnly_completesGracefully(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("script.rb"), "puts 'hello world'");
        Files.writeString(dir.resolve("helper.brainfuck"), "++++++++[>++++[");

        assertThatCode(() -> {
            AnalysisResult result = analyzer.run(dir, msg -> {});
            assertThat(result).isNotNull();
            // Any nodes emitted for unsupported languages must still carry provenance
            for (CodeNode node : result.nodes()) {
                assertThat(node.getProvenance())
                        .as("Node %s from unsupported-language file must have provenance", node.getId())
                        .isNotNull();
            }
        }).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // Mixed-language repo — Java + TypeScript + Python + Go
    // Every node must carry provenance regardless of language.
    // ------------------------------------------------------------------

    @Test
    void mixedLanguageRepo_allNodesHaveProvenance(@TempDir Path dir) throws Exception {
        // Java
        Path javaDir = dir.resolve("src/main/java/com/example");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("UserService.java"), """
                package com.example;
                public class UserService {
                    public String findUser(String id) { return id; }
                }
                """);

        // TypeScript
        Path tsDir = dir.resolve("frontend/src");
        Files.createDirectories(tsDir);
        Files.writeString(tsDir.resolve("api.ts"), """
                export interface User { id: string; name: string; }
                export function fetchUser(id: string): Promise<User> {
                    return fetch(`/api/users/${id}`).then(r => r.json());
                }
                """);

        // Python
        Path pyDir = dir.resolve("scripts");
        Files.createDirectories(pyDir);
        Files.writeString(pyDir.resolve("process.py"), """
                def process_data(items: list) -> list:
                    return [item for item in items if item]
                """);

        // Go
        Path goDir = dir.resolve("cmd");
        Files.createDirectories(goDir);
        Files.writeString(goDir.resolve("main.go"), """
                package main
                import "fmt"
                func main() {
                    fmt.Println("hello")
                }
                """);

        AnalysisResult result = analyzer.run(dir, msg -> {});
        List<CodeNode> nodes = result.nodes();
        assertThat(nodes).isNotEmpty();

        for (CodeNode node : nodes) {
            assertThat(node.getProvenance())
                    .as("Node %s (%s) must carry provenance", node.getId(), node.getFilePath())
                    .isNotNull();
            assertThat(node.getProvenance().schemaVersion())
                    .as("Node %s must have schemaVersion >= 1", node.getId())
                    .isGreaterThanOrEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // No git history — plain directory, not initialised as a git repo.
    // commitSha and repoUrl in provenance should be null, not throw.
    // ------------------------------------------------------------------

    @Test
    void noGitHistory_provenanceHasNullGitFields(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("App.java");
        Files.writeString(src, """
                public class App {
                    public static void main(String[] args) {}
                }
                """);

        AnalysisResult result = analyzer.run(dir, msg -> {});
        assertThat(result.nodes()).isNotEmpty();

        for (CodeNode node : result.nodes()) {
            Provenance prov = node.getProvenance();
            assertThat(prov).isNotNull();
            // No git repo → commitSha and repoUrl must be null
            assertThat(prov.commitSha())
                    .as("No git repo — commitSha should be null for node %s", node.getId())
                    .isNull();
            assertThat(prov.repositoryUrl())
                    .as("No git repo — repositoryUrl should be null for node %s", node.getId())
                    .isNull();
            // extractorVersion and schemaVersion must still be populated
            assertThat(prov.extractorVersion()).isNotBlank();
            assertThat(prov.schemaVersion()).isGreaterThanOrEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // Mixed-language determinism — same mixed-language repo analysed twice
    // ------------------------------------------------------------------

    @Test
    void mixedLanguageRepo_deterministicProvenance(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Service.java"), "public class Service {}");
        Files.writeString(dir.resolve("index.ts"), "export const x = 1;");
        Files.writeString(dir.resolve("util.py"), "def helper(): pass");

        AnalysisResult r1 = analyzer.run(dir, msg -> {});
        AnalysisResult r2 = analyzer.run(dir, msg -> {});

        assertThat(r1.nodes()).hasSameSizeAs(r2.nodes());
        for (int i = 0; i < r1.nodes().size(); i++) {
            Provenance p1 = r1.nodes().get(i).getProvenance();
            Provenance p2 = r2.nodes().get(i).getProvenance();
            assertThat(p1).isNotNull();
            assertThat(p2).isNotNull();
            assertThat(p1.extractorVersion()).isEqualTo(p2.extractorVersion());
            assertThat(p1.schemaVersion()).isEqualTo(p2.schemaVersion());
            assertThat(p1.confidence()).isEqualTo(p2.confidence());
        }
    }
}
