package io.github.randomcodespace.iq.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.randomcodespace.iq.cache.AnalysisCache;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.flow.FlowEngine;
import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.intelligence.ArtifactManifest;
import io.github.randomcodespace.iq.intelligence.FileInventory;
import io.github.randomcodespace.iq.intelligence.RepositoryIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Package Neo4j graph + H2 cache + source code + startup scripts into a
 * self-contained, serve-ready ZIP bundle.
 * <p>
 * Pipeline: {@code index} → {@code enrich} → {@code bundle} → transfer → {@code serve}
 * <p>
 * Bundle structure:
 * <pre>
 * project-bundle.zip
 * ├── manifest.json
 * ├── serve.sh
 * ├── serve.bat
 * ├── graph.db/          (Neo4j embedded database)
 * ├── cache/             (H2 analysis cache)
 * ├── source/            (codebase files)
 * ├── flow.html          (interactive architecture diagram)
 * └── code-iq-*-cli.jar  (optional)
 * </pre>
 */
@Component
@Command(name = "bundle", mixinStandardHelpOptions = true,
        description = "Package graph + source + scripts into serve-ready ZIP")
public class BundleCommand implements Callable<Integer> {

    @Parameters(index = "0", defaultValue = ".", description = "Path to analyzed codebase")
    private Path path;

    @Option(names = {"--tag", "-t"}, description = "Bundle tag/version label")
    private String tag;

    @Option(names = {"--output", "-o"}, description = "Output ZIP path")
    private Path output;

    @Option(names = {"--include-jar"}, description = "Include CLI JAR in bundle")
    private boolean includeJar;

    @Option(names = {"--no-source"}, description = "Exclude source code from bundle")
    private boolean noSource;

    @Option(names = {"--graph"}, description = "Path to Neo4j graph directory (overrides default)")
    private Path graphDirOption;

    private final CodeIqConfig config;
    private final GraphStore graphStore;
    private final FlowEngine flowEngine;

    public BundleCommand() {
        this.config = new CodeIqConfig();
        this.graphStore = null;
        this.flowEngine = null;
    }

    @Autowired
    public BundleCommand(CodeIqConfig config,
                         Optional<GraphStore> graphStore, Optional<FlowEngine> flowEngine) {
        this.config = config;
        this.graphStore = graphStore.orElse(null);
        this.flowEngine = flowEngine.orElse(null);
    }

    BundleCommand(CodeIqConfig config, GraphStore graphStore, FlowEngine flowEngine) {
        this.config = config;
        this.graphStore = graphStore;
        this.flowEngine = flowEngine;
    }

    @Override
    public Integer call() {
        Path root = path.toAbsolutePath().normalize();
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        String version = VersionCommand.VERSION;

        // Resolve paths
        Path neo4jDir = graphDirOption != null
                ? graphDirOption.toAbsolutePath().normalize()
                : root.resolve(".code-iq/graph/graph.db");
        Path h2Dir = root.resolve(config.getCacheDir());

        // Validate Neo4j graph exists
        if (!Files.isDirectory(neo4jDir)) {
            CliOutput.error("No Neo4j graph found at " + neo4jDir);
            CliOutput.info("  Run 'code-iq index " + root + "' then 'code-iq enrich " + root + "' first.");
            return 1;
        }

        String projectName = root.getFileName().toString();
        String bundleTag = tag != null ? tag : "latest";

        Path zipPath = output != null ? output
                : root.resolve(projectName + "-" + bundleTag + "-bundle.zip");

        // Get node/edge counts from H2 cache
        long nodeCount = 0, edgeCount = 0;
        if (Files.isDirectory(h2Dir)) {
            try (var cache = new AnalysisCache(h2Dir.resolve("analysis-cache.db"))) {
                nodeCount = cache.getNodeCount();
                edgeCount = cache.getEdgeCount();
            } catch (Exception e) {
                CliOutput.warn("Could not read H2 cache stats: " + e.getMessage());
            }
        }

        CliOutput.step("[+]", "Creating bundle...");

        try (var zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {

            // 1. manifest.json
            CliOutput.info("  Writing manifest.json");
            RepositoryIdentity repoIdentity = RepositoryIdentity.resolve(root);
            String manifest = createManifest(projectName, bundleTag, version, repoIdentity,
                    nodeCount, edgeCount, !noSource, includeJar);
            writeEntry(zos, "manifest.json", manifest);

            // 2. serve.sh
            CliOutput.info("  Writing serve.sh");
            writeEntry(zos, "serve.sh", generateServeShell(version));

            // 3. serve.bat
            CliOutput.info("  Writing serve.bat");
            writeEntry(zos, "serve.bat", generateServeBat(version));

            // 4. Neo4j graph database
            CliOutput.info("  Bundling Neo4j graph from " + neo4jDir);
            int graphFiles = bundleDirectory(neo4jDir, "graph.db", zos, true);
            CliOutput.info("    " + nf.format(graphFiles) + " files");

            // 5. H2 analysis cache
            if (Files.isDirectory(h2Dir)) {
                CliOutput.info("  Bundling H2 cache from " + h2Dir);
                int cacheFiles = bundleDirectory(h2Dir, "cache", zos, false);
                CliOutput.info("    " + nf.format(cacheFiles) + " files");
            }

            // 6. Source code
            if (!noSource) {
                CliOutput.info("  Bundling source code...");
                int sourceFiles = bundleSourceFiles(root, zos);
                CliOutput.info("    " + nf.format(sourceFiles) + " files");
            }

            // 7. Interactive flow diagram
            if (flowEngine != null) {
                try {
                    String flowHtml = flowEngine.renderInteractive(projectName);
                    writeEntry(zos, "flow.html", flowHtml);
                    CliOutput.info("  Generated flow.html");
                } catch (Exception e) {
                    CliOutput.warn("  Could not generate flow.html: " + e.getMessage());
                }
            }

            // 8. CLI JAR (optional)
            if (includeJar) {
                bundleCliJar(version, zos);
            } else if (version.contains("-SNAPSHOT")) {
                CliOutput.warn("  Version is SNAPSHOT — consider using --include-jar "
                        + "(SNAPSHOT JARs are not on Maven Central)");
            }

        } catch (IOException e) {
            CliOutput.error("Failed to create bundle: " + e.getMessage());
            return 1;
        }

        // Report
        try {
            long sizeBytes = Files.size(zipPath);
            String sizeStr = sizeBytes > 1024 * 1024
                    ? "%.1f MB".formatted(sizeBytes / (1024.0 * 1024.0))
                    : nf.format(sizeBytes / 1024) + " KB";

            System.out.println();
            CliOutput.success("[OK] Bundle created: " + zipPath);
            CliOutput.info("  Tag:    " + bundleTag);
            CliOutput.info("  Nodes:  " + nf.format(nodeCount));
            CliOutput.info("  Edges:  " + nf.format(edgeCount));
            CliOutput.info("  Size:   " + sizeStr);
            System.out.println();
            CliOutput.info("  To run on remote server:");
            CliOutput.info("    unzip " + zipPath.getFileName());
            CliOutput.info("    cd " + projectName + "-" + bundleTag + "-bundle");
            CliOutput.info("    chmod +x serve.sh && ./serve.sh");
        } catch (IOException ignored) {}

        return 0;
    }

    // --- Manifest ---

    private String createManifest(String projectName, String bundleTag, String version,
                                   RepositoryIdentity repoIdentity,
                                   long nodeCount, long edgeCount,
                                   boolean includesSource, boolean includesJar) {
        var manifest = new ArtifactManifest(
                ArtifactManifest.BUNDLE_FORMAT_VERSION,
                bundleTag,
                projectName,
                version,
                io.github.randomcodespace.iq.intelligence.Provenance.CURRENT_SCHEMA_VERSION,
                Instant.now().toString(),
                repoIdentity,
                FileInventory.EMPTY.toSummary(),
                nodeCount,
                edgeCount,
                includesSource,
                includesJar,
                null
        );
        try {
            return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(manifest.toMap());
        } catch (Exception e) {
            return "{}";
        }
    }

    // --- Startup scripts ---

    private String generateServeShell(String version) {
        return """
                #!/usr/bin/env bash
                set -euo pipefail
                SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
                cd "$SCRIPT_DIR"

                # Read version from manifest
                VERSION=$(grep -o '"osscodeiq_version" *: *"[^"]*"' manifest.json | grep -o '"[^"]*"$' | tr -d '"')
                JAR="code-iq-${VERSION}-cli.jar"

                # Download CLI JAR if not present
                if [ ! -f "$JAR" ]; then
                  if [[ "$VERSION" == *-SNAPSHOT ]]; then
                    echo "ERROR: CLI JAR not found and version is a SNAPSHOT."
                    echo "  Re-bundle with --include-jar or place $JAR in this directory."
                    exit 1
                  fi
                  echo "Downloading code-iq CLI v${VERSION}..."
                  curl -fL -o "$JAR" \\
                    "https://repo1.maven.org/maven2/io/github/randomcodespace/iq/code-iq/${VERSION}/code-iq-${VERSION}-cli.jar"
                fi

                # Start serve (read-only)
                exec java \\
                  -Dcodeiq.cache-dir=./cache \\
                  -jar "$JAR" serve ./source \\
                  --graph ./graph.db \\
                  --port "${PORT:-8080}"
                """;
    }

    private String generateServeBat(String version) {
        return """
                @echo off\r
                setlocal enabledelayedexpansion\r
                cd /d "%~dp0"\r
                \r
                for /f "tokens=2 delims=:" %%a in ('findstr "osscodeiq_version" manifest.json') do (\r
                    set "VERSION=%%~a"\r
                    set "VERSION=!VERSION: =!"\r
                    set "VERSION=!VERSION:"=!"\r
                    set "VERSION=!VERSION:,=!"\r
                )\r
                \r
                set "JAR=code-iq-!VERSION!-cli.jar"\r
                \r
                if not exist "!JAR!" (\r
                    echo !VERSION! | findstr /C:"-SNAPSHOT" >nul\r
                    if !errorlevel! == 0 (\r
                        echo ERROR: CLI JAR not found and version is a SNAPSHOT.\r
                        echo   Re-bundle with --include-jar or place !JAR! in this directory.\r
                        exit /b 1\r
                    )\r
                    echo Downloading code-iq CLI v!VERSION!...\r
                    curl -fL -o "!JAR!" "https://repo1.maven.org/maven2/io/github/randomcodespace/iq/code-iq/!VERSION!/code-iq-!VERSION!-cli.jar"\r
                )\r
                \r
                if "%PORT%"=="" set PORT=8080\r
                \r
                java -Dcodeiq.cache-dir=./cache -jar "!JAR!" serve ./source --graph ./graph.db --port %PORT%\r
                """;
    }

    // --- Directory bundling ---

    /**
     * Bundle a directory tree into the ZIP under a given prefix.
     * Skips Neo4j lock files if skipLocks is true.
     * @return number of files bundled
     */
    private int bundleDirectory(Path dir, String zipPrefix, ZipOutputStream zos,
                                 boolean skipLocks) {
        int[] count = {0};
        try (var walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        if (!skipLocks) return true;
                        String name = p.getFileName().toString();
                        return !name.contains("lock") && !name.endsWith(".pid");
                    })
                    .sorted()
                    .forEach(file -> {
                        try {
                            String entryName = zipPrefix + "/" + dir.relativize(file).toString()
                                    .replace('\\', '/');
                            zos.putNextEntry(new ZipEntry(entryName));
                            Files.copy(file, zos);
                            zos.closeEntry();
                            count[0]++;
                        } catch (IOException e) {
                            // Skip files that can't be read (e.g., locked)
                        }
                    });
        } catch (IOException e) {
            CliOutput.warn("Could not bundle " + dir + ": " + e.getMessage());
        }
        return count[0];
    }

    // --- Source bundling ---

    /**
     * Bundle source files using git ls-files or directory walk.
     * @return number of files bundled
     */
    private int bundleSourceFiles(Path root, ZipOutputStream zos) {
        // Try git ls-files first
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "ls-files")
                    .directory(root.toFile())
                    .redirectErrorStream(true);
            Process proc = pb.start();
            String gitOutput = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = proc.waitFor();
            if (exitCode == 0 && !gitOutput.isBlank()) {
                String[] files = gitOutput.split("\n");
                int count = 0;
                for (String relPath : files) {
                    if (relPath.isBlank()) continue;
                    Path absPath = root.resolve(relPath);
                    if (Files.isRegularFile(absPath)) {
                        try {
                            zos.putNextEntry(new ZipEntry("source/" + relPath));
                            Files.copy(absPath, zos);
                            zos.closeEntry();
                            count++;
                        } catch (IOException e) {
                            // Skip
                        }
                    }
                }
                return count;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Not a git repo or git not available
        }

        // Fallback: directory walk
        int[] count = {0};
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !p.startsWith(root.resolve(config.getCacheDir())))
                    .filter(p -> !p.startsWith(root.resolve(".osscodeiq")))
                    .filter(p -> !p.startsWith(root.resolve(".git")))
                    .sorted()
                    .forEach(file -> {
                        try {
                            String entryName = "source/" + root.relativize(file).toString()
                                    .replace('\\', '/');
                            zos.putNextEntry(new ZipEntry(entryName));
                            Files.copy(file, zos);
                            zos.closeEntry();
                            count[0]++;
                        } catch (IOException e) {
                            // Skip
                        }
                    });
        } catch (IOException e) {
            CliOutput.warn("Could not bundle source files: " + e.getMessage());
        }
        return count[0];
    }

    // --- CLI JAR bundling ---

    private void bundleCliJar(String version, ZipOutputStream zos) {
        Path runningJar = findRunningJar();
        if (runningJar != null && Files.isRegularFile(runningJar)) {
            String jarName = "code-iq-" + version + "-cli.jar";
            try {
                zos.putNextEntry(new ZipEntry(jarName));
                Files.copy(runningJar, zos);
                zos.closeEntry();
                long sizeMb = Files.size(runningJar) / (1024 * 1024);
                CliOutput.info("  Included CLI JAR: " + jarName + " (" + sizeMb + " MB)");
            } catch (IOException e) {
                CliOutput.warn("  Could not include CLI JAR: " + e.getMessage());
            }
        } else {
            CliOutput.warn("  Could not locate CLI JAR. The bundle will download it on first run.");
        }
    }

    private Path findRunningJar() {
        try {
            var location = io.github.randomcodespace.iq.CodeIqApplication.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI();
            Path p = Path.of(location);
            // Spring Boot nested JAR: the path might be the nested BOOT-INF path
            // Walk up to find the actual JAR
            while (p != null && !p.toString().endsWith(".jar")) {
                p = p.getParent();
            }
            if (p != null && Files.isRegularFile(p)) return p;
        } catch (Exception ignored) {}

        // Fallback: look in target/
        try (var walk = Files.list(Path.of("target"))) {
            return walk.filter(p -> p.toString().endsWith("-cli.jar"))
                    .findFirst().orElse(null);
        } catch (Exception ignored) {}

        return null;
    }

    // --- Utilities ---

    private void writeEntry(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

}
