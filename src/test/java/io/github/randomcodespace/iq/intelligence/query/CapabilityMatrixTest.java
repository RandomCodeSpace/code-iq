package io.github.randomcodespace.iq.intelligence.query;

import io.github.randomcodespace.iq.intelligence.CapabilityLevel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CapabilityMatrix}.
 * Validates per-language capability lookups and matrix completeness.
 */
class CapabilityMatrixTest {

    // ------------------------------------------------------------------
    // Java — highest fidelity
    // ------------------------------------------------------------------

    @Test
    void java_symbolDefinitions_isExact() {
        assertThat(CapabilityMatrix.get("java", CapabilityDimension.SYMBOL_DEFINITIONS))
                .isEqualTo(CapabilityLevel.EXACT);
    }

    @Test
    void java_frameworkSemantics_isExact() {
        assertThat(CapabilityMatrix.get("java", CapabilityDimension.FRAMEWORK_SEMANTICS))
                .isEqualTo(CapabilityLevel.EXACT);
    }

    @Test
    void java_ormEntityMapping_isExact() {
        assertThat(CapabilityMatrix.get("java", CapabilityDimension.ORM_ENTITY_MAPPING))
                .isEqualTo(CapabilityLevel.EXACT);
    }

    @Test
    void java_allDimensionsPopulated() {
        Map<CapabilityDimension, CapabilityLevel> caps = CapabilityMatrix.forLanguage("java");
        assertThat(caps).containsKeys(CapabilityDimension.values());
    }

    // ------------------------------------------------------------------
    // TypeScript — PARTIAL across most dimensions
    // ------------------------------------------------------------------

    @Test
    void typescript_symbolDefinitions_isPartial() {
        assertThat(CapabilityMatrix.get("typescript", CapabilityDimension.SYMBOL_DEFINITIONS))
                .isEqualTo(CapabilityLevel.PARTIAL);
    }

    @Test
    void typescript_allDimensionsPopulated() {
        Map<CapabilityDimension, CapabilityLevel> caps = CapabilityMatrix.forLanguage("typescript");
        assertThat(caps).containsKeys(CapabilityDimension.values());
    }

    // ------------------------------------------------------------------
    // Python — PARTIAL structural, LEXICAL_ONLY for type info
    // ------------------------------------------------------------------

    @Test
    void python_symbolDefinitions_isPartial() {
        assertThat(CapabilityMatrix.get("python", CapabilityDimension.SYMBOL_DEFINITIONS))
                .isEqualTo(CapabilityLevel.PARTIAL);
    }

    @Test
    void python_typeInfo_isLexicalOnly() {
        assertThat(CapabilityMatrix.get("python", CapabilityDimension.TYPE_INFO))
                .isEqualTo(CapabilityLevel.LEXICAL_ONLY);
    }

    // ------------------------------------------------------------------
    // Lexical-only languages
    // ------------------------------------------------------------------

    @Test
    void kotlin_symbolDefinitions_isLexicalOnly() {
        assertThat(CapabilityMatrix.get("kotlin", CapabilityDimension.SYMBOL_DEFINITIONS))
                .isEqualTo(CapabilityLevel.LEXICAL_ONLY);
    }

    @Test
    void kotlin_typeInfo_isUnsupported() {
        assertThat(CapabilityMatrix.get("kotlin", CapabilityDimension.TYPE_INFO))
                .isEqualTo(CapabilityLevel.UNSUPPORTED);
    }

    @Test
    void shell_ormEntityMapping_isUnsupported() {
        assertThat(CapabilityMatrix.get("shell", CapabilityDimension.ORM_ENTITY_MAPPING))
                .isEqualTo(CapabilityLevel.UNSUPPORTED);
    }

    // ------------------------------------------------------------------
    // Unknown language → UNSUPPORTED
    // ------------------------------------------------------------------

    @Test
    void unknownLanguage_allUnsupported() {
        Map<CapabilityDimension, CapabilityLevel> caps = CapabilityMatrix.forLanguage("brainfuck");
        assertThat(caps.values()).allMatch(l -> l == CapabilityLevel.UNSUPPORTED);
    }

    @Test
    void nullLanguage_allUnsupported() {
        Map<CapabilityDimension, CapabilityLevel> caps = CapabilityMatrix.forLanguage(null);
        assertThat(caps.values()).allMatch(l -> l == CapabilityLevel.UNSUPPORTED);
    }

    @Test
    void caseNormalisation_java_upper() {
        assertThat(CapabilityMatrix.get("JAVA", CapabilityDimension.SYMBOL_DEFINITIONS))
                .isEqualTo(CapabilityLevel.EXACT);
    }

    @Test
    void caseNormalisation_java_mixed() {
        assertThat(CapabilityMatrix.get("Java", CapabilityDimension.FRAMEWORK_SEMANTICS))
                .isEqualTo(CapabilityLevel.EXACT);
    }

    // ------------------------------------------------------------------
    // Rust — ORM is UNSUPPORTED
    // ------------------------------------------------------------------

    @Test
    void rust_ormEntityMapping_isUnsupported() {
        assertThat(CapabilityMatrix.get("rust", CapabilityDimension.ORM_ENTITY_MAPPING))
                .isEqualTo(CapabilityLevel.UNSUPPORTED);
    }

    @Test
    void rust_symbolDefinitions_isPartial() {
        assertThat(CapabilityMatrix.get("rust", CapabilityDimension.SYMBOL_DEFINITIONS))
                .isEqualTo(CapabilityLevel.PARTIAL);
    }

    // ------------------------------------------------------------------
    // asSerializableMap — determinism
    // ------------------------------------------------------------------

    @Test
    void serializableMap_isDeterministic() {
        Map<String, Map<String, String>> first  = CapabilityMatrix.asSerializableMap();
        Map<String, Map<String, String>> second = CapabilityMatrix.asSerializableMap();
        assertThat(first).isEqualTo(second);
    }

    @Test
    void serializableMap_containsExpectedLanguages() {
        Map<String, Map<String, String>> matrix = CapabilityMatrix.asSerializableMap();
        assertThat(matrix).containsKeys("java", "typescript", "javascript", "python", "go", "csharp", "rust", "cpp");
    }

    @Test
    void serializableMap_allDimensionsCovered() {
        Map<String, Map<String, String>> matrix = CapabilityMatrix.asSerializableMap();
        for (Map.Entry<String, Map<String, String>> entry : matrix.entrySet()) {
            assertThat(entry.getValue()).as("language=%s", entry.getKey())
                    .hasSize(CapabilityDimension.values().length);
        }
    }
}
