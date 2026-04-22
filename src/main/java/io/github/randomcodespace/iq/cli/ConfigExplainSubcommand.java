package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.config.unified.CodeIqUnifiedConfig;
import io.github.randomcodespace.iq.config.unified.ConfigLoadException;
import io.github.randomcodespace.iq.config.unified.ConfigProvenance;
import io.github.randomcodespace.iq.config.unified.ConfigResolver;
import io.github.randomcodespace.iq.config.unified.MergedConfig;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Prints the effective {@code codeiq.yml} configuration with per-field provenance: every leaf value
 * together with the layer that won (BUILT_IN / USER_GLOBAL / PROJECT / ENV / CLI) and the source
 * label (file path or {@code (env)}/{@code (defaults)}/{@code (cli)}).
 *
 * <p>Streams:
 *
 * <ul>
 *   <li>{@code out} -- the explain table (this is the command's product, not a log line).
 *   <li>{@code err} -- load failures (missing/unreadable file).
 * </ul>
 *
 * <p>Output is deterministic: rows are emitted sorted by field path so that diffing two runs is
 * meaningful.
 *
 * <p>Two constructors exist: the no-arg form binds to {@link System#out} and {@link System#err}
 * and is what picocli/Spring instantiates at runtime; the two-arg form lets tests inject capture
 * streams without touching mutable singleton state between invocations.
 */
@Component
@Command(
        name = "explain",
        mixinStandardHelpOptions = true,
        description = "Show effective config with per-field provenance")
public class ConfigExplainSubcommand implements Callable<Integer> {

    private static final Path DEFAULT_PATH = Path.of("codeiq.yml");

    /**
     * Row layout: {@code FIELD(40) + space + LAYER(12) + space + SOURCE(40) + "  " + VALUE}. The
     * divider row must span the header portion exactly -- the value column is not padded, so its
     * width is just the width of the literal header {@code "VALUE"} ({@value #VALUE_COLUMN_WIDTH}).
     */
    private static final String ROW_FORMAT = "%-40s %-12s %-40s  %s%n";

    private static final int VALUE_COLUMN_WIDTH = "VALUE".length();
    private static final int TABLE_WIDTH = 40 + 1 + 12 + 1 + 40 + 2 + VALUE_COLUMN_WIDTH;

    @Option(
            names = {"--path", "-p"},
            description = "Path to codeiq.yml (default: ./codeiq.yml)")
    private Path path = DEFAULT_PATH;

    private final PrintStream out;
    private final PrintStream err;

    // Nullable on purpose: a Spring-singleton bean must not freeze the env at construction time,
    // so we resolve System.getenv() lazily inside call(). Tests inject a fixed map via setEnv.
    private Map<String, String> envMap;

    // Nullable: tests may inject a CLI overlay to exercise CLI-wins-over-ENV precedence. Runtime
    // callers pass the real CLI overlay through this same seam once wired end-to-end.
    private CodeIqUnifiedConfig cliOverlay;

    public ConfigExplainSubcommand() {
        this(System.out, System.err);
    }

    public ConfigExplainSubcommand(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    void setPath(Path p) {
        this.path = p;
    }

    /**
     * Overrides the env used for overlay resolution. {@code null} means "use the real process
     * environment" -- {@link #call()} falls back to {@link System#getenv()} at invocation time, so
     * a Spring singleton bean sees fresh env each call instead of a frozen snapshot.
     */
    void setEnv(Map<String, String> e) {
        this.envMap = e;
    }

    /**
     * Injects a CLI-layer overlay (highest precedence). {@code null} means no CLI overlay. Exposed
     * as a package-private hook so tests can assert CLI-wins-over-ENV precedence without booting a
     * full picocli parse.
     */
    void setCliOverlay(CodeIqUnifiedConfig overlay) {
        this.cliOverlay = overlay;
    }

    @Override
    public Integer call() {
        // Guard against picocli leaving path unset (mirrors ConfigValidateSubcommand).
        if (path == null) {
            path = DEFAULT_PATH;
        }
        // UnifiedConfigLoader treats a missing file as an empty overlay, which is the right
        // default for an implicit ./codeiq.yml, but when the user points this subcommand at a
        // specific path, the absence of that file is a real error -- surface it as a load
        // error on stderr, same UX as `config validate`.
        if (!Files.exists(path)) {
            err.println("Load error: config file does not exist: " + path);
            return 1;
        }
        Map<String, String> effectiveEnv = (envMap != null) ? envMap : System.getenv();
        final MergedConfig merged;
        try {
            // ConfigResolver#resolve() invokes UnifiedConfigLoader.load internally; don't
            // double-parse the file here.
            ConfigResolver resolver =
                    new ConfigResolver().projectPath(path).env(effectiveEnv);
            if (cliOverlay != null) {
                resolver = resolver.cliOverlay(cliOverlay, "(cli)");
            }
            merged = resolver.resolve();
        } catch (ConfigLoadException e) {
            err.println("Load error: " + e.getMessage());
            return 1;
        }

        out.printf(ROW_FORMAT, "FIELD", "LAYER", "SOURCE", "VALUE");
        out.println("-".repeat(TABLE_WIDTH));
        // Stream sorted by field path using Comparator.comparing(Map.Entry::getKey) guarantees
        // byte-for-byte deterministic output across runs regardless of the underlying
        // provenance map's iteration order.
        merged.provenance().entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(
                        entry -> {
                            ConfigProvenance p = entry.getValue();
                            out.printf(
                                    ROW_FORMAT,
                                    entry.getKey(),
                                    p.layer(),
                                    p.sourceLabel(),
                                    "= " + p.value());
                        });
        return 0;
    }
}
