package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.detector.Detector;
import io.github.randomcodespace.iq.detector.DetectorRegistry;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/**
 * List and inspect available detectors (plugins).
 */
@Component
@Command(name = "plugins", mixinStandardHelpOptions = true,
        description = "List and inspect detectors",
        subcommands = {
                PluginsCommand.ListSubcommand.class,
                PluginsCommand.InfoSubcommand.class
        })
public class PluginsCommand implements Runnable {

    private final DetectorRegistry registry;

    public PluginsCommand(DetectorRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void run() {
        // Default action: list detectors
        new ListSubcommand(registry).call();
    }

    @Component
    @Command(name = "list", mixinStandardHelpOptions = true,
            description = "List all available detectors")
    static class ListSubcommand implements Callable<Integer> {

        private final DetectorRegistry registry;

        ListSubcommand(DetectorRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Integer call() {
            var detectors = registry.allDetectors();
            CliOutput.bold("Available detectors (" + detectors.size() + "):");
            System.out.println();

            // Collect all languages
            Set<String> allLanguages = new TreeSet<>();

            for (Detector d : detectors) {
                String langs = String.join(", ", new TreeSet<>(d.getSupportedLanguages()));
                CliOutput.print(System.out,
                        "  @|bold " + d.getName() + "|@  @|faint [" + langs + "]|@");
                allLanguages.addAll(d.getSupportedLanguages());
            }

            System.out.println();
            CliOutput.info("Supported languages (" + allLanguages.size() + "): "
                    + String.join(", ", allLanguages));

            return 0;
        }
    }

    @Component
    @Command(name = "info", mixinStandardHelpOptions = true,
            description = "Show details for a specific detector")
    static class InfoSubcommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Detector name")
        private String name;

        private final DetectorRegistry registry;

        InfoSubcommand(DetectorRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Integer call() {
            var detector = registry.get(name);
            if (detector.isEmpty()) {
                CliOutput.error("Detector not found: " + name);
                CliOutput.info("Use 'code-iq plugins list' to see available detectors.");
                return 1;
            }

            Detector d = detector.get();
            CliOutput.bold(d.getName());
            CliOutput.info("  Languages: " + String.join(", ",
                    new TreeSet<>(d.getSupportedLanguages())));
            CliOutput.info("  Class:     " + d.getClass().getName());

            return 0;
        }
    }
}
