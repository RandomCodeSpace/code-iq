package io.github.randomcodespace.iq.config;

/**
 * Test-support helper that wraps the package-private setters on
 * {@link CodeIqConfig}. Intentionally a separate class so the name makes its
 * purpose unmistakable: production code calls {@link UnifiedConfigAdapter}
 * once at Spring startup and {@link CliStartupConfigOverrides} once per JVM
 * at CLI entry; anything else goes through this class.
 *
 * <p>Use from tests that need to construct a {@link CodeIqConfig} with
 * specific field values without taking on the ceremony of building a full
 * {@link io.github.randomcodespace.iq.config.unified.CodeIqUnifiedConfig}
 * record tree. Fluent so setups stay readable.
 *
 * <p><b>Do not use from production code.</b> Any production caller of this
 * class is a bug — production mutation must remain confined to the two
 * entry points named above.
 */
public final class CodeIqConfigTestSupport {

    private final CodeIqConfig config;

    private CodeIqConfigTestSupport(CodeIqConfig config) {
        this.config = config;
    }

    /** Wrap an existing {@link CodeIqConfig} for fluent field overrides. */
    public static CodeIqConfigTestSupport override(CodeIqConfig config) {
        return new CodeIqConfigTestSupport(config);
    }

    public CodeIqConfigTestSupport rootPath(String v) { config.setRootPath(v); return this; }
    public CodeIqConfigTestSupport cacheDir(String v) { config.setCacheDir(v); return this; }
    public CodeIqConfigTestSupport maxDepth(int v)    { config.setMaxDepth(v); return this; }
    public CodeIqConfigTestSupport maxRadius(int v)   { config.setMaxRadius(v); return this; }
    public CodeIqConfigTestSupport maxFiles(int v)    { config.setMaxFiles(v); return this; }
    public CodeIqConfigTestSupport batchSize(int v)   { config.setBatchSize(v); return this; }
    public CodeIqConfigTestSupport readOnly(boolean v){ config.setReadOnly(v); return this; }
    public CodeIqConfigTestSupport serviceName(String v) { config.setServiceName(v); return this; }
    public CodeIqConfigTestSupport uiEnabled(boolean v){ config.setUiEnabled(v); return this; }
    public CodeIqConfigTestSupport maxSnippetLines(int v) { config.setMaxSnippetLines(v); return this; }

    public CodeIqConfigTestSupport graph(CodeIqConfig.Graph g) { config.setGraph(g); return this; }
    public CodeIqConfigTestSupport graphPath(String v) {
        CodeIqConfig.Graph g = new CodeIqConfig.Graph();
        g.setPath(v);
        config.setGraph(g);
        return this;
    }

    /** @return the wrapped config for chaining with downstream construction. */
    public CodeIqConfig done() { return config; }
}
