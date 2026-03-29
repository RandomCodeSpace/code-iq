package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.config.CodeIqConfig;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Package graph + source into a distributable ZIP bundle.
 */
@Component
@Command(name = "bundle", mixinStandardHelpOptions = true,
        description = "Package graph + source into distributable ZIP")
public class BundleCommand implements Callable<Integer> {

    @Parameters(index = "0", defaultValue = ".", description = "Path to analyzed codebase")
    private Path path;

    @Option(names = {"--tag", "-t"}, description = "Bundle tag/version")
    private String tag;

    @Option(names = {"--output", "-o"}, description = "Output ZIP path (default: code-iq-bundle.zip)")
    private Path output;

    private final CodeIqConfig config;

    public BundleCommand(CodeIqConfig config) {
        this.config = config;
    }

    @Override
    public Integer call() {
        Path root = path.toAbsolutePath().normalize();
        Path graphDir = root.resolve(config.getCacheDir());

        if (!Files.isDirectory(graphDir)) {
            CliOutput.error("No analysis data found at " + graphDir);
            CliOutput.info("Run 'code-iq analyze " + root + "' first.");
            return 1;
        }

        Path zipPath = output != null ? output
                : root.resolve("code-iq-bundle.zip");

        CliOutput.step("\uD83D\uDCE6", "Creating bundle...");

        try (var zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            // Write manifest
            String manifest = createManifest(root);
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifest.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // Bundle graph data directory
            try (var walk = Files.walk(graphDir)) {
                walk.filter(Files::isRegularFile).forEach(file -> {
                    try {
                        String entryName = "graph/" + graphDir.relativize(file);
                        zos.putNextEntry(new ZipEntry(entryName));
                        Files.copy(file, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        CliOutput.warn("Skipped file: " + file + " (" + e.getMessage() + ")");
                    }
                });
            }

            CliOutput.success("\u2705 Bundle created: " + zipPath);
            CliOutput.info("  Tag: " + (tag != null ? tag : "untagged"));
            CliOutput.info("  Size: " + Files.size(zipPath) / 1024 + " KB");
        } catch (IOException e) {
            CliOutput.error("Failed to create bundle: " + e.getMessage());
            return 1;
        }

        return 0;
    }

    private String createManifest(Path root) {
        return """
                {
                  "tool": "code-iq",
                  "version": "0.1.0-SNAPSHOT",
                  "tag": "%s",
                  "created_at": "%s",
                  "root": "%s"
                }
                """.formatted(
                tag != null ? tag : "",
                Instant.now().toString(),
                root.getFileName()
        );
    }
}
