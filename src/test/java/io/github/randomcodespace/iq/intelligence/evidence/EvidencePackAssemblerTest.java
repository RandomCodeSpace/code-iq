package io.github.randomcodespace.iq.intelligence.evidence;

import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.intelligence.CapabilityLevel;
import io.github.randomcodespace.iq.intelligence.lexical.LexicalQueryService;
import io.github.randomcodespace.iq.intelligence.lexical.LexicalResult;
import io.github.randomcodespace.iq.intelligence.lexical.SnippetStore;
import io.github.randomcodespace.iq.intelligence.provenance.ArtifactMetadata;
import io.github.randomcodespace.iq.intelligence.query.QueryPlanner;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import io.github.randomcodespace.iq.config.CodeIqConfigTestSupport;

@ExtendWith(MockitoExtension.class)
class EvidencePackAssemblerTest {

    @Mock
    private LexicalQueryService lexicalQueryService;
    @Mock
    private SnippetStore snippetStore;
    @Mock
    private GraphStore graphStore;

    private QueryPlanner queryPlanner;
    private CodeIqConfig config;
    private EvidencePackAssembler assembler;
    private ArtifactMetadata metadata;

    @BeforeEach
    void setUp() {
        queryPlanner = new QueryPlanner();
        config = new CodeIqConfig();
        CodeIqConfigTestSupport.override(config).rootPath(System.getProperty("java.io.tmpdir")).done();
        CodeIqConfigTestSupport.override(config).maxSnippetLines(50).done();
        assembler = new EvidencePackAssembler(lexicalQueryService, snippetStore, queryPlanner, config, graphStore);
        metadata = new ArtifactMetadata(
                "https://github.com/example/repo", "abc123", Instant.now(),
                "1", "2", Map.of("code-iq", "1.0"),
                Map.of(), "deadbeef");
    }

    @Test
    void assemblesPackForKnownSymbol() {
        CodeNode node = new CodeNode("java:Foo.java:class:Foo", NodeKind.CLASS, "Foo");
        node.setFilePath("src/Foo.java");
        node.setLineStart(1);
        node.setLineEnd(10);

        when(lexicalQueryService.findByIdentifier("Foo")).thenReturn(
                List.of(LexicalResult.of(node, 1.0f, "identifier")));
        when(snippetStore.extract(any(CodeNode.class), any())).thenReturn(java.util.Optional.empty());

        // Provide filePath so language resolves to "java" → GRAPH_FIRST route → no degradation note
        EvidencePackRequest req = new EvidencePackRequest("Foo", "src/Foo.java", null, false);
        EvidencePack pack = assembler.assemble(req, metadata);

        assertThat(pack.matchedSymbols()).hasSize(1);
        assertThat(pack.matchedSymbols().get(0).getLabel()).isEqualTo("Foo");
        assertThat(pack.relatedFiles()).contains("src/Foo.java");
        assertThat(pack.degradationNotes()).isEmpty();
        assertThat(pack.capabilityLevel()).isNotNull();
    }

    @Test
    void returnsEmptyPackWithDegradationNoteForMissingSymbol() {
        when(lexicalQueryService.findByIdentifier(anyString())).thenReturn(List.of());

        EvidencePackRequest req = new EvidencePackRequest("NonExistent", null, null, false);
        EvidencePack pack = assembler.assemble(req, metadata);

        assertThat(pack.matchedSymbols()).isEmpty();
        assertThat(pack.snippets()).isEmpty();
        assertThat(pack.degradationNotes()).isNotEmpty();
        assertThat(pack.capabilityLevel()).isEqualTo(CapabilityLevel.UNSUPPORTED);
    }

    @Test
    void returnsEmptyPackWhenNeitherSymbolNorFileProvided() {
        EvidencePackRequest req = new EvidencePackRequest(null, null, null, false);
        EvidencePack pack = assembler.assemble(req, metadata);

        assertThat(pack.matchedSymbols()).isEmpty();
        assertThat(pack.degradationNotes()).isNotEmpty();
    }

    @Test
    void isDeterministic() {
        CodeNode node = new CodeNode("java:Bar.java:class:Bar", NodeKind.CLASS, "Bar");
        node.setFilePath("src/Bar.java");
        node.setLineStart(1);
        node.setLineEnd(5);

        when(lexicalQueryService.findByIdentifier("Bar")).thenReturn(
                List.of(LexicalResult.of(node, 1.0f, "identifier")));
        when(snippetStore.extract(any(CodeNode.class), any())).thenReturn(java.util.Optional.empty());

        EvidencePackRequest req = new EvidencePackRequest("Bar", null, null, false);
        EvidencePack pack1 = assembler.assemble(req, metadata);
        EvidencePack pack2 = assembler.assemble(req, metadata);

        assertThat(pack1.matchedSymbols().stream().map(CodeNode::getId).toList())
                .isEqualTo(pack2.matchedSymbols().stream().map(CodeNode::getId).toList());
        assertThat(pack1.relatedFiles()).isEqualTo(pack2.relatedFiles());
        assertThat(pack1.capabilityLevel()).isEqualTo(pack2.capabilityLevel());
    }

    @Test
    void respectsMaxSnippetLinesFromConfig() {
        CodeIqConfigTestSupport.override(config).maxSnippetLines(10).done();
        assertThat(config.getMaxSnippetLines()).isEqualTo(10);
    }
}
