package io.github.randomcodespace.iq.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.randomcodespace.iq.analyzer.AnalysisResult;
import io.github.randomcodespace.iq.analyzer.Analyzer;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.flow.FlowEngine;
import io.github.randomcodespace.iq.graph.GraphStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Package graph + source + interactive flow diagram into a distributable ZIP bundle.
 */
@Component
@Command(name = "bundle", mixinStandardHelpOptions = true,
        description = "Package graph + source into distributable ZIP")
public class BundleCommand implements Callable<Integer> {

    @Parameters(index = "0", defaultValue = ".", description = "Path to analyzed codebase")
    private Path path;

    @Option(names = {"--tag", "-t"}, description = "Bundle tag/version")
    private String tag;

    @Option(names = {"--output", "-o"}, description = "Output ZIP path")
    private Path output;

    private final CodeIqConfig config;
    private final Analyzer analyzer;
    private final GraphStore graphStore;
    private final FlowEngine flowEngine;

    @Autowired
    public BundleCommand(CodeIqConfig config, Analyzer analyzer,
                         Optional<GraphStore> graphStore, Optional<FlowEngine> flowEngine) {
        this.config = config;
        this.analyzer = analyzer;
        this.graphStore = graphStore.orElse(null);
        this.flowEngine = flowEngine.orElse(null);
    }

    /** Convenience constructor for tests. */
    BundleCommand(CodeIqConfig config, Analyzer analyzer,
                  GraphStore graphStore, FlowEngine flowEngine) {
        this.config = config;
        this.analyzer = analyzer;
        this.graphStore = graphStore;
        this.flowEngine = flowEngine;
    }

    @Override
    public Integer call() {
        Path root = path.toAbsolutePath().normalize();
        Path graphDir = root.resolve(config.getCacheDir());

        // Run analysis if no existing data
        AnalysisResult analysisResult = null;
        if (!Files.isDirectory(graphDir)) {
            CliOutput.step("\uD83D\uDD0D", "No existing analysis found. Running analysis...");
            try {
                analysisResult = analyzer.run(root, null);
            } catch (Exception e) {
                CliOutput.error("Analysis failed: " + e.getMessage());
                return 1;
            }
        }

        String projectName = root.getFileName().toString();
        String bundleTag = tag != null ? tag : "latest";

        Path zipPath = output != null ? output
                : root.resolve(projectName + "-" + bundleTag + "-codegraph.zip");

        CliOutput.step("\uD83D\uDCE6", "Creating bundle...");

        try (var zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            // Determine node/edge counts
            long nodeCount;
            long edgeCount;
            int filesAnalyzed;
            if (analysisResult != null) {
                nodeCount = analysisResult.nodeCount();
                edgeCount = analysisResult.edgeCount();
                filesAnalyzed = analysisResult.totalFiles();
            } else if (graphStore != null) {
                nodeCount = graphStore.count();
                edgeCount = graphStore.findAll().stream()
                        .mapToLong(n -> n.getEdges().size())
                        .sum();
                filesAnalyzed = 0;
            } else {
                nodeCount = 0;
                edgeCount = 0;
                filesAnalyzed = 0;
            }

            // 1. Write manifest.json (matching Python format)
            String manifest = createManifest(projectName, bundleTag, nodeCount, edgeCount, filesAnalyzed);
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifest.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // 2. Bundle graph data directory
            if (Files.isDirectory(graphDir)) {
                try (var walk = Files.walk(graphDir)) {
                    walk.filter(Files::isRegularFile).sorted().forEach(file -> {
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
            }

            // 3. Generate interactive flow HTML
            if (flowEngine != null) {
                try {
                    String flowHtml = flowEngine.renderInteractive(projectName);
                    zos.putNextEntry(new ZipEntry("flow.html"));
                    zos.write(flowHtml.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                } catch (Exception e) {
                    CliOutput.warn("Could not generate flow.html: " + e.getMessage());
                }
            }

            // 4. Bundle source files
            bundleSourceFiles(root, zos);

            CliOutput.success("\u2705 Bundle created: " + zipPath);
            CliOutput.info("  Tag: " + bundleTag);
            CliOutput.info("  Nodes: " + nodeCount + ", Edges: " + edgeCount);
            long sizeKb = Files.size(zipPath) / 1024;
            if (sizeKb > 1024) {
                CliOutput.info("  Size: %.1f MB".formatted(sizeKb / 1024.0));
            } else {
                CliOutput.info("  Size: " + sizeKb + " KB");
            }
        } catch (IOException e) {
            CliOutput.error("Failed to create bundle: " + e.getMessage());
            return 1;
        }

        return 0;
    }

    private String createManifest(String projectName, String bundleTag,
                                   long nodeCount, long edgeCount, int filesAnalyzed) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("tag", bundleTag);
        manifest.put("backend", "neo4j");
        manifest.put("project", projectName);
        manifest.put("created_at", Instant.now().toString());
        manifest.put("node_count", nodeCount);
        manifest.put("edge_count", edgeCount);
        manifest.put("files_analyzed", filesAnalyzed);
        manifest.put("osscodeiq_version", "0.1.0-SNAPSHOT");

        // Try to get git SHA
        String gitSha = getGitSha();
        if (gitSha != null) {
            manifest.put("git_sha", gitSha);
        }

        try {
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            return mapper.writeValueAsString(manifest);
        } catch (Exception e) {
            // Fallback to simple JSON
            return """
                    {
                      "tag": "%s",
                      "project": "%s",
                      "created_at": "%s"
                    }
                    """.formatted(bundleTag, projectName, Instant.now().toString());
        }
    }

    /**
     * Bundle source files into source/ directory using git ls-files if available,
     * otherwise walk the directory tree.
     */
    private void bundleSourceFiles(Path root, ZipOutputStream zos) {
        // Try git ls-files first
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "ls-files")
                    .directory(root.toFile())
                    .redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = proc.waitFor();
            if (exitCode == 0 && !output.isBlank()) {
                String[] files = output.split("\n");
                for (String relPath : files) {
                    if (relPath.isBlank()) continue;
                    Path absPath = root.resolve(relPath);
                    if (Files.isRegularFile(absPath)) {
                        try {
                            zos.putNextEntry(new ZipEntry("source/" + relPath));
                            Files.copy(absPath, zos);
                            zos.closeEntry();
                        } catch (IOException e) {
                            // Skip files that can't be read
                        }
                    }
                }
                return;
            }
        } catch (Exception ignored) {
            // Not a git repo or git not available, fall through to file walk
        }

        // Fallback: walk directory tree
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !p.startsWith(root.resolve(config.getCacheDir())))
                    .filter(p -> !p.startsWith(root.resolve(".git")))
                    .sorted()
                    .forEach(file -> {
                        try {
                            String entryName = "source/" + root.relativize(file);
                            zos.putNextEntry(new ZipEntry(entryName));
                            Files.copy(file, zos);
                            zos.closeEntry();
                        } catch (IOException e) {
                            // Skip files that can't be read
                        }
                    });
        } catch (IOException e) {
            CliOutput.warn("Could not bundle source files: " + e.getMessage());
        }
    }

    private String getGitSha() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .directory(path.toAbsolutePath().normalize().toFile())
                    .redirectErrorStream(true);
            Process proc = pb.start();
            String sha = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = proc.waitFor();
            if (exitCode == 0 && sha.length() >= 7) {
                return sha;
            }
        } catch (Exception ignored) {
            // Not a git repo
        }
        return null;
    }
}
