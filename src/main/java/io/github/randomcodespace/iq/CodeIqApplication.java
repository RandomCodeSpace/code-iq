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
 * Main application entry point for OSSCodeIQ.
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
        boolean isIndex = "index".equalsIgnoreCase(command);
        boolean isEnrich = "enrich".equalsIgnoreCase(command);

        if (isServe) {
            app.setAdditionalProfiles("serving");
        } else if (isIndex) {
            app.setAdditionalProfiles("indexing");
            // Index command: no web server, no Neo4j
            app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        } else if (isEnrich) {
            // Enrich command: no web server, Neo4j started programmatically
            app.setAdditionalProfiles("indexing");
            app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        } else {
            app.setAdditionalProfiles("indexing");
            // Disable web server for non-serve commands
            app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        }

        System.exit(SpringApplication.exit(app.run(args)));
    }
}
