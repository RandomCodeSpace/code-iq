package io.github.randomcodespace.iq.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/**
 * Parent command for configuration-related subcommands.
 *
 * <p>Running {@code codeiq config} with no subcommand prints usage to stderr
 * and exits with picocli's conventional {@code USAGE} (2) exit code so that
 * scripts can distinguish "I invoked the tool wrong" from a successful or
 * failed operation.
 */
@Component
@Command(
        name = "config",
        mixinStandardHelpOptions = true,
        description = "Inspect and validate codeiq configuration",
        subcommands = {ConfigValidateSubcommand.class, ConfigExplainSubcommand.class})
public class ConfigCommand implements Callable<Integer> {

    @Spec private CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(System.err);
        return CommandLine.ExitCode.USAGE;
    }
}
