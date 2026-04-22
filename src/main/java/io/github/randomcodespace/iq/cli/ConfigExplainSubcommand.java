package io.github.randomcodespace.iq.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * Stub for {@code code-iq config explain}. Full implementation lands in
 * Task 9 of the Phase B Unified Config plan; this stub exists so
 * {@link ConfigCommand} compiles with its declared subcommand list.
 */
@Component
@Command(
        name = "explain",
        mixinStandardHelpOptions = true,
        description = "Show effective config with per-field provenance (stub - Task 9)")
public class ConfigExplainSubcommand implements Callable<Integer> {
    @Override
    public Integer call() {
        return 0;
    }
}
