package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.config.unified.CodeIqUnifiedConfig;
import io.github.randomcodespace.iq.config.unified.ConfigError;
import io.github.randomcodespace.iq.config.unified.ConfigLoadException;
import io.github.randomcodespace.iq.config.unified.ConfigResolver;
import io.github.randomcodespace.iq.config.unified.ConfigValidator;
import io.github.randomcodespace.iq.config.unified.MergedConfig;
import io.github.randomcodespace.iq.config.unified.UnifiedConfigLoader;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Validates a codeiq.yml configuration file. Exits with 0 when the effective
 * config (file overlay composed over built-in defaults) passes validation,
 * and 1 otherwise.
 */
@Component
@Command(
        name = "validate",
        mixinStandardHelpOptions = true,
        description = "Validate a codeiq.yml file")
public class ConfigValidateSubcommand implements Callable<Integer> {

    @Option(
            names = {"--path", "-p"},
            description = "Path to codeiq.yml (default: ./codeiq.yml)")
    private Path path = Path.of("codeiq.yml");

    private PrintStream out = System.out;

    void setPath(Path p) {
        this.path = p;
    }

    void setOut(PrintStream o) {
        this.out = o;
    }

    @Override
    public Integer call() {
        try {
            // Confirm the file parses; surfaces load errors distinctly.
            CodeIqUnifiedConfig ignored = UnifiedConfigLoader.load(path);
            // Validate the effective config (file overlay + built-in defaults)
            // so cross-field checks (e.g. heapInitial <= heapMax) always have
            // values to compare against.
            MergedConfig merged = new ConfigResolver().projectPath(path).resolve();
            List<ConfigError> errs = new ConfigValidator().validate(merged.effective());
            if (errs.isEmpty()) {
                out.println("OK: " + path + " is valid.");
                return 0;
            }
            out.println("Validation errors in " + path + ":");
            for (ConfigError e : errs) {
                out.println("  " + e.fieldPath() + ": " + e.message());
            }
            return 1;
        } catch (ConfigLoadException e) {
            out.println("Load error: " + e.getMessage());
            return 1;
        }
    }
}
