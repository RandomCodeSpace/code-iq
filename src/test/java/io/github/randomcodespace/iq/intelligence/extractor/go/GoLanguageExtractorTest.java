package io.github.randomcodespace.iq.intelligence.extractor.go;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.intelligence.CapabilityLevel;
import io.github.randomcodespace.iq.intelligence.extractor.LanguageExtractionResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GoLanguageExtractorTest {

    private final GoLanguageExtractor extractor = new GoLanguageExtractor();

    @Test
    void getLanguage_returnsGo() {
        assertThat(extractor.getLanguage()).isEqualTo("go");
    }

    @Test
    void extract_blockImport_createsImportEdge() {
        CodeNode source = node("go:main.go:fn:main", NodeKind.METHOD, "main");
        CodeNode target = node("go:handler.go:module:handler", NodeKind.MODULE, "handler");

        Map<String, CodeNode> registry = Map.of(target.getLabel(), target);

        String content = """
                package main

                import (
                    "myapp/handler"
                    "fmt"
                )

                func main() {
                    handler.Handle()
                }
                """;

        DetectorContext ctx = new DetectorContext("main.go", "go", content, registry, null);
        LanguageExtractionResult result = extractor.extract(ctx, source);

        assertThat(result.symbolReferences()).hasSize(1);
        CodeEdge edge = result.symbolReferences().get(0);
        assertThat(edge.getKind()).isEqualTo(EdgeKind.IMPORTS);
        assertThat(edge.getTarget().getId()).isEqualTo(target.getId());
    }

    @Test
    void extract_singleImport_createsImportEdge() {
        CodeNode source = node("go:app.go:fn:run", NodeKind.METHOD, "run");
        CodeNode target = node("go:db.go:module:db", NodeKind.MODULE, "db");

        Map<String, CodeNode> registry = Map.of(target.getLabel(), target);
        String content = "import \"myapp/db\"\n";

        DetectorContext ctx = new DetectorContext("app.go", "go", content, registry, null);
        LanguageExtractionResult result = extractor.extract(ctx, source);

        assertThat(result.symbolReferences()).hasSize(1);
        assertThat(result.symbolReferences().get(0).getKind()).isEqualTo(EdgeKind.IMPORTS);
    }

    @Test
    void extract_unknownImport_noEdge() {
        CodeNode source = node("go:app.go:fn:run", NodeKind.METHOD, "run");
        String content = "import \"fmt\"\n";

        DetectorContext ctx = new DetectorContext("app.go", "go", content, Map.of(), null);
        LanguageExtractionResult result = extractor.extract(ctx, source);

        assertThat(result.symbolReferences()).isEmpty();
    }

    @Test
    void extract_noContent_returnsEmpty() {
        CodeNode node = node("go:empty.go:fn:noop", NodeKind.METHOD, "noop");
        DetectorContext ctx = new DetectorContext("empty.go", "go", null, Map.of(), null);
        LanguageExtractionResult result = extractor.extract(ctx, node);

        assertThat(result.callEdges()).isEmpty();
        assertThat(result.symbolReferences()).isEmpty();
        assertThat(result.typeHints()).isEmpty();
    }

    @Test
    void extract_confidence_isPartial() {
        CodeNode node = node("go:x.go:fn:fn", NodeKind.METHOD, "fn");
        DetectorContext ctx = new DetectorContext("x.go", "go", "", Map.of(), null);

        LanguageExtractionResult result = extractor.extract(ctx, node);
        assertThat(result.confidence()).isEqualTo(CapabilityLevel.PARTIAL);
    }

    @Test
    void extract_duplicateImportBothStyles_noDuplicateEdges() {
        CodeNode source = node("go:main.go:fn:main", NodeKind.METHOD, "main");
        CodeNode target = node("go:handler.go:module:handler", NodeKind.MODULE, "handler");

        Map<String, CodeNode> registry = Map.of(target.getLabel(), target);

        // File has both a block import and a single-line import for the same package.
        // collectImportPaths() must deduplicate so only one IMPORTS edge is produced.
        String content = """
                package main

                import (
                    "myapp/handler"
                )
                import "myapp/handler"

                func main() {
                    handler.Handle()
                }
                """;

        DetectorContext ctx = new DetectorContext("main.go", "go", content, registry, null);
        LanguageExtractionResult result = extractor.extract(ctx, source);

        assertThat(result.symbolReferences()).hasSize(1);
        assertThat(result.symbolReferences().get(0).getKind()).isEqualTo(EdgeKind.IMPORTS);
        assertThat(result.symbolReferences().get(0).getTarget().getId()).isEqualTo(target.getId());
    }

    @Test
    void extract_determinism_sameTwice() {
        CodeNode source = node("go:a.go:fn:run", NodeKind.METHOD, "run");
        CodeNode target = node("go:b.go:module:worker", NodeKind.MODULE, "worker");
        Map<String, CodeNode> registry = Map.of(target.getLabel(), target);
        String content = "import (\n    \"app/worker\"\n)\n";

        DetectorContext ctx = new DetectorContext("a.go", "go", content, registry, null);
        LanguageExtractionResult r1 = extractor.extract(ctx, source);
        LanguageExtractionResult r2 = extractor.extract(ctx, source);

        assertThat(r1.symbolReferences().size()).isEqualTo(r2.symbolReferences().size());
        if (!r1.symbolReferences().isEmpty()) {
            assertThat(r1.symbolReferences().get(0).getId())
                    .isEqualTo(r2.symbolReferences().get(0).getId());
        }
    }

    @Test
    void extract_ambiguousPackageName_noFalsePositiveEdge() {
        CodeNode source = node("go:main.go:fn:main", NodeKind.METHOD, "main");
        // Two unrelated nodes share the short label "db" — common in Go codebases.
        CodeNode db1 = node("go:db/conn.go:module:db", NodeKind.MODULE, "db");
        CodeNode db2 = node("go:dbutil/query.go:module:db", NodeKind.MODULE, "db");

        Map<String, CodeNode> registry = new LinkedHashMap<>();
        registry.put(db1.getId(), db1);
        registry.put(db2.getId(), db2);

        String content = "import \"myapp/db\"\n";

        DetectorContext ctx = new DetectorContext("main.go", "go", content, registry, null);
        LanguageExtractionResult result = extractor.extract(ctx, source);

        // Ambiguous: two nodes labelled "db" → lookupUnambiguous returns null → no edge.
        assertThat(result.symbolReferences()).isEmpty();
    }

    @Test
    void extract_satisfiesInterfaces_hintIsDeterministicallySorted() {
        CodeNode struct = node("go:s.go:struct:Worker", NodeKind.CLASS, "Worker");
        CodeNode ifaceReader = node("go:r.go:interface:Reader", NodeKind.INTERFACE, "Reader");
        CodeNode ifaceCloser = node("go:c.go:interface:Closer", NodeKind.INTERFACE, "Closer");

        // filePath must be non-null or extractInterfaceHints skips the candidate.
        ifaceReader.setFilePath("go:r.go");
        ifaceCloser.setFilePath("go:c.go");

        // Registry iterated Reader-first; without sorting hint would be "Reader, Closer".
        // After fix it must always be "Closer, Reader" (alphabetical).
        Map<String, CodeNode> registry = new LinkedHashMap<>();
        registry.put(ifaceReader.getLabel(), ifaceReader);
        registry.put(ifaceCloser.getLabel(), ifaceCloser);

        String content = "Worker) Reader\nWorker) Closer\n";

        DetectorContext ctx = new DetectorContext("s.go", "go", content, registry, null);
        LanguageExtractionResult r1 = extractor.extract(ctx, struct);
        LanguageExtractionResult r2 = extractor.extract(ctx, struct);

        assertThat(r1.typeHints()).containsKey("satisfies_interfaces");
        assertThat(r1.typeHints().get("satisfies_interfaces")).isEqualTo("Closer, Reader");
        assertThat(r1.typeHints()).isEqualTo(r2.typeHints());
    }

    private static CodeNode node(String id, NodeKind kind, String label) {
        CodeNode n = new CodeNode(id, kind, label);
        n.setFqn(id);
        return n;
    }
}
