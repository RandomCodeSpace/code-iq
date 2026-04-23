package io.github.randomcodespace.iq.config;

import io.github.randomcodespace.iq.config.unified.CodeIqUnifiedConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectConfigLoaderTest {

    @Test
    void loadsCodeiqYmlWhenPresent(@TempDir Path repo) throws Exception {
        Files.writeString(repo.resolve("codeiq.yml"), "serving:\n  port: 9000\n");
        ProjectConfigLoader.LoadResult r = new ProjectConfigLoader().loadFrom(repo);
        assertEquals(9000, r.config().serving().port());
    }

    @Test
    void noFilePresentReturnsEmptyConfig(@TempDir Path repo) {
        ProjectConfigLoader.LoadResult r = new ProjectConfigLoader().loadFrom(repo);
        assertEquals(CodeIqUnifiedConfig.empty(), r.config());
    }
}
