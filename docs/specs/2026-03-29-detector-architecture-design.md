# Detector Architecture Design — MVP 1

**Date:** 2026-03-29
**Status:** Approved
**Scope:** Port all 115 Python detectors to Java with regex + structured parsing. No JavaParser/ANTLR in MVP 1.

## Overview

Port the Python detector engine to Java/Spring Boot with exact feature parity. All 115 detectors replicated using the same regex patterns and structured parsing approach. Spring Component Scanning for auto-discovery. Virtual threads for parallel execution. Deterministic output guaranteed.

## Interface

```java
public interface Detector {
    String getName();                    // Unique identifier (e.g., "spring_rest")
    Set<String> getSupportedLanguages(); // e.g., Set.of("java")
    DetectorResult detect(DetectorContext ctx);
}
```

## Context & Result

```java
public record DetectorContext(
    String filePath,       // relative to repo root
    String language,       // "java", "python", "yaml", etc.
    String content,        // decoded file text (UTF-8)
    Object parsedData,     // for structured files (Map from YAML/JSON/XML parser)
    String moduleName      // owning module name (nullable)
) {}

public record DetectorResult(
    List<CodeNode> nodes,
    List<CodeEdge> edges
) {
    public static DetectorResult empty() {
        return new DetectorResult(List.of(), List.of());
    }
}
```

## Base Classes

### AbstractRegexDetector

Shared by 82+ regex-based detectors. Provides:

- `iterLines(String content)` → `List<IndexedLine>` (1-based line number + text)
- `findLineNumber(String content, int charOffset)` → int (1-based)
- `fileName(DetectorContext ctx)` → String (filename from path)
- `matchesFilename(DetectorContext ctx, String... patterns)` → boolean (glob matching)
- Static `Pattern` fields for compiled regex (thread-safe, immutable)

### AbstractStructuredDetector

Shared by 18+ config/infra detectors. Provides defensive data access:

- `getMap(Object obj, String key)` → `Map<String, Object>` (fallback to empty)
- `getList(Object obj, String key)` → `List<Object>` (fallback to empty)
- `getString(Object obj, String key)` → `String` (fallback to null)
- `getInt(Object obj, String key, int defaultValue)` → int
- Handles nested dict/list traversal safely (no ClassCastException)

## Package Structure

Mirrors Python for easy cross-reference during porting:

```
io.github.randomcodespace.iq.detector/
    Detector.java
    DetectorContext.java
    DetectorResult.java
    AbstractRegexDetector.java
    AbstractStructuredDetector.java
    DetectorUtils.java
    DetectorRegistry.java

    java/        (28 detectors)
    python/      (12 detectors)
    typescript/  (14 detectors)
    config/      (19 detectors)
    auth/        (4 detectors)
    frontend/    (6 detectors)
    go/          (4 detectors)
    csharp/      (4 detectors)
    rust/        (3 detectors)
    kotlin/      (3 detectors)
    shell/       (3 detectors)
    scala/       (2 detectors)
    cpp/         (2 detectors)
    docs/        (2 detectors)
    generic/     (2 detectors)
    proto/       (2 detectors)
    iac/         (4 detectors)
```

## Auto-Discovery

All detectors annotated with `@Component`. Spring scans the `detector` package.

```java
@Component
public class SpringRestDetector extends AbstractRegexDetector {
    @Override public String getName() { return "spring_rest"; }
    @Override public Set<String> getSupportedLanguages() { return Set.of("java"); }
    @Override public DetectorResult detect(DetectorContext ctx) { ... }
}
```

### DetectorRegistry

Spring service that collects and indexes all detectors:

```java
@Service
public class DetectorRegistry {
    private final List<Detector> allDetectors;           // sorted by name
    private final Map<String, List<Detector>> byLanguage; // pre-indexed

    public DetectorRegistry(List<Detector> detectors) {
        // Spring injects ALL Detector beans via constructor
        this.allDetectors = detectors.stream()
            .sorted(Comparator.comparing(Detector::getName))
            .toList();
        this.byLanguage = /* pre-built map from language -> detectors */;
    }

    public List<Detector> detectorsForLanguage(String language) {
        return byLanguage.getOrDefault(language, List.of());
    }

    public List<Detector> allDetectors() { return allDetectors; }
    public Optional<Detector> get(String name) { /* lookup by name */ }
}
```

Pre-indexed at startup — O(1) lookup per language, no per-file list filtering.

## Parallel Execution

Virtual threads — one per file, no pool size tuning:

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<FileResult>> futures = files.stream()
        .map(file -> executor.submit(() -> analyzeFile(file)))
        .toList();
    for (int i = 0; i < futures.size(); i++) {
        results[i] = futures.get(i).get();
    }
}
```

- One virtual thread per file
- Deterministic ordering via indexed result array
- Detectors are stateless singletons — safe for concurrent access
- `Pattern.compile()` produces immutable, thread-safe objects

## Determinism Guarantees

- All detectors are stateless — no mutable instance fields
- File list sorted before processing
- Result collection preserves file order (indexed array)
- Within a file: detectors run in sorted order by name
- Nodes/edges within a detector: collected in declaration order

## Structured Parsing

For YAML, JSON, XML, TOML, INI, Properties, SQL, Gradle files:

| File Type | Parser | Output |
|---|---|---|
| YAML | SnakeYAML | `Map<String, Object>` |
| JSON | Jackson ObjectMapper | `Map<String, Object>` |
| XML/POM | JAXB/DOM | `Document` or `Map` |
| TOML | toml4j | `Map<String, Object>` |
| INI/Properties | `java.util.Properties` | `Properties` |
| SQL | JSqlParser | parsed statements |
| Gradle | Regex (text passthrough) | raw String |

Parsed data passed via `DetectorContext.parsedData`. Structured detectors extend `AbstractStructuredDetector` for safe traversal.

## Testing Strategy

Each detector gets 3 tests minimum:

1. **Positive match** — sample code → expected nodes/edges
2. **Negative match** — non-matching code → empty result
3. **Determinism** — run twice, assert identical output

```java
public class DetectorTestUtils {
    public static DetectorContext contextFor(String language, String content) {
        return new DetectorContext("test.java", language, content, null, null);
    }

    public static void assertDeterministic(Detector detector, DetectorContext ctx) {
        DetectorResult r1 = detector.detect(ctx);
        DetectorResult r2 = detector.detect(ctx);
        assertEquals(r1.nodes(), r2.nodes());
        assertEquals(r1.edges(), r2.edges());
    }
}
```

## What This Does NOT Include (MVP 2)

- JavaParser integration for Java files (AST-level quality upgrade)
- ANTLR grammars for non-JVM languages
- Kotlin Compiler API integration
- Tree-sitter (removed entirely — replaced by JavaParser/ANTLR in MVP 2)
