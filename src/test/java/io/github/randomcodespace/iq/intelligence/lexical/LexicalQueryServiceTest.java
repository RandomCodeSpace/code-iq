package io.github.randomcodespace.iq.intelligence.lexical;

import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LexicalQueryService}.
 * GraphStore and SnippetStore are mocked; config supplies the root path.
 */
class LexicalQueryServiceTest {

    @TempDir
    Path tempRoot;

    private GraphStore graphStore;
    private SnippetStore snippetStore;
    private CodeIqConfig config;
    private LexicalQueryService service;

    @BeforeEach
    void setUp() {
        graphStore = mock(GraphStore.class);
        snippetStore = mock(SnippetStore.class);
        config = new CodeIqConfig();
        config.setRootPath(tempRoot.toString());
        service = new LexicalQueryService(graphStore, snippetStore, config);
    }

    // ------------------------------------------------------------------ findByIdentifier

    @Test
    void findByIdentifierDelegatesToGraphStoreSearch() {
        CodeNode node = new CodeNode("cls:UserService", NodeKind.CLASS, "UserService");
        when(graphStore.search("UserService", 10)).thenReturn(List.of(node));

        List<LexicalResult> results = service.findByIdentifier("UserService", 10);

        assertEquals(1, results.size());
        assertEquals(node, results.getFirst().node());
        assertEquals("identifier", results.getFirst().matchedField());
        verify(graphStore).search("UserService", 10);
    }

    @Test
    void findByIdentifierCapsLimitAt200() {
        when(graphStore.search(anyString(), eq(200))).thenReturn(List.of());

        service.findByIdentifier("anything", 999);

        verify(graphStore).search("anything", 200);
    }

    @Test
    void findByIdentifierDefaultOverloadUsesLimit50() {
        when(graphStore.search(anyString(), eq(50))).thenReturn(List.of());

        service.findByIdentifier("handleLogin");

        verify(graphStore).search("handleLogin", 50);
    }

    @Test
    void findByIdentifierReturnsEmptyWhenNoNodes() {
        when(graphStore.search(anyString(), anyInt())).thenReturn(List.of());

        assertTrue(service.findByIdentifier("unknown").isEmpty());
    }

    @Test
    void findByIdentifierMapsAllNodes() {
        var n1 = new CodeNode("cls:A", NodeKind.CLASS, "A");
        var n2 = new CodeNode("cls:B", NodeKind.CLASS, "B");
        when(graphStore.search(anyString(), anyInt())).thenReturn(List.of(n1, n2));

        List<LexicalResult> results = service.findByIdentifier("A", 10);

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(r -> "identifier".equals(r.matchedField())));
    }

    // ------------------------------------------------------------------ findByDocComment

    @Test
    void findByDocCommentDelegatesToSearchLexical() {
        CodeNode node = new CodeNode("cls:Foo", NodeKind.CLASS, "Foo");
        node.setFilePath("src/Foo.java");
        when(graphStore.searchLexical("authentication", 50)).thenReturn(List.of(node));
        when(snippetStore.extract(eq(node), any(Path.class))).thenReturn(Optional.empty());

        List<LexicalResult> results = service.findByDocComment("authentication");

        assertEquals(1, results.size());
        assertEquals(node, results.getFirst().node());
        assertEquals(LexicalEnricher.KEY_LEX_COMMENT, results.getFirst().matchedField());
    }

    @Test
    void findByDocCommentCapsLimitAt200() {
        when(graphStore.searchLexical(anyString(), eq(200))).thenReturn(List.of());

        service.findByDocComment("query", 300);

        verify(graphStore).searchLexical("query", 200);
    }

    @Test
    void findByDocCommentDefaultOverloadUsesLimit50() {
        when(graphStore.searchLexical(anyString(), eq(50))).thenReturn(List.of());

        service.findByDocComment("some query");

        verify(graphStore).searchLexical("some query", 50);
    }

    @Test
    void findByDocCommentIncludesSnippetWhenPresent() {
        CodeNode node = new CodeNode("cls:Bar", NodeKind.CLASS, "Bar");
        node.setFilePath("src/Bar.java");
        node.setLineStart(1);
        node.setLineEnd(5);
        CodeSnippet snippet = new CodeSnippet("public class Bar {}", "src/Bar.java", 1, 5, "java", null);

        when(graphStore.searchLexical(anyString(), anyInt())).thenReturn(List.of(node));
        when(snippetStore.extract(eq(node), any(Path.class))).thenReturn(Optional.of(snippet));

        List<LexicalResult> results = service.findByDocComment("Bar");

        assertEquals(snippet, results.getFirst().snippet());
    }

    // ------------------------------------------------------------------ findByConfigKey

    @Test
    void findByConfigKeyFiltersToConfigNodeKinds() {
        CodeNode configKey = new CodeNode("ck:1", NodeKind.CONFIG_KEY, "spring.datasource.url");
        CodeNode configFile = new CodeNode("cf:1", NodeKind.CONFIG_FILE, "application.yml");
        CodeNode configDef = new CodeNode("cd:1", NodeKind.CONFIG_DEFINITION, "DataSourceDef");
        CodeNode classNode = new CodeNode("cls:1", NodeKind.CLASS, "SomeClass"); // should be filtered out

        when(graphStore.searchLexical(anyString(), anyInt()))
                .thenReturn(List.of(configKey, configFile, configDef, classNode));

        List<LexicalResult> results = service.findByConfigKey("spring.datasource");

        assertEquals(3, results.size());
        assertTrue(results.stream().noneMatch(r -> r.node().getKind() == NodeKind.CLASS));
        assertTrue(results.stream().allMatch(r -> LexicalEnricher.KEY_LEX_CONFIG_KEYS.equals(r.matchedField())));
    }

    @Test
    void findByConfigKeyReturnsEmptyWhenOnlyNonConfigNodes() {
        CodeNode cls = new CodeNode("cls:1", NodeKind.CLASS, "Something");
        when(graphStore.searchLexical(anyString(), anyInt())).thenReturn(List.of(cls));

        List<LexicalResult> results = service.findByConfigKey("spring");

        assertTrue(results.isEmpty());
    }

    @Test
    void findByConfigKeyCapsLimitAt200() {
        when(graphStore.searchLexical(anyString(), eq(200))).thenReturn(List.of());

        service.findByConfigKey("any", 500);

        verify(graphStore).searchLexical("any", 200);
    }

    @Test
    void findByConfigKeyDefaultOverloadUsesLimit50() {
        when(graphStore.searchLexical(anyString(), eq(50))).thenReturn(List.of());

        service.findByConfigKey("key");

        verify(graphStore).searchLexical("key", 50);
    }

    @Test
    void findByConfigKeyIncludesAllThreeConfigKinds() {
        CodeNode ck = new CodeNode("ck:1", NodeKind.CONFIG_KEY, "key");
        CodeNode cf = new CodeNode("cf:1", NodeKind.CONFIG_FILE, "file");
        CodeNode cd = new CodeNode("cd:1", NodeKind.CONFIG_DEFINITION, "def");
        when(graphStore.searchLexical(anyString(), anyInt())).thenReturn(List.of(ck, cf, cd));

        List<LexicalResult> results = service.findByConfigKey("key");

        assertEquals(3, results.size());
    }
}
