package io.github.randomcodespace.iq.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Start the web UI + REST API + MCP server.
 *
 * This command signals to the application that it should keep running
 * (the web server is already started by Spring Boot when the "serving" profile
 * is active). The serve command simply prints server info and blocks until
 * the Spring context shuts down.
 */
@Component
@Command(name = "serve", mixinStandardHelpOptions = true,
        description = "Start web UI + REST API + MCP server")
public class ServeCommand implements Callable<Integer> {

    /** Marker flag — checked by CodeIqApplication to activate serving profile. */
    public static final String COMMAND_NAME = "serve";

    @Parameters(index = "0", defaultValue = ".", description = "Path to analyzed codebase")
    private Path path;

    @Option(names = {"--port", "-p"}, defaultValue = "8080", description = "Server port")
    private int port;

    @Option(names = {"--host"}, defaultValue = "0.0.0.0", description = "Bind address")
    private String host;

    @Override
    public Integer call() {
        CliOutput.step("\uD83D\uDE80", "@|bold,green Server started|@");
        System.out.println();
        CliOutput.info("  URL:       http://" + host + ":" + port);
        CliOutput.info("  REST API:  http://" + host + ":" + port + "/api");
        CliOutput.info("  MCP:       http://" + host + ":" + port + "/mcp");
        CliOutput.info("  Health:    http://" + host + ":" + port + "/actuator/health");
        CliOutput.info("  API Docs:  http://" + host + ":" + port + "/docs");
        System.out.println();
        CliOutput.info("Press Ctrl+C to stop.");

        // The Spring Boot web server is already running. We block here
        // to prevent the CommandLineRunner from returning (which would
        // trigger application shutdown). The JVM shutdown hook will
        // handle cleanup.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return 0;
    }

    public Path getPath() {
        return path;
    }

    public int getPort() {
        return port;
    }

    public String getHost() {
        return host;
    }
}
