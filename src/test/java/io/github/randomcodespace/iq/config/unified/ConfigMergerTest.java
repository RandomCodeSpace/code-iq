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
