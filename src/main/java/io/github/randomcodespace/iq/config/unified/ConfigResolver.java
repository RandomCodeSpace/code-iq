package io.github.randomcodespace.iq.config.unified;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builder-style façade that composes ConfigDefaults + UnifiedConfigLoader +
 * EnvVarOverlay + a caller-provided CLI overlay, then runs ConfigMerger,
 * producing a MergedConfig with per-leaf provenance. Layer order
 * (last wins): BUILT_IN -> USER_GLOBAL -> PROJECT -> ENV -> CLI.
 */
public final class ConfigResolver {

    private Path userGlobal;
    private Path project;
    private Map<String, String> env = Map.of();
    private CodeIqUnifiedConfig cliOverlay = CodeIqUnifiedConfig.empty();
    private String cliLabel = "(cli)";

    public ConfigResolver userGlobalPath(Path p)           { this.userGlobal = p; return this; }
    public ConfigResolver projectPath(Path p)              { this.project = p; return this; }
    public ConfigResolver env(Map<String, String> env)     { this.env = env; return this; }
    public ConfigResolver cliOverlay(CodeIqUnifiedConfig c, String label) {
        this.cliOverlay = c == null ? CodeIqUnifiedConfig.empty() : c;
        this.cliLabel = label == null ? "(cli)" : label;
        return this;
    }

    public MergedConfig resolve() {
        List<ConfigMerger.Input> layers = new ArrayList<>();
        layers.add(new ConfigMerger.Input(ConfigLayer.BUILT_IN, "(defaults)", ConfigDefaults.builtIn()));
        if (userGlobal != null) {
            layers.add(new ConfigMerger.Input(ConfigLayer.USER_GLOBAL, userGlobal.toString(),
                    UnifiedConfigLoader.load(userGlobal)));
        }
        if (project != null) {
            layers.add(new ConfigMerger.Input(ConfigLayer.PROJECT, project.toString(),
                    UnifiedConfigLoader.load(project)));
        }
        layers.add(new ConfigMerger.Input(ConfigLayer.ENV, "(env)", EnvVarOverlay.from(env)));
        layers.add(new ConfigMerger.Input(ConfigLayer.CLI, cliLabel, cliOverlay));
        return new ConfigMerger().merge(layers);
    }
}
