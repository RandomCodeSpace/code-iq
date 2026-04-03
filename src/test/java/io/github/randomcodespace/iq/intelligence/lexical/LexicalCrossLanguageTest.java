package io.github.randomcodespace.iq.intelligence.lexical;

import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-language lexical enrichment tests.
 * Validates DocCommentExtractor and LexicalEnricher for TypeScript, Python, Go, and JavaScript.
 * Complements {@link LexicalEnricherTest} which only covers Java.
 */
class LexicalCrossLanguageTest {

    @TempDir
    Path root;

    private final LexicalEnricher enricher = new LexicalEnricher();

    // ------------------------------------------------------------------
    // TypeScript — block comment (/** ... */)
    // ------------------------------------------------------------------

    @Test
    void typescript_blockComment_extracted() throws Exception {
        Path file = root.resolve("UserService.ts");
        Files.writeString(file, """
                /**
                 * Fetches a user by their unique identifier.
                 */
                export class UserService {
                    fetchUser(id: string) { return id; }
                }
                """);

        CodeNode node = new CodeNode("ts:id1", NodeKind.CLASS, "UserService");
        node.setFilePath("UserService.ts");
        node.setLineStart(4);

        enricher.enrich(List.of(node), root);

        assertThat(node.getProperties()).containsKey(LexicalEnricher.KEY_LEX_COMMENT);
        assertThat(node.getProperties().get(LexicalEnricher.KEY_LEX_COMMENT).toString())
                .contains("unique identifier");
    }

    @Test
    void typescript_noComment_noLexKey() throws Exception {
        Path file = root.resolve("Bare.ts");
        Files.writeString(file, """
                export function bare() { return 42; }
                """);

        CodeNode node = new CodeNode("ts:id2", NodeKind.METHOD, "bare");
        node.setFilePath("Bare.ts");
        node.setLineStart(1);

        enricher.enrich(List.of(node), root);

        assertThat(node.getProperties()).doesNotContainKey(LexicalEnricher.KEY_LEX_COMMENT);
    }

    // ------------------------------------------------------------------
    // JavaScript — block comment (/** ... */)
    // ------------------------------------------------------------------

    @Test
    void javascript_jsDocComment_extracted() throws Exception {
        Path file = root.resolve("helper.js");
        Files.writeString(file, """
                /**
                 * Computes the sum of two numbers.
                 * @param {number} a
                 * @param {number} b
                 */
                function add(a, b) {
                    return a + b;
                }
                """);

        CodeNode node = new CodeNode("js:id1", NodeKind.METHOD, "add");
        node.setFilePath("helper.js");
        node.setLineStart(6);

        enricher.enrich(List.of(node), root);

        assertThat(node.getProperties()).containsKey(LexicalEnricher.KEY_LEX_COMMENT);
        assertThat(node.getProperties().get(LexicalEnricher.KEY_LEX_COMMENT).toString())
                .contains("sum");
    }

    // ------------------------------------------------------------------
    // Python — triple-quoted docstring
    // ------------------------------------------------------------------

    @Test
    void python_tripleDoubleQuotedDocstring_extracted() throws Exception {
        Path file = root.resolve("processor.py");
        Files.writeString(file, """
                class DataProcessor:
                    \"\"\"Processes raw data into structured records.\"\"\"
                    def run(self): pass
                """);

        CodeNode node = new CodeNode("py:id1", NodeKind.CLASS, "DataProcessor");
        node.setFilePath("processor.py");
        node.setLineStart(1);

        enricher.enrich(List.of(node), root);

        assertThat(node.getProperties()).containsKey(LexicalEnricher.KEY_LEX_COMMENT);
        assertThat(node.getProperties().get(LexicalEnricher.KEY_LEX_COMMENT).toString())
                .contains("structured records");
    }

    @Test
    void python_multilineDocstring_extractedTrimmed() throws Exception {
        Path file = root.resolve("service.py");
        Files.writeString(file, """
                def fetch_user(user_id: str):
                    \"\"\"
                    Fetch a user from the database.
                    Returns None if not found.
                    \"\"\"
                    return None
                """);

        CodeNode node = new CodeNode("py:id2", NodeKind.METHOD, "fetch_user");
        node.setFilePath("service.py");
        node.setLineStart(1);

        enricher.enrich(List.of(node), root);

        assertThat(node.getProperties()).containsKey(LexicalEnricher.KEY_LEX_COMMENT);
        String comment = node.getProperties().get(LexicalEnricher.KEY_LEX_COMMENT).toString();
        assertThat(comment).contains("database");
        assertThat(comment).contains("None if not found");
    }

    // ------------------------------------------------------------------
    // Go — line comments (//)
    // ------------------------------------------------------------------

    @Test
    void go_lineComments_extracted() throws Exception {
        Path file = root.resolve("handler.go");
        Files.writeString(file, """
                package handler

                // ServeHTTP handles incoming HTTP requests.
                // It validates the token and returns 401 if invalid.
                func ServeHTTP(w http.ResponseWriter, r *http.Request) {
                }
                """);

        CodeNode node = new CodeNode("go:id1", NodeKind.METHOD, "ServeHTTP");
        node.setFilePath("handler.go");
        node.setLineStart(5);

        enricher.enrich(List.of(node), root);

        assertThat(node.getProperties()).containsKey(LexicalEnricher.KEY_LEX_COMMENT);
        assertThat(node.getProperties().get(LexicalEnricher.KEY_LEX_COMMENT).toString())
                .contains("HTTP requests");
    }

    @Test
    void go_noComment_noLexKey() throws Exception {
        Path file = root.resolve("bare.go");
        Files.writeString(file, """
                package bare
                func noop() {}
                """);

        CodeNode node = new CodeNode("go:id2", NodeKind.METHOD, "noop");
        node.setFilePath("bare.go");
        node.setLineStart(2);

        enricher.enrich(List.of(node), root);

        assertThat(node.getProperties()).doesNotContainKey(LexicalEnricher.KEY_LEX_COMMENT);
    }

    // ------------------------------------------------------------------
    // Determinism — same cross-language nodes enriched twice yield same result
    // ------------------------------------------------------------------

    @Test
    void crossLanguage_deterministicEnrichment() throws Exception {
        Path tsFile = root.resolve("api.ts");
        Files.writeString(tsFile, """
                /** Returns the current user session. */
                export function getSession() {}
                """);

        Path pyFile = root.resolve("models.py");
        Files.writeString(pyFile, """
                class Order:
                    \"\"\"Represents a customer order.\"\"\"
                    pass
                """);

        CodeNode ts1 = new CodeNode("ts:d1", NodeKind.METHOD, "getSession");
        ts1.setFilePath("api.ts"); ts1.setLineStart(2);

        CodeNode ts2 = new CodeNode("ts:d2", NodeKind.METHOD, "getSession");
        ts2.setFilePath("api.ts"); ts2.setLineStart(2);

        CodeNode py1 = new CodeNode("py:d1", NodeKind.CLASS, "Order");
        py1.setFilePath("models.py"); py1.setLineStart(1);

        CodeNode py2 = new CodeNode("py:d2", NodeKind.CLASS, "Order");
        py2.setFilePath("models.py"); py2.setLineStart(1);

        enricher.enrich(List.of(ts1, py1), root);
        enricher.enrich(List.of(ts2, py2), root);

        assertThat(ts1.getProperties().get(LexicalEnricher.KEY_LEX_COMMENT))
                .isEqualTo(ts2.getProperties().get(LexicalEnricher.KEY_LEX_COMMENT));
        assertThat(py1.getProperties().get(LexicalEnricher.KEY_LEX_COMMENT))
                .isEqualTo(py2.getProperties().get(LexicalEnricher.KEY_LEX_COMMENT));
    }

    // ------------------------------------------------------------------
    // DocCommentExtractor direct — Go and Python extraction
    // ------------------------------------------------------------------

    @Test
    void docCommentExtractor_python_singleLineTripledQuote() throws Exception {
        Path file = root.resolve("util.py");
        Files.writeString(file, """
                def compute():
                    \"\"\"Computes the result.\"\"\"
                    return 42
                """);

        String comment = DocCommentExtractor.extract(file, "python", 1);
        assertThat(comment).contains("Computes the result");
    }

    @Test
    void docCommentExtractor_go_lineComment() throws Exception {
        Path file = root.resolve("repo.go");
        Files.writeString(file, """
                // FindAll retrieves all records from the store.
                func FindAll() {}
                """);

        String comment = DocCommentExtractor.extract(file, "go", 2);
        assertThat(comment).contains("retrieves all records");
    }

    @Test
    void docCommentExtractor_typescript_blockComment() throws Exception {
        Path file = root.resolve("client.ts");
        Files.writeString(file, """
                /**
                 * HTTP client for the backend API.
                 */
                export class ApiClient {}
                """);

        String comment = DocCommentExtractor.extract(file, "typescript", 4);
        assertThat(comment).contains("backend API");
    }
}
