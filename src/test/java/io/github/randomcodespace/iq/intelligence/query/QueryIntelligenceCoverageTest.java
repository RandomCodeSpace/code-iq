package io.github.randomcodespace.iq.intelligence.query;

import io.github.randomcodespace.iq.intelligence.CapabilityLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Additional coverage for intelligence/query package — branches not hit by
 * existing QueryPlannerTest.
 */
class QueryIntelligenceCoverageTest {

    // =====================================================================
    // CapabilityMatrix
    // =====================================================================
    @Nested
    class CapabilityMatrixCoverage {

        @Test
        void javaHasExactSymbolDefinitions() {
            Map<CapabilityDimension, CapabilityLevel> caps = CapabilityMatrix.forLanguage("java");
            assertThat(caps.get(CapabilityDimension.SYMBOL_DEFINITIONS)).isEqualTo(CapabilityLevel.EXACT);
        }

        @Test
        void javaHasExactImportResolution() {
            Map<CapabilityDimension, CapabilityLevel> caps = CapabilityMatrix.forLanguage("java");
            assertThat(caps.get(CapabilityDimension.IMPORT_RESOLUTION)).isEqualTo(CapabilityLevel.EXACT);
        }

        @Test
        void javaHasExactFrameworkSemantics() {
            Map<CapabilityDimension, CapabilityLevel> caps = CapabilityMatrix.forLanguage("java");
            assertThat(caps.get(CapabilityDimension.FRAMEWORK_SEMANTICS)).isEqualTo(CapabilityLevel.EXACT);
        }

        @Test
        void typescriptHasPartialCapabilities() {
            Map<CapabilityDimension, CapabilityLevel> caps = CapabilityMatrix.forLanguage("typescript");
            assertThat(caps.get(CapabilityDimension.SYMBOL_DEFINITIONS)).isEqualTo(CapabilityLevel.PARTIAL);
        }

        @Test
        void javascriptHasPartialCapabilities() {
            Map<CapabilityDimension, CapabilityLevel> caps = CapabilityMatrix.forLanguage("javascript");
            assertThat(caps.get(CapabilityDimension.SYMBOL_DEFINITIONS)).isEqualTo(CapabilityLevel.PARTIAL);
        }

        @Test
        void pythonHasPartialCapabilities() {
            Map<CapabilityDimension, CapabilityLevel> caps = CapabilityMatrix.forLanguage("python");
            assertThat(caps.get(CapabilityDimension.SYMBOL_DEFINITIONS)).isEqualTo(CapabilityLevel.PARTIAL);
        }

        @Test
        void goHasPartialCapabilities() {
            Map<CapabilityDimension, CapabilityLevel> caps = CapabilityMatrix.forLanguage("go");
            assertThat(caps.get(CapabilityDimension.SYMBOL_DEFINITIONS)).isEqualTo(CapabilityLevel.PARTIAL);
        }

        @Test
        void csharpHasPartialCapabilities() {
            Map<CapabilityDimension, CapabilityLevel> caps = CapabilityMatrix.forLanguage("csharp");
            assertThat(caps.get(CapabilityDimension.SYMBOL_DEFINITIONS)).isEqualTo(CapabilityLevel.PARTIAL);
        }

        @Test
        void rustHasPartialCapabilities() {
            Map<CapabilityDimension, CapabilityLevel> caps = CapabilityMatrix.forLanguage("rust");
            assertThat(caps.get(CapabilityDimension.SYMBOL_DEFINITIONS)).isEqualTo(CapabilityLevel.PARTIAL);
        }

        @Test
        void kotlinHasLexicalOnlyCapabilities() {
            Map<CapabilityDimension, CapabilityLevel> caps = CapabilityMatrix.forLanguage("kotlin");
            assertThat(caps.get(CapabilityDimension.SYMBOL_DEFINITIONS)).isEqualTo(CapabilityLevel.LEXICAL_ONLY);
        }

        @Test
        void shellHasLexicalOnlyCapabilities() {
            Map<CapabilityDimension, CapabilityLevel> caps = CapabilityMatrix.forLanguage("shell");
            assertThat(caps.get(CapabilityDimension.SYMBOL_DEFINITIONS)).isEqualTo(CapabilityLevel.LEXICAL_ONLY);
        }

        @Test
        void unknownLanguageHasUnsupportedCapabilities() {
            Map<CapabilityDimension, CapabilityLevel> caps = CapabilityMatrix.forLanguage("cobol");
            assertThat(caps.get(CapabilityDimension.SYMBOL_DEFINITIONS)).isEqualTo(CapabilityLevel.UNSUPPORTED);
        }

        @Test
        void nullLanguageReturnsUnsupported() {
            Map<CapabilityDimension, CapabilityLevel> caps = CapabilityMatrix.forLanguage(null);
            assertThat(caps.get(CapabilityDimension.SYMBOL_DEFINITIONS)).isEqualTo(CapabilityLevel.UNSUPPORTED);
        }

        @Test
        void allDimensionsPresentForJava() {
            Map<CapabilityDimension, CapabilityLevel> caps = CapabilityMatrix.forLanguage("java");
            for (CapabilityDimension dim : CapabilityDimension.values()) {
                assertThat(caps).as("Java capabilities must include dimension %s", dim).containsKey(dim);
            }
        }

        @Test
        void deterministicForSameLanguage() {
            Map<CapabilityDimension, CapabilityLevel> caps1 = CapabilityMatrix.forLanguage("typescript");
            Map<CapabilityDimension, CapabilityLevel> caps2 = CapabilityMatrix.forLanguage("typescript");
            assertThat(caps1).isEqualTo(caps2);
        }
    }

    // =====================================================================
    // QueryPlan — record methods
    // =====================================================================
    @Nested
    class QueryPlanCoverage {

        @Test
        void graphFirstPlanUsesGraphNotLexical() {
            QueryPlan plan = QueryPlan.of(QueryType.FIND_SYMBOL, "java",
                    QueryRoute.GRAPH_FIRST, CapabilityMatrix.forLanguage("java"));
            assertThat(plan.usesGraph()).isTrue();
            assertThat(plan.usesLexical()).isFalse();
        }

        @Test
        void lexicalFirstPlanUsesLexicalNotGraph() {
            QueryPlan plan = QueryPlan.of(QueryType.FIND_SYMBOL, "kotlin",
                    QueryRoute.LEXICAL_FIRST, CapabilityMatrix.forLanguage("kotlin"));
            assertThat(plan.usesGraph()).isFalse();
            assertThat(plan.usesLexical()).isTrue();
        }

        @Test
        void mergedPlanUsesBothGraphAndLexical() {
            QueryPlan plan = QueryPlan.of(QueryType.FIND_SYMBOL, "typescript",
                    QueryRoute.MERGED, CapabilityMatrix.forLanguage("typescript"));
            assertThat(plan.usesGraph()).isTrue();
            assertThat(plan.usesLexical()).isTrue();
        }

        @Test
        void degradedPlanUsesNeitherGraphNorLexical() {
            QueryPlan plan = new QueryPlan(QueryType.FIND_SYMBOL, "brainfuck",
                    QueryRoute.DEGRADED, CapabilityMatrix.forLanguage("brainfuck"),
                    "Not supported");
            assertThat(plan.usesGraph()).isFalse();
            assertThat(plan.usesLexical()).isFalse();
        }

        @Test
        void planOfFactoryHasNullDegradationNote() {
            QueryPlan plan = QueryPlan.of(QueryType.FIND_CALLERS, "java",
                    QueryRoute.GRAPH_FIRST, CapabilityMatrix.forLanguage("java"));
            assertThat(plan.degradationNote()).isNull();
        }

        @Test
        void planRecordFieldsAccessible() {
            QueryPlan plan = new QueryPlan(QueryType.FIND_REFERENCES, "python",
                    QueryRoute.MERGED, CapabilityMatrix.forLanguage("python"), null);
            assertThat(plan.queryType()).isEqualTo(QueryType.FIND_REFERENCES);
            assertThat(plan.language()).isEqualTo("python");
            assertThat(plan.route()).isEqualTo(QueryRoute.MERGED);
            assertThat(plan.capabilities()).isNotNull();
            assertThat(plan.degradationNote()).isNull();
        }
    }

    // =====================================================================
    // QueryPlanner — additional routing branches
    // =====================================================================
    @Nested
    class QueryPlannerCoverage {
        private QueryPlanner planner;

        @BeforeEach
        void setUp() {
            planner = new QueryPlanner();
        }

        @Test
        void csharpFindSymbolRoutesMerged() {
            QueryPlan plan = planner.plan(QueryType.FIND_SYMBOL, "csharp");
            assertThat(plan.route()).isEqualTo(QueryRoute.MERGED);
        }

        @Test
        void rustFindCallerRoutesMerged() {
            QueryPlan plan = planner.plan(QueryType.FIND_CALLERS, "rust");
            assertThat(plan.route()).isEqualTo(QueryRoute.MERGED);
        }

        @Test
        void goFindConfigRoutesMerged() {
            QueryPlan plan = planner.plan(QueryType.FIND_CONFIG, "go");
            assertThat(plan.route()).isEqualTo(QueryRoute.MERGED);
        }

        @Test
        void kotlinFindDependenciesRoutesLexicalFirst() {
            QueryPlan plan = planner.plan(QueryType.FIND_DEPENDENCIES, "kotlin");
            assertThat(plan.route()).isEqualTo(QueryRoute.LEXICAL_FIRST);
            assertThat(plan.degradationNote()).isNotBlank();
        }

        @Test
        void scaladFindReferencesRoutesLexicalFirst() {
            QueryPlan plan = planner.plan(QueryType.FIND_REFERENCES, "scala");
            assertThat(plan.route()).isEqualTo(QueryRoute.LEXICAL_FIRST);
        }

        @Test
        void javadFindReferencesRoutesGraphFirst() {
            QueryPlan plan = planner.plan(QueryType.FIND_REFERENCES, "java");
            assertThat(plan.route()).isEqualTo(QueryRoute.GRAPH_FIRST);
        }

        @Test
        void javaFindConfigRoutesGraphFirst() {
            QueryPlan plan = planner.plan(QueryType.FIND_CONFIG, "java");
            assertThat(plan.route()).isEqualTo(QueryRoute.GRAPH_FIRST);
        }

        @Test
        void degradedNoteContainsSupportedLanguageHint() {
            QueryPlan plan = planner.plan(QueryType.FIND_SYMBOL, "cobol");
            assertThat(plan.degradationNote()).containsIgnoringCase("java");
        }

        @Test
        void lexicalNoteContainsQueryType() {
            QueryPlan plan = planner.plan(QueryType.FIND_CALLERS, "shell");
            assertThat(plan.degradationNote()).containsIgnoringCase("FIND_CALLERS");
        }

        @Test
        void searchTextAlwaysLexicalFirstForAnyLanguage() {
            for (String lang : new String[]{"java", "typescript", "python", "go", "kotlin", "rust", "cobol"}) {
                QueryPlan plan = planner.plan(QueryType.SEARCH_TEXT, lang);
                assertThat(plan.route())
                        .as("SEARCH_TEXT should always be LEXICAL_FIRST for %s", lang)
                        .isEqualTo(QueryRoute.LEXICAL_FIRST);
                // SEARCH_TEXT is not degraded even for unsupported languages
                assertThat(plan.degradationNote()).isNull();
            }
        }

        @Test
        void planHasAllCapabilityDimensionsPopulated() {
            QueryPlan plan = planner.plan(QueryType.FIND_SYMBOL, "go");
            assertThat(plan.capabilities()).hasSize(CapabilityDimension.values().length);
        }
    }

    // =====================================================================
    // QueryRoute enum — coverage
    // =====================================================================
    @Nested
    class QueryRouteCoverage {
        @Test
        void allRouteValuesAccessible() {
            assertThat(QueryRoute.values()).containsExactlyInAnyOrder(
                    QueryRoute.GRAPH_FIRST,
                    QueryRoute.MERGED,
                    QueryRoute.LEXICAL_FIRST,
                    QueryRoute.DEGRADED
            );
        }
    }

    // =====================================================================
    // QueryType enum — coverage
    // =====================================================================
    @Nested
    class QueryTypeCoverage {
        @Test
        void allQueryTypeValuesAccessible() {
            assertThat(QueryType.values()).contains(
                    QueryType.FIND_SYMBOL,
                    QueryType.FIND_REFERENCES,
                    QueryType.FIND_CALLERS,
                    QueryType.FIND_DEPENDENCIES,
                    QueryType.SEARCH_TEXT,
                    QueryType.FIND_CONFIG
            );
        }
    }
}
