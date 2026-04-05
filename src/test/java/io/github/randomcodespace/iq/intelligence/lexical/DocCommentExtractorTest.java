package io.github.randomcodespace.iq.intelligence.lexical;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DocCommentExtractorTest {

    @TempDir
    Path tmp;

    // --- Javadoc block comments ---

    @Test
    void extractsJavadocBeforeClass() throws Exception {
        Path file = tmp.resolve("Foo.java");
        Files.writeString(file, """
                /**
                 * This is the Foo class.
                 * It does foo things.
                 */
                public class Foo {}
                """);
        String result = DocCommentExtractor.extract(file, "java", 5);
        assertThat(result).contains("Foo class").contains("foo things");
    }

    @Test
    void extractsJavadocSkippingAnnotations() throws Exception {
        Path file = tmp.resolve("Bar.java");
        Files.writeString(file, """
                /**
                 * Bar service implementation.
                 */
                @Service
                @Transactional
                public class Bar {}
                """);
        String result = DocCommentExtractor.extract(file, "java", 6);
        assertThat(result).contains("Bar service implementation");
    }

    @Test
    void returnsNullWhenNoDocComment() throws Exception {
        Path file = tmp.resolve("Plain.java");
        Files.writeString(file, """
                public class Plain {
                    void method() {}
                }
                """);
        String result = DocCommentExtractor.extract(file, "java", 1);
        assertThat(result).isNull();
    }

    @Test
    void returnsNullForNullArgs() {
        assertThat(DocCommentExtractor.extract((Path) null, "java", 1)).isNull();
        assertThat(DocCommentExtractor.extract(tmp.resolve("x.java"), null, 1)).isNull();
        assertThat(DocCommentExtractor.extract(tmp.resolve("x.java"), "java", 0)).isNull();
    }

    // --- Python docstrings ---

    @Test
    void extractsPythonDoubleQuoteDocstring() throws Exception {
        Path file = tmp.resolve("service.py");
        Files.writeString(file, """
                def handle_request(req):
                    \"\"\"Handle an incoming HTTP request.\"\"\"
                    pass
                """);
        String result = DocCommentExtractor.extract(file, "python", 1);
        assertThat(result).contains("Handle an incoming HTTP request");
    }

    @Test
    void extractsPythonMultilineDocstring() throws Exception {
        Path file = tmp.resolve("multi.py");
        Files.writeString(file, """
                class UserService:
                    \"\"\"
                    Service for managing users.
                    Supports CRUD operations.
                    \"\"\"
                    pass
                """);
        String result = DocCommentExtractor.extract(file, "python", 1);
        assertThat(result).contains("Service for managing users");
    }

    // --- Go line comments ---

    @Test
    void extractsGoLineComments() throws Exception {
        Path file = tmp.resolve("handler.go");
        Files.writeString(file, """
                // HandleLogin processes a user login request.
                // Returns 401 on failure.
                func HandleLogin(w http.ResponseWriter, r *http.Request) {
                }
                """);
        String result = DocCommentExtractor.extract(file, "go", 3);
        assertThat(result).contains("HandleLogin").contains("401");
    }

    // --- Determinism ---

    @Test
    void extractionIsDeterministic() throws Exception {
        Path file = tmp.resolve("Det.java");
        Files.writeString(file, """
                /**
                 * Deterministic class.
                 */
                public class Det {}
                """);
        String r1 = DocCommentExtractor.extract(file, "java", 4);
        String r2 = DocCommentExtractor.extract(file, "java", 4);
        assertThat(r1).isEqualTo(r2);
    }
}
