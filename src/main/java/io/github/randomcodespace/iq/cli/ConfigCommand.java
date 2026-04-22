package io.github.randomcodespace.iq.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Parent command for configuration-related subcommands.
 *
 * <p>Use one of the subcommands (e.g. {@code code-iq config validate}) to act
 * on a codeiq.yml file. Running {@code code-iq config} with no subcommand
 * prints usage.
 */
@Component
@Command(
        name = "config",
        mixinStandardHelpOptions = true,
        description = "Inspect and validate code-iq configuration",
        subcommands = {ConfigValidateSubcommand.class, ConfigExplainSubcommand.class})
public class ConfigCommand implements Runnable {
    @Override
    public void run() {
        // no-op parent; use a subcommand
        new CommandLine(this).usage(System.out);
    }
}
