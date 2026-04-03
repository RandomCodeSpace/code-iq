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

/**
 * Verifies that every node produced by the analysis pipeline carries provenance.
 * Also validates determinism: running twice on the same input produces identical provenance.
 */
@SpringBootTest
@ActiveProfiles("indexing")
class ProvenanceIntegrationTest {

    @Autowired
    Analyzer analyzer;

    @Test
    void everyNodeHasProvenance(@TempDir Path tempDir) throws Exception {
        // Write a minimal Java source file
        Path src = tempDir.resolve("src/main/java/com/example/HelloController.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package com.example;
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.bind.annotation.GetMapping;
                @RestController
                public class HelloController {
                    @GetMapping("/hello")
                    public String hello() { return "hello"; }
                }
                """);

        AnalysisResult result = analyzer.run(tempDir, msg -> {});

        List<CodeNode> nodes = result.nodes();
        assertThat(nodes).isNotNull();
        assertThat(nodes).isNotEmpty();

        for (CodeNode node : nodes) {
            Provenance prov = node.getProvenance();
            assertThat(prov)
                    .as("Node %s should have provenance", node.getId())
                    .isNotNull();
            assertThat(prov.extractorVersion())
                    .as("Node %s should have extractorVersion", node.getId())
                    .isNotBlank();
            assertThat(prov.confidence())
                    .as("Node %s should have confidence", node.getId())
                    .isNotNull();
            assertThat(prov.schemaVersion())
                    .as("Node %s should have schemaVersion >= 1", node.getId())
                    .isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void provenance_isDeterministic(@TempDir Path tempDir) throws Exception {
        Path src = tempDir.resolve("src/Foo.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package com;
                public class Foo {
                    public void bar() {}
                }
                """);

        // Run twice
        AnalysisResult r1 = analyzer.run(tempDir, msg -> {});
        AnalysisResult r2 = analyzer.run(tempDir, msg -> {});

        assertThat(r1.nodes()).isNotNull();
        assertThat(r2.nodes()).isNotNull();

        // Same node count
        assertThat(r1.nodes()).hasSameSizeAs(r2.nodes());

        // Provenance fields must be identical (same extractor version, same schema version)
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
