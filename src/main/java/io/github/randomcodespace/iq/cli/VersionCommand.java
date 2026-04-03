package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.detector.DetectorRegistry;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/**
 * Show version and environment info.
 */
@Component
@Command(name = "version", mixinStandardHelpOptions = true,
        description = "Show version info")
public class VersionCommand implements Callable<Integer> {

    public static final String VERSION = "0.1.0-SNAPSHOT";

    private final DetectorRegistry registry;

    public VersionCommand(DetectorRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Integer call() {
        Set<String> allLanguages = new TreeSet<>();
        for (var d : registry.allDetectors()) {
            allLanguages.addAll(d.getSupportedLanguages());
        }

        CliOutput.bold("OSSCodeIQ " + VERSION);
        CliOutput.info("  Java:       " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")");
        CliOutput.info("  Runtime:    " + System.getProperty("java.runtime.name"));
        CliOutput.info("  OS:         " + System.getProperty("os.name")
                + " " + System.getProperty("os.arch"));
        CliOutput.info("  Detectors:  " + registry.count());
        CliOutput.info("  Languages:  " + allLanguages.size());
        CliOutput.info("  Backend:    Neo4j Embedded");

        return 0;
    }
}
