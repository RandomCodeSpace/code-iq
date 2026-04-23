package io.github.randomcodespace.iq.config.unified;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConfigResolverTest {

    @Test
    void layersResolveInDocumentedOrder(@TempDir Path tmp) throws Exception {
        // user-global: port=7000
        Path userGlobal = tmp.resolve("user.yml");
        Files.writeString(userGlobal, "serving:\n  port: 7000\n");

        // project: port=8500  AND  indexing.batch_size=1234
        Path project = tmp.resolve("codeiq.yml");
        Files.writeString(project, "serving:\n  port: 8500\nindexing:\n  batch_size: 1234\n");

        // env: port=9100 (should win over project)  AND NO batch_size (project wins there)
        Map<String, String> env = Map.of("CODEIQ_SERVING_PORT", "9100");

        // cli: read_only=true (only CLI sets it)
        CodeIqUnifiedConfig cli = new CodeIqUnifiedConfig(
                ProjectConfig.empty(), IndexingConfig.empty(),
                new ServingConfig(null, null, true, Neo4jConfig.empty()),
                McpConfig.empty(), ObservabilityConfig.empty(), DetectorsConfig.empty());

        MergedConfig merged = new ConfigResolver()
                .userGlobalPath(userGlobal)
                .projectPath(project)
                .env(env)
                .cliOverlay(cli, "--read-only")
                .resolve();

        assertEquals(9100, merged.effective().serving().port());
        assertEquals(ConfigLayer.ENV, merged.provenance().get("serving.port").layer());
        assertEquals(1234, merged.effective().indexing().batchSize());
        assertEquals(ConfigLayer.PROJECT, merged.provenance().get("indexing.batch_size").layer());
        assertEquals(Boolean.TRUE, merged.effective().serving().readOnly());
        assertEquals(ConfigLayer.CLI, merged.provenance().get("serving.read_only").layer());
        // indexing.incremental is not set in project/env/cli, so it must
        // fall through to BUILT_IN defaults (which set it to true).
        assertEquals(Boolean.TRUE, merged.effective().indexing().incremental());
        assertEquals(ConfigLayer.BUILT_IN,
                merged.provenance().get("indexing.incremental").layer());
    }
}
