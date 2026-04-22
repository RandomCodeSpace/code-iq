package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.config.unified.ConfigError;
import io.github.randomcodespace.iq.config.unified.ConfigLoadException;
import io.github.randomcodespace.iq.config.unified.ConfigResolver;
import io.github.randomcodespace.iq.config.unified.ConfigValidator;
import io.github.randomcodespace.iq.config.unified.MergedConfig;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Validates a codeiq.yml configuration file. Exits with 0 when the effective
 * config (file overlay composed over built-in defaults) passes validation,
 * and 1 otherwise.
 *
 * <p>Streams:
 * <ul>
 *   <li>{@code out} -- human "OK" success messages only.</li>
 *   <li>{@code err} -- validation-error lists and load failures.</li>
 * </ul>
 *
 * <p>Two constructors exist: the no-arg form binds to {@link System#out} and
 * {@link System#err} and is what picocli/Spring instantiates at runtime; the
 * two-arg form lets tests inject capture streams without touching mutable
 * singleton state between invocations.
 */
@Component
@Command(
        name = "validate",
        mixinStandardHelpOptions = true,
        description = "Validate a codeiq.yml file")
public class ConfigValidateSubcommand implements Callable<Integer> {

    private static final Path DEFAULT_PATH = Path.of("codeiq.yml");

    @Option(
            names = {"--path", "-p"},
            description = "Path to codeiq.yml (default: ./codeiq.yml)")
    private Path path = DEFAULT_PATH;

    private final PrintStream out;
    private final PrintStream err;

    public ConfigValidateSubcommand() {
        this(System.out, System.err);
    }

    public ConfigValidateSubcommand(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    void setPath(Path p) {
        this.path = p;
    }

    @Override
    public Integer call() {
        // Guard against picocli leaving path unset when the user did not pass --path;
        // picocli normally uses the field initializer, but a null override via reflection
        // or a future refactor should still land on a sensible default.
        if (path == null) {
            path = DEFAULT_PATH;
        }
        // UnifiedConfigLoader treats a missing file as an empty overlay, which is
        // the right default for an implicit ./codeiq.yml, but when the user points
        // this subcommand at a specific path, the absence of that file is a real
        // error -- not a silent pass. Surface it as a load error.
        if (!Files.exists(path)) {
            err.println("Load error: config file does not exist: " + path);
            return 1;
        }
        try {
            // Validate the effective config (file overlay + built-in defaults) so
            // cross-field checks (e.g. heapInitial <= heapMax) always have values.
            // ConfigResolver#resolve() invokes UnifiedConfigLoader.load internally,
            // so any ConfigLoadException propagates from here.
            MergedConfig merged = new ConfigResolver().projectPath(path).resolve();
            List<ConfigError> errs = new ConfigValidator().validate(merged.effective());
            if (errs.isEmpty()) {
                out.println("OK: " + path + " is valid.");
                return 0;
            }
            err.println("Validation errors in " + path + ":");
            errs.stream()
                    .sorted(
                            Comparator.comparing(ConfigError::fieldPath)
                                    .thenComparing(ConfigError::message))
                    .forEach(e -> err.println("  " + e.fieldPath() + ": " + e.message()));
            return 1;
        } catch (ConfigLoadException e) {
            err.println("Load error: " + e.getMessage());
            return 1;
        }
    }
}
