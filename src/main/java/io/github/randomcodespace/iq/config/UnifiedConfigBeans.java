package io.github.randomcodespace.iq.config;

import io.github.randomcodespace.iq.config.unified.CodeIqUnifiedConfig;
import io.github.randomcodespace.iq.config.unified.ConfigDefaults;
import io.github.randomcodespace.iq.config.unified.ConfigLayer;
import io.github.randomcodespace.iq.config.unified.ConfigMerger;
import io.github.randomcodespace.iq.config.unified.EnvVarOverlay;
import io.github.randomcodespace.iq.config.unified.MergedConfig;
import io.github.randomcodespace.iq.config.unified.UnifiedConfigLoader;
import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.intelligence.provenance.ArtifactMetadataProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.nio.file.Path;
import java.util.List;

/**
 * Produces the unified config tree and the legacy {@link CodeIqConfig} POJO for downstream consumers.
 *
 * <p><b>Config boundary:</b> this class owns all runtime {@code codeiq.*} values (cache dirs, limits,
 * pipeline tuning, MCP auth, etc.) via {@link CodeIqUnifiedConfig}. A narrow set of Spring-level
 * keys stay in {@code application.yml} because they drive {@code @ConditionalOnProperty} /
 * {@code @Value} machinery that reads Spring {@code Environment} directly and is not sourced from
 * the unified tree:
 *
 * <ul>
 *   <li>{@code codeiq.neo4j.enabled} — gates {@link Neo4jConfig} (profile-conditional).
 *   <li>{@code codeiq.neo4j.bolt.port} — {@code @Value} default on the bolt port.
 *   <li>{@code codeiq.cors.allowed-origin-patterns} — {@code @Value} default in {@code CorsConfig}.
 *   <li>{@code codeiq.ui.enabled} — gates {@code SpaController}.
 * </ul>
 *
 * <p>Everything else was migrated to {@code codeiq.yml} / env / CLI overlays in Phase B.
 *
 * <p>The unified bean is produced by running {@link ConfigResolver} once at startup (defaults +
 * user-global yml + project yml + env vars). The legacy {@link CodeIqConfig} bean is derived from
 * the unified tree via {@link UnifiedConfigAdapter#toCodeIqConfig}, so call sites that still depend
 * on the legacy API continue to work unchanged.
 *
 * <p>Path layering (last wins):
 * <pre>
 *   BUILT_IN    (ConfigDefaults.builtIn())
 *   USER_GLOBAL (~/.codeiq/config.yml)
 *   PROJECT     (./codeiq.yml)
 *   ENV         (CODEIQ_* environment variables)
 *   CLI         (injected per-command; not applied here)
 * </pre>
 */
@Configuration
public class UnifiedConfigBeans {

    /**
     * Resolves {@code codeiq.yml} + env vars once at startup; the resulting
     * {@link CodeIqUnifiedConfig} is the single source of truth for
     * configuration.
     */
    @Bean
    public CodeIqUnifiedConfig codeIqUnifiedConfig(ProjectConfigLoader loader) {
        Path userGlobal = Path.of(System.getProperty("user.home"), ".codeiq", "config.yml");
        ProjectConfigLoader.LoadResult pr = loader.loadFrom(Path.of("."));
        // Compose defaults + user-global + project (from loader) + env. The CLI
        // overlay is injected per-command elsewhere.
        MergedConfig merged = new ConfigMerger().merge(List.of(
                new ConfigMerger.Input(ConfigLayer.BUILT_IN, "(defaults)", ConfigDefaults.builtIn()),
                new ConfigMerger.Input(ConfigLayer.USER_GLOBAL, userGlobal.toString(),
                        UnifiedConfigLoader.load(userGlobal)),
                new ConfigMerger.Input(ConfigLayer.PROJECT, "./codeiq.yml", pr.config()),
                new ConfigMerger.Input(ConfigLayer.ENV, "(env)", EnvVarOverlay.from(System.getenv()))
        ));
        return merged.effective();
    }

    /**
     * Back-compat bean for the legacy {@link CodeIqConfig} API. Produced by
     * adapting the unified tree; preserves existing getter/setter surface
     * consumed by ~100 call sites across the codebase.
     */
    @Bean
    @Primary
    public CodeIqConfig codeIqConfig(CodeIqUnifiedConfig unified) {
        return UnifiedConfigAdapter.toCodeIqConfig(unified);
    }

    /**
     * Provides on-demand artifact metadata in the {@code serving} profile.
     *
     * <p>Moved here from {@link CodeIqConfig} when that class stopped being a
     * {@code @Configuration}. Graph-derived fields are resolved lazily so
     * H2-to-Neo4j bootstrap can complete before clients fetch manifest data.
     */
    @Bean
    @Profile("serving")
    public ArtifactMetadataProvider artifactMetadataProvider(
            CodeIqConfig config,
            @Autowired(required = false) GraphStore graphStore) {
        Path root = Path.of(config.getRootPath()).toAbsolutePath().normalize();
        return new ArtifactMetadataProvider(root, graphStore);
    }
}
