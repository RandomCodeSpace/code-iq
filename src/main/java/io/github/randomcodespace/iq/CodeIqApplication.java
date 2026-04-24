package io.github.randomcodespace.iq;

import io.github.randomcodespace.iq.cli.CodeIqCli;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

import java.util.Arrays;

/**
 * Main application entry point for Code IQ.
 * <p>
 * Uses Picocli with Spring Boot integration for CLI command routing.
 * Profile selection:
 * <ul>
 *   <li>{@code serve} command → "serving" profile (web server enabled)</li>
 *   <li>All other commands → "indexing" profile (no web server)</li>
 * </ul>
 */
@SpringBootApplication
@EnableCaching
public class CodeIqApplication implements CommandLineRunner, ExitCodeGenerator {

    private final CodeIqCli codeIqCli;
    private final IFactory factory;
    private int exitCode;

    public CodeIqApplication(CodeIqCli codeIqCli, IFactory factory) {
        this.codeIqCli = codeIqCli;
        this.factory = factory;
    }

    @Override
    public void run(String... args) {
        exitCode = new CommandLine(codeIqCli, factory).execute(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    public static void main(String[] args) {
        var app = new SpringApplication(CodeIqApplication.class);
        app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);

        // Detect command from first non-flag argument only
        String command = Arrays.stream(args)
                .filter(arg -> !arg.startsWith("-"))
                .findFirst()
                .orElse("");
        boolean isServe = "serve".equalsIgnoreCase(command);

        if (isServe) {
            app.setAdditionalProfiles("serving");

            // Extract --port flag for server port
            String portStr = extractFlag(args, "--port");
            if (portStr == null) portStr = extractFlag(args, "-p");
            if (portStr != null) {
                System.setProperty("server.port", portStr);
            }

            // Disable web UI if --no-ui flag is present
            boolean noUi = Arrays.asList(args).contains("--no-ui");
            if (noUi) {
                System.setProperty("codeiq.ui.enabled", "false");
                // Also disable Spring Boot's static resource handler so no
                // static files (index.html, JS, CSS bundles) are served.
                System.setProperty("spring.web.resources.add-mappings", "false");
            }

            // Resolve codebase root so Neo4j points to the correct graph.db
            String codebasePath = extractPositionalArg(args, "serve");
            java.nio.file.Path root = java.nio.file.Path.of(
                    codebasePath != null ? codebasePath : "."
            ).toAbsolutePath().normalize();
            System.setProperty("codeiq.root-path", root.toString());

            // Check if enrich has been run (graph.db exists), otherwise
            // check for the --graph flag override
            String graphOverride = extractFlag(args, "--graph");
            java.nio.file.Path graphDbPath;
            if (graphOverride != null) {
                graphDbPath = java.nio.file.Path.of(graphOverride).toAbsolutePath().normalize();
            } else {
                graphDbPath = root.resolve(".codeiq/graph/graph.db");
            }

            // Point Neo4j config to the graph path (enriched or new empty db).
            // GraphBootstrapper will auto-load from H2 cache if no enriched graph exists.
            System.setProperty("codeiq.graph.path", graphDbPath.toString());
        } else {
            // All non-serve commands (index, enrich, analyze, stats, ...) share the same
            // Spring setup: "indexing" profile, no web server. index/enrich open Neo4j
            // programmatically when needed. Previously split into three identical
            // branches — SpotBugs DB_DUPLICATE_BRANCHES.
            app.setAdditionalProfiles("indexing");
            app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        }

        System.exit(SpringApplication.exit(app.run(args)));
    }

    /**
     * Extract the value of a named flag from the args array.
     * Supports both "--flag value" and "--flag=value" forms.
     */
    private static String extractFlag(String[] args, String flagName) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals(flagName) && i + 1 < args.length) {
                return args[i + 1];
            }
            if (args[i].startsWith(flagName + "=")) {
                return args[i].substring(flagName.length() + 1);
            }
        }
        return null;
    }

    /**
     * Boolean (no-value) flags for the serve command.
     * These must NOT consume the next token as their value.
     */
    private static final java.util.Set<String> BOOLEAN_FLAGS = java.util.Set.of(
            "--no-ui", "--help", "-h", "--version"
    );

    /**
     * Extract the first positional argument after the command name.
     * Skips flags (--name value pairs) to find positional args.
     * Boolean flags (no value) are not allowed to consume the next token.
     */
    private static String extractPositionalArg(String[] args, String command) {
        boolean foundCommand = false;
        boolean skipNext = false;
        for (String arg : args) {
            if (skipNext) {
                skipNext = false;
                continue;
            }
            if (!foundCommand && arg.equalsIgnoreCase(command)) {
                foundCommand = true;
                continue;
            }
            if (foundCommand) {
                // Skip --flag value pairs, but not boolean flags that take no value
                if (arg.startsWith("--") && !arg.contains("=") && !BOOLEAN_FLAGS.contains(arg)) {
                    skipNext = true;
                    continue;
                }
                if (arg.startsWith("-") && arg.length() == 2 && !BOOLEAN_FLAGS.contains(arg)) {
                    skipNext = true; // short flag like -p 8080
                    continue;
                }
                if (arg.startsWith("-")) {
                    continue; // --flag=value, boolean flag, or unknown short flag
                }
                return arg;
            }
        }
        return null;
    }
}
