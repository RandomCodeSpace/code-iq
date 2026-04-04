package io.github.randomcodespace.iq.detector.java;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for PublicApiDetector covering both the JavaParser AST path
 * and the regex fallback path (triggered by a NUL byte in content).
 */
class PublicApiDetectorTest {

    private final PublicApiDetector detector = new PublicApiDetector();

    private static DetectorContext ctx(String content) {
        return DetectorTestUtils.contextFor(
                "src/main/java/com/example/api/UserService.java", "java", content);
    }

    // ---- Empty / null guards --------------------------------------------------------

    @Test
    void returnsEmptyOnNullContent() {
        var result = detector.detect(new DetectorContext("Api.java", "java", null));
        assertTrue(result.nodes().isEmpty());
        assertTrue(result.edges().isEmpty());
    }

    @Test
    void returnsEmptyOnEmptyContent() {
        var result = detector.detect(ctx(""));
        assertTrue(result.nodes().isEmpty());
    }

    // ---- Public class with public methods -------------------------------------------

    @Test
    void detectsPublicMethodOnPublicClass() {
        String code = """
                package com.example;
                public class OrderService {
                    public Order findById(Long id) { return null; }
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        var method = result.nodes().get(0);
        assertEquals(NodeKind.METHOD, method.getKind());
        assertEquals("public", method.getProperties().get("visibility"));
    }

    @Test
    void detectsMultiplePublicMethods() {
        String code = """
                package com.example;
                public class CatalogService {
                    public List<Item> listAll(String filter, int page, int size) { return null; }
                    public Item findBySku(String sku) { return null; }
                    public void archive(Long id, String reason) {}
                }
                """;
        var result = detector.detect(ctx(code));
        // listAll and archive have >0 params that are not trivial accessors, findBySku too
        assertEquals(3, result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.METHOD).count());
    }

    // ---- Protected method -----------------------------------------------------------

    @Test
    void detectsProtectedMethodOnAbstractClass() {
        String code = """
                package com.example;
                public abstract class BaseProcessor {
                    protected String formatResult(String raw) { return raw.trim(); }
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertEquals("protected", result.nodes().get(0).getProperties().get("visibility"));
    }

    // ---- Private methods skipped ----------------------------------------------------

    @Test
    void skipsPrivateMethods() {
        String code = """
                package com.example;
                public class InternalHelper {
                    private void helper() {}
                    private String format(String s) { return s; }
                }
                """;
        var result = detector.detect(ctx(code));
        assertTrue(result.nodes().isEmpty(), "Private methods should not be detected");
    }

    // ---- Getter/setter/is accessors skipped ----------------------------------------

    @Test
    void skipsGetterMethods() {
        String code = """
                package com.example;
                public class UserDto {
                    public String getName() { return name; }
                    public Long getId() { return id; }
                }
                """;
        var result = detector.detect(ctx(code));
        assertTrue(result.nodes().isEmpty(), "Getter methods should be skipped");
    }

    @Test
    void skipsSetterMethods() {
        String code = """
                package com.example;
                public class UserDto {
                    public void setName(String name) { this.name = name; }
                    public void setId(Long id) { this.id = id; }
                }
                """;
        var result = detector.detect(ctx(code));
        assertTrue(result.nodes().isEmpty(), "Setter methods should be skipped");
    }

    @Test
    void skipsIsAccessors() {
        String code = """
                package com.example;
                public class UserDto {
                    public boolean isActive() { return active; }
                    public boolean isAdmin() { return admin; }
                }
                """;
        var result = detector.detect(ctx(code));
        assertTrue(result.nodes().isEmpty(), "Boolean is-accessors should be skipped");
    }

    // ---- toString / hashCode / equals / clone / finalize skipped -------------------

    @Test
    void skipsToStringHashCodeEquals() {
        String code = """
                package com.example;
                public class Entity {
                    public String toString() { return ""; }
                    public int hashCode() { return 0; }
                    public boolean equals(Object o) { return false; }
                    public Object clone() { return null; }
                    protected void finalize() {}
                }
                """;
        var result = detector.detect(ctx(code));
        assertTrue(result.nodes().isEmpty(), "Object method overrides should be skipped");
    }

    // ---- Static public method -------------------------------------------------------

    @Test
    void detectsStaticPublicMethod() {
        String code = """
                package com.example;
                public class Converter {
                    public static String toJson(Object obj) { return "{}"; }
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertEquals(true, result.nodes().get(0).getProperties().get("is_static"));
    }

    // ---- Abstract method ------------------------------------------------------------

    @Test
    void detectsAbstractPublicMethod() {
        String code = """
                package com.example;
                public abstract class Processor {
                    public abstract void process(String input);
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertEquals(true, result.nodes().get(0).getProperties().get("is_abstract"));
    }

    // ---- Public interface methods ---------------------------------------------------

    @Test
    void detectsInterfaceMethodsWithImplicitPublic() {
        String code = """
                package com.example;
                public interface Repository {
                    List<User> findAll(String filter);
                    User findById(Long id);
                    void deleteById(Long id);
                }
                """;
        var result = detector.detect(ctx(code));
        // Interface methods that are not trivial accessors should be detected
        assertFalse(result.nodes().isEmpty());
        assertTrue(result.nodes().stream().allMatch(n -> n.getKind() == NodeKind.METHOD));
    }

    @Test
    void detectsInterfaceWithDefaultMethods() {
        String code = """
                package com.example;
                public interface Validator<T> {
                    boolean validate(T value);
                    default void validateAndThrow(T value) {
                        if (!validate(value)) throw new IllegalArgumentException();
                    }
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
    }

    // ---- Javadoc annotation detection ----------------------------------------------

    @Test
    void detectsDeprecatedPublicMethod() {
        String code = """
                package com.example;
                public class LegacyService {
                    /** @deprecated use newMethod instead */
                    @Deprecated
                    public String oldMethod(String param) { return null; }
                    public String newMethod(String param) { return null; }
                }
                """;
        var result = detector.detect(ctx(code));
        assertEquals(2, result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.METHOD).count(),
                "Both deprecated and new method should be detected");
    }

    // ---- Parameter signatures -------------------------------------------------------

    @Test
    void methodIdIncludesParameterSignature() {
        String code = """
                package com.example;
                public class SearchService {
                    public List<String> search(String query, int limit) { return null; }
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        String methodId = result.nodes().get(0).getId();
        assertNotNull(methodId);
        assertTrue(methodId.contains("search"), "Method ID should contain method name");
    }

    @Test
    void detectsMethodWithNoParameters() {
        String code = """
                package com.example;
                public class HealthService {
                    public String status() { return "OK"; }
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        @SuppressWarnings("unchecked")
        var params = (List<?>) result.nodes().get(0).getProperties().get("parameters");
        assertNotNull(params);
        assertTrue(params.isEmpty(), "Method with no parameters should have empty params list");
    }

    // ---- DEFINES edge ---------------------------------------------------------------

    @Test
    void createsDefinesEdge() {
        String code = """
                package com.example;
                public class NotificationService {
                    public void sendEmail(String to, String subject) {}
                }
                """;
        var result = detector.detect(ctx(code));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEFINES),
                "Should create a DEFINES edge from class to method");
    }

    @Test
    void definesEdgeSourceIsClassNode() {
        String code = """
                package com.example;
                public class UserService {
                    public void activate(Long userId) {}
                }
                """;
        var result = detector.detect(ctx(code));
        var edge = result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.DEFINES).findFirst().orElseThrow();
        assertTrue(edge.getSourceId().contains("UserService"),
                "DEFINES edge source should reference the class");
    }

    // ---- FQN includes package -------------------------------------------------------

    @Test
    void fqnIncludesPackageAndClass() {
        String code = """
                package com.example.svc;
                public class PaymentService {
                    public void charge(Long amount) {}
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        String fqn = result.nodes().get(0).getFqn();
        assertNotNull(fqn);
        assertTrue(fqn.startsWith("com.example.svc.PaymentService"),
                "FQN should include package and class name");
    }

    // ---- lineStart and lineEnd -------------------------------------------------------

    @Test
    void methodNodeHasLineNumbers() {
        String code = """
                package com.example;
                public class InfoService {
                    public String describe(Long id) {
                        return "item-" + id;
                    }
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        int lineStart = result.nodes().get(0).getLineStart();
        int lineEnd = result.nodes().get(0).getLineEnd();
        assertTrue(lineStart > 0, "lineStart should be positive");
        assertTrue(lineEnd >= lineStart, "lineEnd should be >= lineStart");
    }

    // ---- label format ---------------------------------------------------------------

    @Test
    void methodLabelIncludesClassAndMethodName() {
        String code = """
                package com.example;
                public class ReportService {
                    public byte[] exportToPdf(Long reportId) { return null; }
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        String label = result.nodes().get(0).getLabel();
        assertNotNull(label);
        assertTrue(label.contains("ReportService"), "Label should include class name");
        assertTrue(label.contains("exportToPdf"), "Label should include method name");
    }

    // ---- Returns empty when no class/interface found --------------------------------

    @Test
    void returnsEmptyWhenNoClassOrInterface() {
        String code = "// Just a comment\nimport java.util.*;\n";
        var result = detector.detect(ctx(code));
        assertTrue(result.nodes().isEmpty(),
                "Content without a class or interface declaration should return empty");
    }

    // ---- Determinism ----------------------------------------------------------------

    @Test
    void isDeterministic() {
        String code = """
                package com.example;
                public class ApiService {
                    public String execute(String command) { return null; }
                    public List<String> listAll(String filter) { return null; }
                    protected void doInternal(String key) {}
                }
                """;
        DetectorTestUtils.assertDeterministic(detector, ctx(code));
    }

    // ---- getName / getSupportedLanguages --------------------------------------------

    @Test
    void returnsCorrectName() {
        assertEquals("java.public_api", detector.getName());
    }

    @Test
    void supportedLanguagesContainsJava() {
        assertTrue(detector.getSupportedLanguages().contains("java"));
    }

    // ---- Regex fallback (NUL byte forces JavaParser failure) -------------------------

    @Test
    void regexFallback_detectsPublicMethod() {
        String code = "\u0000 class SomeService {\n"
                + "    public String process(String input) { return null; }\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect public method");
        assertEquals(NodeKind.METHOD, result.nodes().get(0).getKind());
        assertEquals("public", result.nodes().get(0).getProperties().get("visibility"));
    }

    @Test
    void regexFallback_detectsProtectedMethod() {
        String code = "\u0000 class Base {\n"
                + "    protected void doWork(String context) {}\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect protected method");
        assertEquals("protected", result.nodes().get(0).getProperties().get("visibility"));
    }

    @Test
    void regexFallback_skipsPrivateMethod() {
        String code = "\u0000 class Util {\n"
                + "    private void internalHelper() {}\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertTrue(result.nodes().isEmpty(),
                "regex fallback should skip private methods");
    }

    @Test
    void regexFallback_skipsGetterMethod() {
        String code = "\u0000 class Dto {\n"
                + "    public String getName() { return name; }\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertTrue(result.nodes().isEmpty(),
                "regex fallback should skip getter methods");
    }

    @Test
    void regexFallback_skipsSetterMethod() {
        String code = "\u0000 class Dto {\n"
                + "    public void setName(String name) { this.name = name; }\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertTrue(result.nodes().isEmpty(),
                "regex fallback should skip setter methods");
    }

    @Test
    void regexFallback_skipsToString() {
        String code = "\u0000 class Dto {\n"
                + "    public String toString() { return \"\"; }\n"
                + "    public void process(String data) {}\n"
                + "}";
        var result = detector.detect(ctx(code));
        // toString should be skipped; process should be detected
        assertEquals(1, result.nodes().size(), "Only process() should be detected");
        assertEquals("process", ((String) result.nodes().get(0).getId())
                .contains("process") ? "process" : "");
    }

    @Test
    void regexFallback_detectsStaticMethod() {
        String code = "\u0000 class Factory {\n"
                + "    public static Factory create(String cfg) { return null; }\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect static public method");
        assertEquals(true, result.nodes().get(0).getProperties().get("is_static"));
    }

    @Test
    void regexFallback_detectsAbstractMethod() {
        String code = "\u0000 abstract class Template {\n"
                + "    public abstract void execute(String param);\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect abstract method");
        assertEquals(true, result.nodes().get(0).getProperties().get("is_abstract"));
    }

    @Test
    void regexFallback_detectsMethodWithMultipleParams() {
        String code = "\u0000 class SearchService {\n"
                + "    public List search(String query, int page, int size) { return null; }\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect method with multiple params");
        @SuppressWarnings("unchecked")
        var params = (List<?>) result.nodes().get(0).getProperties().get("parameters");
        assertNotNull(params);
        assertFalse(params.isEmpty(), "Parameters should be extracted in regex fallback");
    }

    @Test
    void regexFallback_createsDefinesEdge() {
        String code = "\u0000 class EventService {\n"
                + "    public void publish(String topic, String message) {}\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEFINES),
                "regex fallback should create DEFINES edge");
    }

    @Test
    void regexFallback_detectsInterfaceMethod() {
        String code = "\u0000 interface DataService {\n"
                + "    public List findAll(String filter);\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(),
                "regex fallback should detect public method in interface");
    }

    @Test
    void regexFallback_noClass_returnsEmpty() {
        String code = "\u0000 // just comments\n"
                + "import java.util.*;\n";
        var result = detector.detect(ctx(code));
        assertTrue(result.nodes().isEmpty(),
                "regex fallback without class/interface should return empty");
    }

    @Test
    void regexFallback_returnTypeExtracted() {
        String code = "\u0000 class ReportSvc {\n"
                + "    public ResponseEntity generate(Long id) { return null; }\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertNotNull(result.nodes().get(0).getProperties().get("return_type"),
                "return_type should be extracted in regex fallback");
    }

    @Test
    void regexFallback_labelIncludesClassAndMethodName() {
        String code = "\u0000 class AlertService {\n"
                + "    public void sendAlert(String message) {}\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        String label = result.nodes().get(0).getLabel();
        assertTrue(label.contains("AlertService") && label.contains("sendAlert"),
                "Label should contain class.method in regex fallback");
    }
}
