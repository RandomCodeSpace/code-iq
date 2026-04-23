package io.github.randomcodespace.iq.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Top-level CLI entry point for Code IQ.
 * Delegates to subcommands for actual work.
 */
@Component
@Command(
        name = "code-iq",
        mixinStandardHelpOptions = true,
        version = "0.1.0-SNAPSHOT",
        description = "Intelligent code graph discovery and analysis CLI",
        subcommands = {
                AnalyzeCommand.class,
                IndexCommand.class,
                EnrichCommand.class,
                ServeCommand.class,
                GraphCommand.class,
                QueryCommand.class,
                FindCommand.class,
                CypherCommand.class,
                FlowCommand.class,
                BundleCommand.class,
                CacheCommand.class,
                ConfigCommand.class,
                StatsCommand.class,
                TopologyCommand.class,
                PluginsCommand.class,
                VersionCommand.class
        }
)
public class CodeIqCli implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
