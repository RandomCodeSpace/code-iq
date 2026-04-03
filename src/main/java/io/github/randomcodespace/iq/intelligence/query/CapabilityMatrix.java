package io.github.randomcodespace.iq.intelligence.query;

import io.github.randomcodespace.iq.intelligence.CapabilityLevel;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Static, deterministic capability matrix that declares per-language analysis
 * capability levels for each {@link CapabilityDimension}.
 *
 * <p>Levels reflect what the current detector suite actually provides:
 * <ul>
 *   <li><b>Java</b> — 27 detectors with JavaParser AST → {@code EXACT} for most dimensions.</li>
 *   <li><b>TypeScript / JavaScript</b> — ANTLR grammar-based → {@code PARTIAL}.</li>
 *   <li><b>Python</b> — ANTLR grammar-based → {@code PARTIAL}.</li>
 *   <li><b>Go</b> — ANTLR grammar-based → {@code PARTIAL}.</li>
 *   <li><b>C# / Rust</b> — ANTLR grammar-based → {@code PARTIAL}.</li>
 *   <li><b>Kotlin / Scala / Ruby / PHP / Shell / Markdown</b> — regex only → {@code LEXICAL_ONLY}.</li>
 *   <li><b>Everything else</b> — {@code UNSUPPORTED}.</li>
 * </ul>
 *
 * <p>This class is intentionally non-instantiable. Use the static {@link #get} methods.
 */
public final class CapabilityMatrix {

    // ------------------------------------------------------------------
    // Language normalisation
    // ------------------------------------------------------------------

    /** Languages with ANTLR or JavaParser AST-level support. */
    private static final Set<String> ANTLR_LANGUAGES =
            Set.of("typescript", "javascript", "python", "go", "csharp", "rust", "cpp");

    /** Languages with regex/text-only detection (no grammar). */
    private static final Set<String> LEXICAL_ONLY_LANGUAGES =
            Set.of("kotlin", "scala", "ruby", "php", "shell", "bash", "powershell",
                   "markdown", "proto", "hcl", "terraform", "dockerfile", "yaml",
                   "json", "toml", "ini", "properties", "xml", "sql");

    // ------------------------------------------------------------------
    // Per-language dimension tables
    // ------------------------------------------------------------------

    /** Java: full AST analysis via JavaParser — highest fidelity. */
    private static final Map<CapabilityDimension, CapabilityLevel> JAVA_CAPS;

    /** TypeScript: ANTLR grammar — good structural coverage. */
    private static final Map<CapabilityDimension, CapabilityLevel> TYPESCRIPT_CAPS;

    /** JavaScript: same grammar as TypeScript, no static types. */
    private static final Map<CapabilityDimension, CapabilityLevel> JAVASCRIPT_CAPS;

    /** Python: ANTLR grammar — class/function/import aware. */
    private static final Map<CapabilityDimension, CapabilityLevel> PYTHON_CAPS;

    /** Go: ANTLR grammar — struct/interface/import aware. */
    private static final Map<CapabilityDimension, CapabilityLevel> GO_CAPS;

    /** C#: ANTLR grammar — partial coverage. */
    private static final Map<CapabilityDimension, CapabilityLevel> CSHARP_CAPS;

    /** Rust: ANTLR grammar — partial structural coverage. */
    private static final Map<CapabilityDimension, CapabilityLevel> RUST_CAPS;

    /** C++: ANTLR grammar — partial structural coverage, no ORM convention. */
    private static final Map<CapabilityDimension, CapabilityLevel> CPP_CAPS;

    /** Fallback for regex-only languages. */
    private static final Map<CapabilityDimension, CapabilityLevel> LEXICAL_ONLY_CAPS;

    /** Fallback for completely unsupported languages. */
    private static final Map<CapabilityDimension, CapabilityLevel> UNSUPPORTED_CAPS;

    static {
        JAVA_CAPS = immutableDimMap(
                CapabilityDimension.SYMBOL_DEFINITIONS,  CapabilityLevel.EXACT,
                CapabilityDimension.SYMBOL_REFERENCES,   CapabilityLevel.EXACT,
                CapabilityDimension.IMPORT_RESOLUTION,   CapabilityLevel.EXACT,
                CapabilityDimension.TYPE_INFO,            CapabilityLevel.EXACT,
                CapabilityDimension.CLASS_HIERARCHY,      CapabilityLevel.EXACT,
                CapabilityDimension.FRAMEWORK_SEMANTICS,  CapabilityLevel.EXACT,
                CapabilityDimension.ORM_ENTITY_MAPPING,   CapabilityLevel.EXACT,
                CapabilityDimension.AUTH_SECURITY,        CapabilityLevel.EXACT,
                CapabilityDimension.ASYNC_PATTERNS,       CapabilityLevel.PARTIAL
        );

        TYPESCRIPT_CAPS = immutableDimMap(
                CapabilityDimension.SYMBOL_DEFINITIONS,  CapabilityLevel.PARTIAL,
                CapabilityDimension.SYMBOL_REFERENCES,   CapabilityLevel.PARTIAL,
                CapabilityDimension.IMPORT_RESOLUTION,   CapabilityLevel.PARTIAL,
                CapabilityDimension.TYPE_INFO,            CapabilityLevel.PARTIAL,
                CapabilityDimension.CLASS_HIERARCHY,      CapabilityLevel.PARTIAL,
                CapabilityDimension.FRAMEWORK_SEMANTICS,  CapabilityLevel.PARTIAL,
                CapabilityDimension.ORM_ENTITY_MAPPING,   CapabilityLevel.PARTIAL,
                CapabilityDimension.AUTH_SECURITY,        CapabilityLevel.PARTIAL,
                CapabilityDimension.ASYNC_PATTERNS,       CapabilityLevel.PARTIAL
        );

        JAVASCRIPT_CAPS = immutableDimMap(
                CapabilityDimension.SYMBOL_DEFINITIONS,  CapabilityLevel.PARTIAL,
                CapabilityDimension.SYMBOL_REFERENCES,   CapabilityLevel.PARTIAL,
                CapabilityDimension.IMPORT_RESOLUTION,   CapabilityLevel.PARTIAL,
                CapabilityDimension.TYPE_INFO,            CapabilityLevel.LEXICAL_ONLY,
                CapabilityDimension.CLASS_HIERARCHY,      CapabilityLevel.PARTIAL,
                CapabilityDimension.FRAMEWORK_SEMANTICS,  CapabilityLevel.PARTIAL,
                CapabilityDimension.ORM_ENTITY_MAPPING,   CapabilityLevel.PARTIAL,
                CapabilityDimension.AUTH_SECURITY,        CapabilityLevel.PARTIAL,
                CapabilityDimension.ASYNC_PATTERNS,       CapabilityLevel.PARTIAL
        );

        PYTHON_CAPS = immutableDimMap(
                CapabilityDimension.SYMBOL_DEFINITIONS,  CapabilityLevel.PARTIAL,
                CapabilityDimension.SYMBOL_REFERENCES,   CapabilityLevel.PARTIAL,
                CapabilityDimension.IMPORT_RESOLUTION,   CapabilityLevel.PARTIAL,
                CapabilityDimension.TYPE_INFO,            CapabilityLevel.LEXICAL_ONLY,
                CapabilityDimension.CLASS_HIERARCHY,      CapabilityLevel.PARTIAL,
                CapabilityDimension.FRAMEWORK_SEMANTICS,  CapabilityLevel.PARTIAL,
                CapabilityDimension.ORM_ENTITY_MAPPING,   CapabilityLevel.PARTIAL,
                CapabilityDimension.AUTH_SECURITY,        CapabilityLevel.PARTIAL,
                CapabilityDimension.ASYNC_PATTERNS,       CapabilityLevel.PARTIAL
        );

        GO_CAPS = immutableDimMap(
                CapabilityDimension.SYMBOL_DEFINITIONS,  CapabilityLevel.PARTIAL,
                CapabilityDimension.SYMBOL_REFERENCES,   CapabilityLevel.PARTIAL,
                CapabilityDimension.IMPORT_RESOLUTION,   CapabilityLevel.PARTIAL,
                CapabilityDimension.TYPE_INFO,            CapabilityLevel.PARTIAL,
                CapabilityDimension.CLASS_HIERARCHY,      CapabilityLevel.LEXICAL_ONLY,
                CapabilityDimension.FRAMEWORK_SEMANTICS,  CapabilityLevel.PARTIAL,
                CapabilityDimension.ORM_ENTITY_MAPPING,   CapabilityLevel.PARTIAL,
                CapabilityDimension.AUTH_SECURITY,        CapabilityLevel.LEXICAL_ONLY,
                CapabilityDimension.ASYNC_PATTERNS,       CapabilityLevel.PARTIAL
        );

        CSHARP_CAPS = immutableDimMap(
                CapabilityDimension.SYMBOL_DEFINITIONS,  CapabilityLevel.PARTIAL,
                CapabilityDimension.SYMBOL_REFERENCES,   CapabilityLevel.PARTIAL,
                CapabilityDimension.IMPORT_RESOLUTION,   CapabilityLevel.PARTIAL,
                CapabilityDimension.TYPE_INFO,            CapabilityLevel.PARTIAL,
                CapabilityDimension.CLASS_HIERARCHY,      CapabilityLevel.PARTIAL,
                CapabilityDimension.FRAMEWORK_SEMANTICS,  CapabilityLevel.PARTIAL,
                CapabilityDimension.ORM_ENTITY_MAPPING,   CapabilityLevel.PARTIAL,
                CapabilityDimension.AUTH_SECURITY,        CapabilityLevel.PARTIAL,
                CapabilityDimension.ASYNC_PATTERNS,       CapabilityLevel.PARTIAL
        );

        RUST_CAPS = immutableDimMap(
                CapabilityDimension.SYMBOL_DEFINITIONS,  CapabilityLevel.PARTIAL,
                CapabilityDimension.SYMBOL_REFERENCES,   CapabilityLevel.PARTIAL,
                CapabilityDimension.IMPORT_RESOLUTION,   CapabilityLevel.PARTIAL,
                CapabilityDimension.TYPE_INFO,            CapabilityLevel.PARTIAL,
                CapabilityDimension.CLASS_HIERARCHY,      CapabilityLevel.LEXICAL_ONLY,
                CapabilityDimension.FRAMEWORK_SEMANTICS,  CapabilityLevel.PARTIAL,
                CapabilityDimension.ORM_ENTITY_MAPPING,   CapabilityLevel.UNSUPPORTED,
                CapabilityDimension.AUTH_SECURITY,        CapabilityLevel.LEXICAL_ONLY,
                CapabilityDimension.ASYNC_PATTERNS,       CapabilityLevel.PARTIAL
        );

        CPP_CAPS = immutableDimMap(
                CapabilityDimension.SYMBOL_DEFINITIONS,  CapabilityLevel.PARTIAL,
                CapabilityDimension.SYMBOL_REFERENCES,   CapabilityLevel.PARTIAL,
                CapabilityDimension.IMPORT_RESOLUTION,   CapabilityLevel.PARTIAL,
                CapabilityDimension.TYPE_INFO,            CapabilityLevel.PARTIAL,
                CapabilityDimension.CLASS_HIERARCHY,      CapabilityLevel.PARTIAL,
                CapabilityDimension.FRAMEWORK_SEMANTICS,  CapabilityLevel.PARTIAL,
                CapabilityDimension.ORM_ENTITY_MAPPING,   CapabilityLevel.UNSUPPORTED,
                CapabilityDimension.AUTH_SECURITY,        CapabilityLevel.LEXICAL_ONLY,
                CapabilityDimension.ASYNC_PATTERNS,       CapabilityLevel.PARTIAL
        );

        LEXICAL_ONLY_CAPS = immutableDimMap(
                CapabilityDimension.SYMBOL_DEFINITIONS,  CapabilityLevel.LEXICAL_ONLY,
                CapabilityDimension.SYMBOL_REFERENCES,   CapabilityLevel.LEXICAL_ONLY,
                CapabilityDimension.IMPORT_RESOLUTION,   CapabilityLevel.LEXICAL_ONLY,
                CapabilityDimension.TYPE_INFO,            CapabilityLevel.UNSUPPORTED,
                CapabilityDimension.CLASS_HIERARCHY,      CapabilityLevel.LEXICAL_ONLY,
                CapabilityDimension.FRAMEWORK_SEMANTICS,  CapabilityLevel.LEXICAL_ONLY,
                CapabilityDimension.ORM_ENTITY_MAPPING,   CapabilityLevel.UNSUPPORTED,
                CapabilityDimension.AUTH_SECURITY,        CapabilityLevel.LEXICAL_ONLY,
                CapabilityDimension.ASYNC_PATTERNS,       CapabilityLevel.LEXICAL_ONLY
        );

        UNSUPPORTED_CAPS = immutableDimMap(
                CapabilityDimension.SYMBOL_DEFINITIONS,  CapabilityLevel.UNSUPPORTED,
                CapabilityDimension.SYMBOL_REFERENCES,   CapabilityLevel.UNSUPPORTED,
                CapabilityDimension.IMPORT_RESOLUTION,   CapabilityLevel.UNSUPPORTED,
                CapabilityDimension.TYPE_INFO,            CapabilityLevel.UNSUPPORTED,
                CapabilityDimension.CLASS_HIERARCHY,      CapabilityLevel.UNSUPPORTED,
                CapabilityDimension.FRAMEWORK_SEMANTICS,  CapabilityLevel.UNSUPPORTED,
                CapabilityDimension.ORM_ENTITY_MAPPING,   CapabilityLevel.UNSUPPORTED,
                CapabilityDimension.AUTH_SECURITY,        CapabilityLevel.UNSUPPORTED,
                CapabilityDimension.ASYNC_PATTERNS,       CapabilityLevel.UNSUPPORTED
        );
    }

    private CapabilityMatrix() {}

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Returns the full dimension-to-level map for {@code language}.
     * The map is sorted by {@link CapabilityDimension} ordinal order (deterministic).
     *
     * @param language normalised lowercase language name
     * @return immutable capability map; never {@code null}
     */
    public static Map<CapabilityDimension, CapabilityLevel> forLanguage(String language) {
        return tableFor(normalise(language));
    }

    /**
     * Returns the capability level for a single {@code language} / {@code dimension} pair.
     *
     * @param language  normalised lowercase language name
     * @param dimension the capability dimension to query
     * @return the capability level; never {@code null}
     */
    public static CapabilityLevel get(String language, CapabilityDimension dimension) {
        return tableFor(normalise(language)).getOrDefault(dimension, CapabilityLevel.UNSUPPORTED);
    }

    /**
     * Returns the full matrix as a serialisation-friendly nested map.
     * Outer keys are language names (sorted), inner keys are dimension names, values are level names.
     * Deterministic by construction.
     */
    public static Map<String, Map<String, String>> asSerializableMap() {
        Map<String, Map<String, String>> result = new TreeMap<>();
        for (String lang : new String[]{
                "java", "typescript", "javascript", "python", "go", "csharp", "rust", "cpp",
                "kotlin", "scala", "ruby", "php", "shell"}) {
            Map<CapabilityDimension, CapabilityLevel> caps = tableFor(lang);
            Map<String, String> row = new LinkedHashMap<>();
            for (CapabilityDimension dim : CapabilityDimension.values()) {
                row.put(dim.name().toLowerCase(), caps.getOrDefault(dim, CapabilityLevel.UNSUPPORTED).name());
            }
            result.put(lang, row);
        }
        return Collections.unmodifiableMap(result);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static String normalise(String language) {
        if (language == null) return "";
        return language.strip().toLowerCase();
    }

    private static Map<CapabilityDimension, CapabilityLevel> tableFor(String lang) {
        return switch (lang) {
            case "java"       -> JAVA_CAPS;
            case "typescript" -> TYPESCRIPT_CAPS;
            case "javascript" -> JAVASCRIPT_CAPS;
            case "python"     -> PYTHON_CAPS;
            case "go"         -> GO_CAPS;
            case "csharp", "c#" -> CSHARP_CAPS;
            case "cpp", "c++"   -> CPP_CAPS;
            case "rust"       -> RUST_CAPS;
            default -> {
                if (LEXICAL_ONLY_LANGUAGES.contains(lang)) yield LEXICAL_ONLY_CAPS;
                if (ANTLR_LANGUAGES.contains(lang))        yield CSHARP_CAPS; // cpp etc → PARTIAL
                yield UNSUPPORTED_CAPS;
            }
        };
    }

    /**
     * Varargs helper: alternating {@code (dimension, level)} pairs → immutable EnumMap.
     */
    private static Map<CapabilityDimension, CapabilityLevel> immutableDimMap(Object... pairs) {
        EnumMap<CapabilityDimension, CapabilityLevel> map = new EnumMap<>(CapabilityDimension.class);
        for (int i = 0; i < pairs.length - 1; i += 2) {
            map.put((CapabilityDimension) pairs[i], (CapabilityLevel) pairs[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }
}
