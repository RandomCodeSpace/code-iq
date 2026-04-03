package io.github.randomcodespace.iq.intelligence.query;

import io.github.randomcodespace.iq.intelligence.CapabilityLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit and integration tests for {@link QueryPlanner}.
 * Covers each routing path and the determinism contract.
 */
class QueryPlannerTest {

    private QueryPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new QueryPlanner();
    }

    // ------------------------------------------------------------------
    // GRAPH_FIRST path — Java has EXACT capability
    // ------------------------------------------------------------------

    @Test
    void java_findSymbol_routesGraphFirst() {
        QueryPlan plan = planner.plan(QueryType.FIND_SYMBOL, "java");
        assertThat(plan.route()).isEqualTo(QueryRoute.GRAPH_FIRST);
        assertThat(plan.usesGraph()).isTrue();
        assertThat(plan.usesLexical()).isFalse();
        assertThat(plan.degradationNote()).isNull();
    }

    @Test
    void java_findCallers_routesGraphFirst() {
        QueryPlan plan = planner.plan(QueryType.FIND_CALLERS, "java");
        assertThat(plan.route()).isEqualTo(QueryRoute.GRAPH_FIRST);
        assertThat(plan.degradationNote()).isNull();
    }

    @Test
    void java_findDependencies_routesGraphFirst() {
        QueryPlan plan = planner.plan(QueryType.FIND_DEPENDENCIES, "java");
        assertThat(plan.route()).isEqualTo(QueryRoute.GRAPH_FIRST);
        assertThat(plan.degradationNote()).isNull();
    }

    @Test
    void java_findConfig_routesGraphFirst() {
        QueryPlan plan = planner.plan(QueryType.FIND_CONFIG, "java");
        assertThat(plan.route()).isEqualTo(QueryRoute.GRAPH_FIRST);
        assertThat(plan.degradationNote()).isNull();
    }

    // ------------------------------------------------------------------
    // MERGED path — TypeScript/Python have PARTIAL capability
    // ------------------------------------------------------------------

    @Test
    void typescript_findSymbol_routesMerged() {
        QueryPlan plan = planner.plan(QueryType.FIND_SYMBOL, "typescript");
        assertThat(plan.route()).isEqualTo(QueryRoute.MERGED);
        assertThat(plan.usesGraph()).isTrue();
        assertThat(plan.usesLexical()).isTrue();
        assertThat(plan.degradationNote()).isNull();
    }

    @Test
    void python_findCallers_routesMerged() {
        QueryPlan plan = planner.plan(QueryType.FIND_CALLERS, "python");
        assertThat(plan.route()).isEqualTo(QueryRoute.MERGED);
        assertThat(plan.degradationNote()).isNull();
    }

    @Test
    void go_findDependencies_routesMerged() {
        QueryPlan plan = planner.plan(QueryType.FIND_DEPENDENCIES, "go");
        assertThat(plan.route()).isEqualTo(QueryRoute.MERGED);
        assertThat(plan.degradationNote()).isNull();
    }

    // ------------------------------------------------------------------
    // LEXICAL_FIRST path — lexical-only languages
    // ------------------------------------------------------------------

    @Test
    void kotlin_findSymbol_routesLexicalFirst() {
        QueryPlan plan = planner.plan(QueryType.FIND_SYMBOL, "kotlin");
        assertThat(plan.route()).isEqualTo(QueryRoute.LEXICAL_FIRST);
        assertThat(plan.usesGraph()).isFalse();
        assertThat(plan.usesLexical()).isTrue();
        assertThat(plan.degradationNote()).isNotBlank();
    }

    @Test
    void shell_findCallers_routesLexicalFirst() {
        QueryPlan plan = planner.plan(QueryType.FIND_CALLERS, "shell");
        assertThat(plan.route()).isEqualTo(QueryRoute.LEXICAL_FIRST);
        assertThat(plan.degradationNote()).isNotBlank();
    }

    // ------------------------------------------------------------------
    // DEGRADED path — unsupported language or dimension
    // ------------------------------------------------------------------

    @Test
    void unknownLanguage_findSymbol_routesDegraded() {
        QueryPlan plan = planner.plan(QueryType.FIND_SYMBOL, "brainfuck");
        assertThat(plan.route()).isEqualTo(QueryRoute.DEGRADED);
        assertThat(plan.usesGraph()).isFalse();
        assertThat(plan.usesLexical()).isFalse();
        assertThat(plan.degradationNote()).isNotBlank();
    }

    @Test
    void rust_findSymbol_routesMerged_notDegraded() {
        // Rust SYMBOL_DEFINITIONS is PARTIAL — should be MERGED, not DEGRADED
        QueryPlan plan = planner.plan(QueryType.FIND_SYMBOL, "rust");
        assertThat(plan.route()).isEqualTo(QueryRoute.MERGED);
    }

    @Test
    void degradedPlan_hasExplanatoryNote() {
        QueryPlan plan = planner.plan(QueryType.FIND_SYMBOL, "brainfuck");
        assertThat(plan.degradationNote())
                .contains("FIND_SYMBOL")
                .contains("brainfuck");
    }

    // ------------------------------------------------------------------
    // LEXICAL_FIRST always — SEARCH_TEXT
    // ------------------------------------------------------------------

    @Test
    void java_searchText_routesLexicalFirst() {
        QueryPlan plan = planner.plan(QueryType.SEARCH_TEXT, "java");
        assertThat(plan.route()).isEqualTo(QueryRoute.LEXICAL_FIRST);
        assertThat(plan.usesLexical()).isTrue();
        // No degradation note — SEARCH_TEXT lexical routing is normal behaviour
        assertThat(plan.degradationNote()).isNull();
    }

    @Test
    void unknownLanguage_searchText_routesLexicalFirst() {
        QueryPlan plan = planner.plan(QueryType.SEARCH_TEXT, "brainfuck");
        assertThat(plan.route()).isEqualTo(QueryRoute.LEXICAL_FIRST);
    }

    // ------------------------------------------------------------------
    // Query plan fields
    // ------------------------------------------------------------------

    @Test
    void plan_populatesQueryTypeAndLanguage() {
        QueryPlan plan = planner.plan(QueryType.FIND_REFERENCES, "java");
        assertThat(plan.queryType()).isEqualTo(QueryType.FIND_REFERENCES);
        assertThat(plan.language()).isEqualTo("java");
    }

    @Test
    void plan_capabilitiesContainAllDimensions() {
        QueryPlan plan = planner.plan(QueryType.FIND_SYMBOL, "java");
        assertThat(plan.capabilities()).containsKeys(CapabilityDimension.values());
    }

    @Test
    void plan_capabilitiesMatchMatrix() {
        QueryPlan plan = planner.plan(QueryType.FIND_SYMBOL, "java");
        assertThat(plan.capabilities().get(CapabilityDimension.SYMBOL_DEFINITIONS))
                .isEqualTo(CapabilityLevel.EXACT);
    }

    // ------------------------------------------------------------------
    // Determinism test — same input always produces same output
    // ------------------------------------------------------------------

    @Test
    void determinism_sameInputProducesSameOutput() {
        for (QueryType qt : QueryType.values()) {
            for (String lang : new String[]{"java", "typescript", "python", "go", "kotlin", "brainfuck"}) {
                QueryPlan first  = planner.plan(qt, lang);
                QueryPlan second = planner.plan(qt, lang);
                assertThat(first.route())
                        .as("route for %s/%s must be deterministic", qt, lang)
                        .isEqualTo(second.route());
                assertThat(first.degradationNote())
                        .as("degradationNote for %s/%s must be deterministic", qt, lang)
                        .isEqualTo(second.degradationNote());
                assertThat(first.capabilities())
                        .as("capabilities for %s/%s must be deterministic", qt, lang)
                        .isEqualTo(second.capabilities());
            }
        }
    }

    // ------------------------------------------------------------------
    // Degradation note quality
    // ------------------------------------------------------------------

    @Test
    void lexicalOnlyNote_mentionsLanguage() {
        QueryPlan plan = planner.plan(QueryType.FIND_SYMBOL, "kotlin");
        assertThat(plan.degradationNote()).contains("kotlin");
    }

    @Test
    void lexicalOnlyNote_mentionsQueryType() {
        QueryPlan plan = planner.plan(QueryType.FIND_REFERENCES, "kotlin");
        assertThat(plan.degradationNote()).containsIgnoringCase("FIND_REFERENCES");
    }
}
