package io.github.randomcodespace.iq.analyzer;

import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects service boundaries by scanning the graph for build file nodes
 * that indicate module boundaries. Runs AFTER all detectors + linkers
 * during the enrich phase.
 * <p>
 * Creates SERVICE nodes and sets the {@code service} property on all
 * child nodes (nodes whose filePath starts with the module directory).
 * <p>
 * Supported build systems:
 * <ul>
 *   <li>Maven (pom.xml) -- extracts artifactId</li>
 *   <li>Gradle (build.gradle, build.gradle.kts)</li>
 *   <li>npm (package.json) -- extracts name field</li>
 *   <li>Go (go.mod) -- extracts module name</li>
 *   <li>Cargo (Cargo.toml) -- extracts package name</li>
 *   <li>.NET (.csproj)</li>
 *   <li>Python (requirements.txt, setup.py, pyproject.toml, manage.py)</li>
 *   <li>Docker (Dockerfile) -- supplemental indicator</li>
 * </ul>
 */
public class ServiceDetector {
    private static final String PROP_DJANGO = "django";
    private static final String PROP_DOCKER = "docker";
    private static final String PROP_DOTNET = "dotnet";
    private static final String PROP_GRADLE = "gradle";
    private static final String PROP_HASKELL = "haskell";
    private static final String PROP_PYPROJECT_TOML = "pyproject.toml";
    private static final String PROP_PYTHON = "python";
    private static final String PROP_RUBY = "ruby";
    private static final String PROP_SETUP_PY = "setup.py";


    private static final Logger log = LoggerFactory.getLogger(ServiceDetector.class);

    /**
     * Build file patterns that indicate module boundaries.
     * Maps filename to build tool name.
     */
    private static final Map<String, String> BUILD_FILES = Map.ofEntries(
            // Java/JVM
            Map.entry("pom.xml", "maven"),
            Map.entry("build.gradle", PROP_GRADLE),
            Map.entry("build.gradle.kts", PROP_GRADLE),
            Map.entry("settings.gradle", PROP_GRADLE),
            Map.entry("settings.gradle.kts", PROP_GRADLE),
            Map.entry("build.xml", "ant"),
            Map.entry("build.sbt", "sbt"),
            Map.entry("project.clj", "leiningen"),
            // JavaScript / TypeScript
            Map.entry("package.json", "npm"),
            Map.entry("deno.json", "deno"),
            Map.entry("deno.jsonc", "deno"),
            // Go
            Map.entry("go.mod", "go"),
            // Rust
            Map.entry("Cargo.toml", "cargo"),
            // Python
            Map.entry(PROP_PYPROJECT_TOML, PROP_PYTHON),
            Map.entry(PROP_SETUP_PY, PROP_PYTHON),
            Map.entry("setup.cfg", PROP_PYTHON),
            Map.entry("Pipfile", PROP_PYTHON),
            Map.entry("requirements.txt", PROP_PYTHON),
            Map.entry("manage.py", PROP_DJANGO),
            // Ruby
            Map.entry("Gemfile", PROP_RUBY),
            // PHP
            Map.entry("composer.json", "php"),
            // .NET (csproj handled by suffix match below)
            Map.entry("Directory.Build.props", PROP_DOTNET),
            // Swift
            Map.entry("Package.swift", "swift"),
            // Elixir
            Map.entry("mix.exs", "elixir"),
            // Dart / Flutter
            Map.entry("pubspec.yaml", "dart"),
            // Scala (also build.sbt above)
            // Haskell
            Map.entry("stack.yaml", PROP_HASKELL),
            // Zig
            Map.entry("build.zig", "zig"),
            // OCaml
            Map.entry("dune-project", "ocaml"),
            // R
            Map.entry("DESCRIPTION", "r"),
            // Bazel
            Map.entry("BUILD", "bazel"),
            Map.entry("BUILD.bazel", "bazel"),
            // Mono-repo orchestrators (supplemental, like Docker)
            Map.entry("nx.json", "nx"),
            Map.entry("lerna.json", "lerna"),
            Map.entry("turbo.json", "turbo"),
            Map.entry("rush.json", "rush"),
            // Docker (supplemental -- doesn't override other tools)
            Map.entry("Dockerfile", PROP_DOCKER),
            Map.entry("docker-compose.yml", PROP_DOCKER),
            Map.entry("docker-compose.yaml", PROP_DOCKER),
            Map.entry("compose.yml", PROP_DOCKER),
            Map.entry("compose.yaml", PROP_DOCKER)
    );

    /** File extensions matched by suffix (not exact name). */
    private static final String CSPROJ_EXTENSION = ".csproj";
    private static final String FSPROJ_EXTENSION = ".fsproj";
    private static final String VBPROJ_EXTENSION = ".vbproj";
    private static final String GEMSPEC_EXTENSION = ".gemspec";
    private static final String CABAL_EXTENSION = ".cabal";
    private static final String NIMBLE_EXTENSION = ".nimble";

    /** Build tools that are supplemental (don't override real build tools). */
    private static final java.util.Set<String> SUPPLEMENTAL_TOOLS = java.util.Set.of(
            PROP_DOCKER, "nx", "lerna", "turbo", "rush"
    );

    /** Python build files ranked by priority (first match wins for a directory). */
    private static final List<String> PYTHON_BUILD_FILES = List.of(
            PROP_PYPROJECT_TOML, PROP_SETUP_PY, "requirements.txt", "manage.py"
    );

    /** Regex patterns for extracting names from build file contents. */
    private static final Pattern POM_ARTIFACT_ID = Pattern.compile(
            "<artifactId>\\s*([^<]+?)\\s*</artifactId>");
    private static final Pattern PACKAGE_JSON_NAME = Pattern.compile(
            "\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern GO_MOD_MODULE = Pattern.compile(
            "^module\\s+(\\S+)", Pattern.MULTILINE);
    private static final Pattern CARGO_PACKAGE_NAME = Pattern.compile(
            "^name\\s*=\\s*\"([^\"]+)\"", Pattern.MULTILINE);
    private static final Pattern PYPROJECT_NAME = Pattern.compile(
            "^name\\s*=\\s*\"([^\"]+)\"", Pattern.MULTILINE);
    private static final Pattern SETUP_PY_NAME = Pattern.compile(
            "name\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern GRADLE_SETTINGS_NAME = Pattern.compile(
            "rootProject\\.name\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern SBT_NAME = Pattern.compile(
            "name\\s*:=\\s*\"([^\"]+)\"");
    private static final Pattern COMPOSER_NAME = Pattern.compile(
            "\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MIX_APP_NAME = Pattern.compile(
            "app:\\s*:([\\w]+)");
    private static final Pattern PUBSPEC_NAME = Pattern.compile(
            "^name:\\s*(\\S+)", Pattern.MULTILINE);

    /**
     * Detect service boundaries from the graph's nodes and create SERVICE nodes.
     *
     * @param nodes      all current nodes in the graph
     * @param edges      all current edges in the graph
     * @param projectDir the project root directory name (used as fallback service name)
     * @return result containing new SERVICE nodes, CONTAINS edges, and
     *         the service property assignments for existing nodes
     */
    public ServiceDetectionResult detect(List<CodeNode> nodes, List<CodeEdge> edges, String projectDir) {
        return detect(nodes, edges, projectDir, null);
    }

    /**
     * Detect service boundaries with optional project root for reading build file contents.
     *
     * @param nodes      all current nodes in the graph
     * @param edges      all current edges in the graph
     * @param projectDir the project root directory name (used as fallback service name)
     * @param projectRoot optional absolute path to the project root (for reading build files)
     * @return result containing new SERVICE nodes, CONTAINS edges
     */
    public ServiceDetectionResult detect(List<CodeNode> nodes, List<CodeEdge> edges,
                                         String projectDir, Path projectRoot) {
        // 1. Find module boundaries by scanning the filesystem for build files.
        //    This is more reliable than scanning node file paths, which may miss
        //    modules where no detector created a node from the build file.
        Map<String, ModuleInfo> modules = new TreeMap<>();

        if (projectRoot != null) {
            // Scan filesystem directly for build files (most reliable)
            scanFilesystemForBuildFiles(projectRoot, projectRoot, modules);
        }

        // Fallback: also scan node file paths in case filesystem scan missed anything
        for (CodeNode node : nodes) {
            String filePath = node.getFilePath();
            if (filePath == null) continue;

            String fileName = java.util.Objects.toString(Path.of(filePath).getFileName(), "");
            String dirPath = parentDir(filePath);

            String buildTool = BUILD_FILES.get(fileName);
            if (buildTool != null) {
                registerModule(modules, dirPath, buildTool, fileName);
            }
            if (fileName.endsWith(CSPROJ_EXTENSION) || fileName.endsWith(FSPROJ_EXTENSION)
                    || fileName.endsWith(VBPROJ_EXTENSION)) {
                modules.putIfAbsent(dirPath, new ModuleInfo(dirPath, PROP_DOTNET, fileName));
            } else if (fileName.endsWith(GEMSPEC_EXTENSION)) {
                modules.putIfAbsent(dirPath, new ModuleInfo(dirPath, PROP_RUBY, fileName));
            } else if (fileName.endsWith(CABAL_EXTENSION)) {
                modules.putIfAbsent(dirPath, new ModuleInfo(dirPath, PROP_HASKELL, fileName));
            } else if (fileName.endsWith(NIMBLE_EXTENSION)) {
                modules.putIfAbsent(dirPath, new ModuleInfo(dirPath, "nim", fileName));
            }
        }

        // 2. If no modules detected, create one service for the whole project
        if (modules.isEmpty()) {
            modules.put("", new ModuleInfo("", "unknown", ""));
        }

        // 3. Create SERVICE nodes and assign child nodes
        List<CodeNode> serviceNodes = new ArrayList<>();
        List<CodeEdge> serviceEdges = new ArrayList<>();

        // Sort module dirs by length descending so deeper paths match first
        List<String> sortedDirs = new ArrayList<>(modules.keySet());
        sortedDirs.sort((a, b) -> Integer.compare(b.length(), a.length()));

        // Map from module dir -> service node for child assignment
        Map<String, CodeNode> serviceByDir = new LinkedHashMap<>();

        for (var entry : modules.entrySet()) {
            String dir = entry.getKey();
            ModuleInfo info = entry.getValue();

            String serviceName = extractServiceName(dir, info, projectDir, projectRoot);

            CodeNode service = new CodeNode();
            service.setId("service:" + serviceName);
            service.setKind(NodeKind.SERVICE);
            service.setLabel(serviceName);
            service.setFilePath(dir.isEmpty() ? "." : dir);
            service.setLayer("backend"); // default, can be refined

            Map<String, Object> props = new LinkedHashMap<>();
            props.put("build_tool", info.buildTool());
            props.put("detected_from", info.buildFile());
            // Counts filled below
            props.put("endpoint_count", 0);
            props.put("entity_count", 0);
            service.setProperties(props);

            serviceNodes.add(service);
            serviceByDir.put(dir, service);
        }

        // 4. Assign service property to all child nodes + count endpoints/entities
        Map<String, Integer> endpointCounts = new LinkedHashMap<>();
        Map<String, Integer> entityCounts = new LinkedHashMap<>();

        for (CodeNode node : nodes) {
            String filePath = node.getFilePath();
            if (filePath == null) filePath = "";

            // Find the best matching service (deepest directory match)
            String matchedDir = null;
            for (String dir : sortedDirs) {
                if (dir.isEmpty() || filePath.startsWith(dir + "/") || filePath.equals(dir)) {
                    matchedDir = dir;
                    break;
                }
            }
            // Fallback to root module if present
            if (matchedDir == null && modules.containsKey("")) {
                matchedDir = "";
            }

            if (matchedDir != null) {
                CodeNode serviceNode = serviceByDir.get(matchedDir);
                if (serviceNode != null) {
                    String serviceName = serviceNode.getLabel();
                    // Ensure properties map is mutable before modifying
                    if (!(node.getProperties() instanceof java.util.HashMap)) {
                        node.setProperties(new java.util.HashMap<>(node.getProperties()));
                    }
                    node.getProperties().put("service", serviceName);

                    // Create CONTAINS edge
                    CodeEdge containsEdge = new CodeEdge(
                            "edge:service:" + serviceName + ":contains:" + node.getId(),
                            EdgeKind.CONTAINS,
                            serviceNode.getId(),
                            node
                    );
                    serviceEdges.add(containsEdge);

                    // Count endpoints and entities
                    if (node.getKind() == NodeKind.ENDPOINT) {
                        endpointCounts.merge(serviceName, 1, Integer::sum);
                    } else if (node.getKind() == NodeKind.ENTITY) {
                        entityCounts.merge(serviceName, 1, Integer::sum);
                    }
                }
            }
        }

        // 5. Update counts on service nodes
        for (CodeNode service : serviceNodes) {
            String name = service.getLabel();
            service.getProperties().put("endpoint_count",
                    endpointCounts.getOrDefault(name, 0));
            service.getProperties().put("entity_count",
                    entityCounts.getOrDefault(name, 0));
        }

        log.info("Detected {} service(s): {}", serviceNodes.size(),
                serviceNodes.stream().map(CodeNode::getLabel).toList());

        return new ServiceDetectionResult(serviceNodes, serviceEdges);
    }

    /**
     * Scan the filesystem recursively for build files that indicate service/module boundaries.
     * More reliable than scanning node file paths since not all build files produce CodeNodes.
     */
    /** Directories to skip entirely during filesystem walk. */
    private static final java.util.Set<String> SKIP_DIRS = java.util.Set.of(
            "node_modules", ".git", "target", "build", "dist", ".gradle",
            ".idea", ".vscode", "__pycache__", ".tox", ".eggs", "venv",
            ".venv", "vendor", ".bundle", "_build", "deps"
    );

    private void scanFilesystemForBuildFiles(Path root, Path projectRoot, Map<String, ModuleInfo> modules) {
        try {
            Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                    10, new java.nio.file.SimpleFileVisitor<Path>() {

                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                    String dirName = java.util.Objects.toString(dir.getFileName(), "");
                    if (SKIP_DIRS.contains(dirName)) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    String name = java.util.Objects.toString(file.getFileName(), "");
                    boolean isBuildFile = BUILD_FILES.containsKey(name)
                            || name.endsWith(CSPROJ_EXTENSION) || name.endsWith(FSPROJ_EXTENSION)
                            || name.endsWith(VBPROJ_EXTENSION) || name.endsWith(GEMSPEC_EXTENSION)
                            || name.endsWith(CABAL_EXTENSION) || name.endsWith(NIMBLE_EXTENSION);

                    if (!isBuildFile) return java.nio.file.FileVisitResult.CONTINUE;

                    String relDir = projectRoot.relativize(file.getParent()).toString()
                            .replace('\\', '/');
                    if (relDir.equals(".")) relDir = "";

                    if (name.endsWith(CSPROJ_EXTENSION) || name.endsWith(FSPROJ_EXTENSION)
                            || name.endsWith(VBPROJ_EXTENSION)) {
                        modules.putIfAbsent(relDir, new ModuleInfo(relDir, PROP_DOTNET, name));
                    } else if (name.endsWith(GEMSPEC_EXTENSION)) {
                        modules.putIfAbsent(relDir, new ModuleInfo(relDir, PROP_RUBY, name));
                    } else if (name.endsWith(CABAL_EXTENSION)) {
                        modules.putIfAbsent(relDir, new ModuleInfo(relDir, PROP_HASKELL, name));
                    } else if (name.endsWith(NIMBLE_EXTENSION)) {
                        modules.putIfAbsent(relDir, new ModuleInfo(relDir, "nim", name));
                    } else {
                        String buildTool = BUILD_FILES.get(name);
                        if (buildTool != null) {
                            registerModule(modules, relDir, buildTool, name);
                        }
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Could not scan filesystem for build files: {}", e.getMessage());
        }
    }

    /**
     * Register a module, respecting priority rules for Python/Docker.
     */
    private void registerModule(Map<String, ModuleInfo> modules, String dirPath,
                                 String buildTool, String fileName) {
        ModuleInfo existing = modules.get(dirPath);
        // Supplemental tools (docker, nx, lerna, turbo, rush) don't override real build tools
        if (SUPPLEMENTAL_TOOLS.contains(buildTool) && existing != null) {
            return;
        }
        // Python doesn't override non-Python
        if (existing != null && isPythonTool(buildTool) && !isPythonTool(existing.buildTool())) {
            return;
        }
        // Python priority: pyproject.toml > setup.py > requirements.txt > manage.py
        if (isPythonTool(buildTool) && existing != null && isPythonTool(existing.buildTool())) {
            if (pythonPriority(fileName) >= pythonPriority(existing.buildFile())) {
                return;
            }
        }
        // Gradle settings files don't override gradle build files
        if ("gradle".equals(buildTool) && existing != null && "gradle".equals(existing.buildTool())
                && fileName.startsWith("settings.")) {
            return;
        }
        modules.put(dirPath, new ModuleInfo(dirPath, buildTool, fileName));
    }

    /**
     * Extract service name from build file contents if possible, otherwise use directory name.
     */
    private String extractServiceName(String dir, ModuleInfo info, String projectDir, Path projectRoot) {
        // Try to read the build file and extract the real name
        if (projectRoot != null && !info.buildFile().isEmpty()) {
            String nameFromFile = readNameFromBuildFile(projectRoot, dir, info);
            if (nameFromFile != null && !nameFromFile.isBlank()) {
                return nameFromFile;
            }
        }
        // Fallback to directory-based naming
        return deriveServiceName(dir, projectDir);
    }

    /**
     * Read the build file and extract the project/module/package name.
     */
    private String readNameFromBuildFile(Path projectRoot, String dir, ModuleInfo info) {
        Path buildFile = dir.isEmpty()
                ? projectRoot.resolve(info.buildFile())
                : projectRoot.resolve(dir).resolve(info.buildFile());

        if (!Files.isRegularFile(buildFile)) {
            return null;
        }

        try {
            String content = Files.readString(buildFile, StandardCharsets.UTF_8);
            return switch (info.buildTool()) {
                case "maven" -> extractFromPom(content);
                case PROP_GRADLE -> extractFromGradleSettings(content, info.buildFile());
                case "npm" -> extractFromPackageJson(content);
                case "go" -> extractFromGoMod(content);
                case "cargo" -> extractFromCargoToml(content);
                case PROP_PYTHON -> extractFromPythonBuild(content, info.buildFile());
                case "sbt" -> extractWithPattern(content, SBT_NAME);
                case "php" -> extractWithPattern(content, COMPOSER_NAME);
                case "elixir" -> extractWithPattern(content, MIX_APP_NAME);
                case "dart" -> extractWithPattern(content, PUBSPEC_NAME);
                case PROP_DJANGO -> null;
                default -> null;
            };
        } catch (IOException e) {
            log.debug("Could not read build file {}: {}", buildFile, e.getMessage());
            return null;
        }
    }

    private String extractFromPom(String content) {
        // Find the first <artifactId> that is a direct child of <project>
        // (not inside <parent> or <dependency>). Simple heuristic: skip
        // artifactIds that appear inside a <parent> block.
        int parentEnd = content.indexOf("</parent>");
        String searchContent = parentEnd > 0 ? content.substring(parentEnd) : content;
        Matcher m = POM_ARTIFACT_ID.matcher(searchContent);
        return m.find() ? m.group(1).trim() : null;
    }

    private String extractFromPackageJson(String content) {
        Matcher m = PACKAGE_JSON_NAME.matcher(content);
        if (m.find()) {
            String name = m.group(1).trim();
            // Strip npm scope prefix (@org/name -> name)
            if (name.contains("/")) {
                name = name.substring(name.lastIndexOf('/') + 1);
            }
            return name;
        }
        return null;
    }

    private String extractFromGoMod(String content) {
        Matcher m = GO_MOD_MODULE.matcher(content);
        if (m.find()) {
            String module = m.group(1).trim();
            // Use last path segment (github.com/org/repo -> repo)
            if (module.contains("/")) {
                module = module.substring(module.lastIndexOf('/') + 1);
            }
            return module;
        }
        return null;
    }

    private String extractFromCargoToml(String content) {
        Matcher m = CARGO_PACKAGE_NAME.matcher(content);
        return m.find() ? m.group(1).trim() : null;
    }

    private String extractFromGradleSettings(String content, String buildFile) {
        if (buildFile.startsWith("settings.")) {
            Matcher m = GRADLE_SETTINGS_NAME.matcher(content);
            return m.find() ? m.group(1).trim() : null;
        }
        return null; // build.gradle doesn't typically have the project name
    }

    private String extractWithPattern(String content, Pattern pattern) {
        Matcher m = pattern.matcher(content);
        if (m.find()) {
            String name = m.group(1).trim();
            // Strip scope prefix for PHP composer (vendor/name -> name)
            if (name.contains("/")) {
                name = name.substring(name.lastIndexOf('/') + 1);
            }
            return name;
        }
        return null;
    }

    private String extractFromPythonBuild(String content, String fileName) {
        if ("pyproject.toml".equals(fileName)) {
            Matcher m = PYPROJECT_NAME.matcher(content);
            return m.find() ? m.group(1).trim() : null;
        }
        if ("setup.py".equals(fileName)) {
            Matcher m = SETUP_PY_NAME.matcher(content);
            return m.find() ? m.group(1).trim() : null;
        }
        // requirements.txt has no name
        return null;
    }

    /**
     * Derive a human-readable service name from a directory path.
     */
    private String deriveServiceName(String dir, String projectDir) {
        if (dir.isEmpty()) {
            return projectDir != null && !projectDir.isEmpty() ? projectDir : "root";
        }
        // Use the last path component
        String[] parts = dir.replace('\\', '/').split("/");
        return parts[parts.length - 1];
    }

    /**
     * Get the parent directory of a file path.
     */
    private static String parentDir(String filePath) {
        if (filePath == null) return "";
        String normalized = filePath.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash <= 0) return "";
        return normalized.substring(0, lastSlash);
    }

    private static boolean isPythonTool(String buildTool) {
        return "python".equals(buildTool) || "django".equals(buildTool);
    }

    /**
     * Priority index for Python build files (lower = higher priority).
     */
    private static int pythonPriority(String fileName) {
        int idx = PYTHON_BUILD_FILES.indexOf(fileName);
        return idx < 0 ? PYTHON_BUILD_FILES.size() : idx;
    }

    /**
     * Internal record for module metadata.
     */
    private record ModuleInfo(String directory, String buildTool, String buildFile) {}

    /**
     * Result of service detection.
     */
    public record ServiceDetectionResult(
            List<CodeNode> serviceNodes,
            List<CodeEdge> serviceEdges
    ) {}
}
