package io.github.randomcodespace.iq.config;

import io.github.randomcodespace.iq.config.unified.CodeIqUnifiedConfig;
import io.github.randomcodespace.iq.config.unified.UnifiedConfigLoader;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the project-scoped {@code codeiq.yml} from the repo root.
 *
 * <p>Surface: the {@link #loadFrom(Path)} instance method returns a
 * {@link LoadResult} with a {@link CodeIqUnifiedConfig} overlay for the
 * PROJECT layer. This is the only public loader surface; it is consumed by
 * {@code UnifiedConfigBeans} at startup.
 */
@Component
public class ProjectConfigLoader {

    private static final String NEW_NAME = "codeiq.yml";

    public ProjectConfigLoader() {
        // default bean constructor
    }

    /**
     * Result of loading the project-scoped config.
     *
     * @param config the loaded overlay in unified-config form, or
     *               {@link CodeIqUnifiedConfig#empty()} if the file does not exist
     */
    public record LoadResult(CodeIqUnifiedConfig config) {}

    /**
     * Loads the project-scoped config overlay from {@code repoRoot}. Reads
     * {@code codeiq.yml} if present; otherwise returns an empty overlay.
     */
    public LoadResult loadFrom(Path repoRoot) {
        Path file = repoRoot.resolve(NEW_NAME);
        if (Files.exists(file)) {
            return new LoadResult(UnifiedConfigLoader.load(file));
        }
        return new LoadResult(CodeIqUnifiedConfig.empty());
    }
}
