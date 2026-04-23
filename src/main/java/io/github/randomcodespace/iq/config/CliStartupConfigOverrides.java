package io.github.randomcodespace.iq.config;

import java.nio.file.Path;

/**
 * Centralised, CLI-startup-only mutation of the {@link CodeIqConfig} Spring
 * singleton.
 *
 * <p><b>Call contract:</b> these helpers are invoked exactly once per JVM
 * invocation, from a Picocli command's {@code call()} entry point, <em>before</em>
 * any downstream consumer reads config state. Treat the config as frozen
 * afterwards.
 *
 * <p>Do <b>not</b> invoke from request handlers, background workers, controllers,
 * MCP tools, or any serving-layer code path. The {@link CodeIqConfig} bean is a
 * Spring singleton shared across every consumer — mutating it at runtime is a
 * correctness hazard and was the motivation for collapsing all existing call
 * sites into this one package-private surface.
 *
 * <p>Visibility is package-private by design: only other classes inside
 * {@code io.github.randomcodespace.iq.config} can reach {@link CodeIqConfig}'s
 * package-private setters via this helper. CLI callers in
 * {@code io.github.randomcodespace.iq.cli} and analyzer callers in
 * {@code io.github.randomcodespace.iq.analyzer} route through the public
 * {@code apply*} methods below.
 */
public final class CliStartupConfigOverrides {

    private CliStartupConfigOverrides() {}

    /**
     * Apply the {@code serve} command's startup overrides to the config bean:
     * absolute root path, and read-only mode when the {@code --read-only} flag
     * was set.
     *
     * @param config the Spring-managed {@link CodeIqConfig} singleton
     * @param root   absolute, normalised root path (must not be {@code null})
     * @param readOnly whether the {@code --read-only} CLI flag was set
     */
    public static void applyServeOverrides(CodeIqConfig config, Path root, boolean readOnly) {
        if (config == null || root == null) {
            return;
        }
        config.setRootPath(root.toString());
        if (readOnly) {
            config.setReadOnly(true);
        }
    }

    /**
     * Override the cache directory. No-op if {@code cacheDir} is {@code null}
     * or blank — we never overwrite the in-code default with an absent value.
     */
    public static void applyCacheDir(CodeIqConfig config, String cacheDir) {
        if (config == null || cacheDir == null || cacheDir.isBlank()) {
            return;
        }
        config.setCacheDir(cacheDir);
    }

    /**
     * Override the service-name tag used in multi-repo graph mode. No-op if
     * {@code name} is {@code null} or blank.
     */
    public static void applyServiceName(CodeIqConfig config, String name) {
        if (config == null || name == null || name.isBlank()) {
            return;
        }
        config.setServiceName(name);
    }
}
