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
import java.util.Locale;
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
 * <p>
 * The bundled CLI JAR keeps the historical {@code code-iq-*-cli.jar} filename because
 * it tracks the Maven artifactId ({@code io.github.randomcodespace.iq:code-iq}), which
 * is intentionally unchanged across the codeiq rename. See {@code CLAUDE.md}.
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
                : root.resolve(".codeiq/graph/graph.db");
        Path h2Dir = root.resolve(config.getCacheDir());

        // Validate Neo4j graph exists
        if (!Files.isDirectory(neo4jDir)) {
            CliOutput.error("No Neo4j graph found at " + neo4jDir);
            CliOutput.info("  Run 'codeiq index " + root + "' then 'codeiq enrich " + root + "' first.");
            return 1;
        }

        String projectName = java.util.Objects.toString(root.getFileName(), "bundle");
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

            // 9. checksums.sha256 — written LAST so it covers every preceding
            // entry (and excludes itself, which would be circular). Receivers
            // verify with `sha256sum -c checksums.sha256` post-unzip — the file
            // format mirrors GNU coreutils sha256sum output exactly.
            CliOutput.info("  Writing checksums.sha256 (" + checksums.size() + " entries)");
            writeChecksumsManifest(zos);

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
                # codeiq bundle launcher (offline / air-gapped).
                #
                # No public-internet calls. The receiving environment must already
                # have the CLI JAR present — either bundled via `codeiq bundle
                # --include-jar` or staged from your internal artifact mirror.
                set -euo pipefail
                SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
                cd "$SCRIPT_DIR"

                # Optional but recommended: verify bundle integrity before launch.
                # checksums.sha256 is generated by `codeiq bundle` in standard
                # GNU sha256sum format. Skip with CODEIQ_SKIP_VERIFY=1.
                if [ "${CODEIQ_SKIP_VERIFY:-0}" != "1" ] && [ -f checksums.sha256 ] \\
                    && command -v sha256sum >/dev/null 2>&1; then
                  echo "Verifying bundle integrity (sha256sum -c)..."
                  sha256sum -c --quiet checksums.sha256
                fi

                # Read version from manifest
                VERSION=$(grep -o '"extractor_version" *: *"[^"]*"' manifest.json | grep -o '"[^"]*"$' | tr -d '"')
                JAR="code-iq-${VERSION}-cli.jar"

                if [ ! -f "$JAR" ]; then
                  echo "ERROR: $JAR not found in $SCRIPT_DIR." >&2
                  echo "  Re-bundle with: codeiq bundle <repo> --include-jar" >&2
                  echo "  Or place the JAR next to serve.sh (e.g., from your internal mirror)." >&2
                  exit 1
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
                rem codeiq bundle launcher (offline / air-gapped).\r
                rem No public-internet calls. The CLI JAR must already be present\r
                rem alongside this script — bundle with `codeiq bundle --include-jar`\r
                rem or stage from your internal artifact mirror.\r
                setlocal enabledelayedexpansion\r
                cd /d "%~dp0"\r
                \r
                for /f "tokens=2 delims=:" %%a in ('findstr "extractor_version" manifest.json') do (\r
                    set "VERSION=%%~a"\r
                    set "VERSION=!VERSION: =!"\r
                    set "VERSION=!VERSION:"=!"\r
                    set "VERSION=!VERSION:,=!"\r
                )\r
                \r
                set "JAR=code-iq-!VERSION!-cli.jar"\r
                \r
                if not exist "!JAR!" (\r
                    echo ERROR: !JAR! not found in %~dp0.\r
                    echo   Re-bundle with: codeiq bundle ^<repo^> --include-jar\r
                    echo   Or place the JAR next to serve.bat (e.g., from your internal mirror).\r
                    exit /b 1\r
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
                            writeFileHashed(zos, entryName, file);
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
                            writeFileHashed(zos, "source/" + relPath, absPath);
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
                    .filter(p -> !p.startsWith(root.resolve(".codeiq")))
                    .filter(p -> !p.startsWith(root.resolve(".git")))
                    .sorted()
                    .forEach(file -> {
                        try {
                            String entryName = "source/" + root.relativize(file).toString()
                                    .replace('\\', '/');
                            writeFileHashed(zos, entryName, file);
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
                long sizeMb = Files.size(runningJar) / (1024 * 1024);
                writeFileHashed(zos, jarName, runningJar);
                CliOutput.info("  Included CLI JAR: " + jarName + " (" + sizeMb + " MB)");
            } catch (IOException e) {
                CliOutput.warn("  Could not include CLI JAR: " + e.getMessage());
            }
        } else {
            CliOutput.warn("  Could not locate CLI JAR. Receivers must place the matching"
                    + " code-iq-" + version + "-cli.jar next to serve.sh before running.");
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

    /**
     * Per-entry SHA-256 accumulator. {@link LinkedHashMap} preserves write
     * order — paired with the deterministic ZIP write order (sorted dir walks
     * + sorted git ls-files), this gives a byte-stable {@code checksums.sha256}.
     * Format mirrors {@code sha256sum} output exactly so receivers can run
     * {@code sha256sum -c checksums.sha256} to verify the unpacked bundle.
     */
    private final java.util.Map<String, String> checksums = new java.util.LinkedHashMap<>();

    private void writeEntry(ZipOutputStream zos, String name, String content) throws IOException {
        writeEntryHashed(zos, name, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Write a string/byte entry to the ZIP and record its SHA-256 in
     * {@link #checksums}. Used for in-memory content (manifest, serve scripts,
     * flow.html).
     */
    private void writeEntryHashed(ZipOutputStream zos, String name, byte[] content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content);
        zos.closeEntry();
        checksums.put(name, sha256Hex(content));
    }

    /**
     * Stream a file into the ZIP and record its SHA-256 in {@link #checksums}.
     * The file is read once: each chunk is fed both to the hash and to the
     * ZIP output stream. No intermediate byte[] for large files (graph DB,
     * cache files, CLI JAR can be hundreds of MB).
     */
    private void writeFileHashed(ZipOutputStream zos, String entryName, java.nio.file.Path file) throws IOException {
        zos.putNextEntry(new ZipEntry(entryName));
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable in JDK", e);
        }
        try (java.io.InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
                zos.write(buf, 0, n);
            }
        }
        zos.closeEntry();
        checksums.put(entryName, java.util.HexFormat.of().formatHex(md.digest()));
    }

    private static String sha256Hex(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable in JDK", e);
        }
    }

    /**
     * Emit the {@code checksums.sha256} entry — the canonical integrity manifest
     * for receivers. Format: {@code <sha256>  <relative-path>\n} per line, which
     * matches GNU coreutils {@code sha256sum} output so verification is a
     * straight {@code sha256sum -c checksums.sha256} on the unpacked bundle.
     *
     * <p>Note: this file is itself NOT in the checksums map (would be circular).
     * Operators wanting to verify {@code checksums.sha256} authenticity should
     * verify the bundle.zip's signature/digest out-of-band (Sigstore, GPG, or
     * the GitHub release SHA-256).
     */
    private void writeChecksumsManifest(ZipOutputStream zos) throws IOException {
        StringBuilder sb = new StringBuilder(checksums.size() * 80);
        for (var e : checksums.entrySet()) {
            sb.append(e.getValue()).append("  ").append(e.getKey()).append('\n');
        }
        zos.putNextEntry(new ZipEntry("checksums.sha256"));
        zos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

}
