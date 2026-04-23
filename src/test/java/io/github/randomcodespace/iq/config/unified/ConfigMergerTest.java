package io.github.randomcodespace.iq.config.unified;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConfigMergerTest {

    @Test
    void laterLayersWinWhenPresent() {
        CodeIqUnifiedConfig defaults = ConfigDefaults.builtIn(); // port=8080
        CodeIqUnifiedConfig project = EnvVarOverlay.from(Map.of("CODEIQ_SERVING_PORT", "9000")); // 9000
        CodeIqUnifiedConfig cli     = EnvVarOverlay.from(Map.of("CODEIQ_SERVING_PORT", "9999")); // 9999

        MergedConfig merged = new ConfigMerger().merge(List.of(
                new ConfigMerger.Input(ConfigLayer.BUILT_IN, "defaults", defaults),
                new ConfigMerger.Input(ConfigLayer.PROJECT, "./codeiq.yml", project),
                new ConfigMerger.Input(ConfigLayer.CLI, "--port=9999", cli)
        ));

        assertEquals(9999, merged.effective().serving().port());
        ConfigProvenance p = merged.provenance().get("serving.port");
        assertEquals(ConfigLayer.CLI, p.layer());
        assertEquals("--port=9999", p.sourceLabel());
    }

    @Test
    void nullInHigherLayerInheritsFromLower() {
        CodeIqUnifiedConfig defaults = ConfigDefaults.builtIn();      // port=8080
        CodeIqUnifiedConfig project = EnvVarOverlay.from(Map.of());   // nothing set
        MergedConfig merged = new ConfigMerger().merge(List.of(
                new ConfigMerger.Input(ConfigLayer.BUILT_IN, "defaults", defaults),
                new ConfigMerger.Input(ConfigLayer.PROJECT, "./codeiq.yml", project)
        ));
        assertEquals(8080, merged.effective().serving().port());
        assertEquals(ConfigLayer.BUILT_IN, merged.provenance().get("serving.port").layer());
    }

    // ---- Phase-B extensions: detectors.categories/include + indexing.parsers -------

    @Test
    void detectorsCategoriesFollowLayerReplacement() {
        CodeIqUnifiedConfig project = EnvVarOverlay.from(Map.of(
                "CODEIQ_DETECTORS_CATEGORIES", "endpoints"));
        CodeIqUnifiedConfig cli = EnvVarOverlay.from(Map.of(
                "CODEIQ_DETECTORS_CATEGORIES", "entities,topics"));
        MergedConfig merged = new ConfigMerger().merge(List.of(
                new ConfigMerger.Input(ConfigLayer.BUILT_IN, "defaults", ConfigDefaults.builtIn()),
                new ConfigMerger.Input(ConfigLayer.PROJECT, "./codeiq.yml", project),
                new ConfigMerger.Input(ConfigLayer.CLI, "--categories=...", cli)));
        assertEquals(List.of("entities", "topics"), merged.effective().detectors().categories());
        assertEquals(ConfigLayer.CLI, merged.provenance().get("detectors.categories").layer());
    }

    @Test
    void detectorsIncludeFallsThroughWhenAbsent() {
        CodeIqUnifiedConfig project = EnvVarOverlay.from(Map.of(
                "CODEIQ_DETECTORS_INCLUDE", "spring-rest-detector"));
        MergedConfig merged = new ConfigMerger().merge(List.of(
                new ConfigMerger.Input(ConfigLayer.BUILT_IN, "defaults", ConfigDefaults.builtIn()),
                new ConfigMerger.Input(ConfigLayer.PROJECT, "./codeiq.yml", project)));
        assertEquals(List.of("spring-rest-detector"), merged.effective().detectors().include());
        assertEquals(ConfigLayer.PROJECT, merged.provenance().get("detectors.include").layer());
    }

    @Test
    void indexingParsersMergeWholeLayer() {
        CodeIqUnifiedConfig project = EnvVarOverlay.from(Map.of(
                "CODEIQ_INDEXING_PARSERS", "javaparser"));
        CodeIqUnifiedConfig cli = EnvVarOverlay.from(Map.of(
                "CODEIQ_INDEXING_PARSERS", "antlr,regex"));
        MergedConfig merged = new ConfigMerger().merge(List.of(
                new ConfigMerger.Input(ConfigLayer.BUILT_IN, "defaults", ConfigDefaults.builtIn()),
                new ConfigMerger.Input(ConfigLayer.PROJECT, "./codeiq.yml", project),
                new ConfigMerger.Input(ConfigLayer.CLI, "--parsers=...", cli)));
        assertEquals(List.of("antlr", "regex"), merged.effective().indexing().parsers());
        assertEquals(ConfigLayer.CLI, merged.provenance().get("indexing.parsers").layer());
    }

    @Test
    void indexingParallelismIntegerLayerReplacement() {
        CodeIqUnifiedConfig project = EnvVarOverlay.from(Map.of(
                "CODEIQ_INDEXING_PARALLELISM", "4"));
        CodeIqUnifiedConfig cli = EnvVarOverlay.from(Map.of(
                "CODEIQ_INDEXING_PARALLELISM", "16"));
        MergedConfig merged = new ConfigMerger().merge(List.of(
                new ConfigMerger.Input(ConfigLayer.BUILT_IN, "defaults", ConfigDefaults.builtIn()),
                new ConfigMerger.Input(ConfigLayer.PROJECT, "./codeiq.yml", project),
                new ConfigMerger.Input(ConfigLayer.CLI, "--parallelism=16", cli)));
        assertEquals(16, merged.effective().indexing().parallelism());
        assertEquals(ConfigLayer.CLI, merged.provenance().get("indexing.parallelism").layer());
    }

    @Test
    void listsFollowWholeLayerReplacementNotMerge() {
        // Non-merge semantics: if a higher layer declares `languages`,
        // it REPLACES the lower layer entirely. This is predictable and
        // matches how most tools handle list overrides.
        CodeIqUnifiedConfig defaults = ConfigDefaults.builtIn(); // []
        CodeIqUnifiedConfig project = EnvVarOverlay.from(Map.of(
                "CODEIQ_INDEXING_LANGUAGES", "java,ts"));         // [java, ts]
        CodeIqUnifiedConfig cli = EnvVarOverlay.from(Map.of(
                "CODEIQ_INDEXING_LANGUAGES", "python"));          // [python]
        MergedConfig merged = new ConfigMerger().merge(List.of(
                new ConfigMerger.Input(ConfigLayer.BUILT_IN, "defaults", defaults),
                new ConfigMerger.Input(ConfigLayer.PROJECT, "./codeiq.yml", project),
                new ConfigMerger.Input(ConfigLayer.CLI, "--languages=python", cli)
        ));
        assertEquals(List.of("python"), merged.effective().indexing().languages());
        assertEquals(ConfigLayer.CLI, merged.provenance().get("indexing.languages").layer());
    }
}
