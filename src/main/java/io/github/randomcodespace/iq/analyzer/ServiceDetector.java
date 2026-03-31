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

    private static final Logger log = LoggerFactory.getLogger(ServiceDetector.class);

    /**
     * Build file patterns that indicate module boundaries.
     * Maps filename to build tool name.
     */
    private static final Map<String, String> BUILD_FILES = Map.ofEntries(
            Map.entry("pom.xml", "maven"),
            Map.entry("package.json", "npm"),
            Map.entry("go.mod", "go"),
            Map.entry("build.gradle", "gradle"),
            Map.entry("build.gradle.kts", "gradle"),
            Map.entry("Cargo.toml", "cargo"),
            Map.entry("requirements.txt", "python"),
            Map.entry("setup.py", "python"),
            Map.entry("pyproject.toml", "python"),
            Map.entry("manage.py", "django"),
            Map.entry("Dockerfile", "docker")
    );

    /** File extension for .csproj files (matched by suffix). */
    private static final String CSPROJ_EXTENSION = ".csproj";

    /** Python build files ranked by priority (first match wins for a directory). */
    private static final List<String> PYTHON_BUILD_FILES = List.of(
            "pyproject.toml", "setup.py", "requirements.txt", "manage.py"
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

            String fileName = Path.of(filePath).getFileName().toString();
            String dirPath = parentDir(filePath);

            String buildTool = BUILD_FILES.get(fileName);
            if (buildTool != null) {
                registerModule(modules, dirPath, buildTool, fileName);
            }
            if (fileName.endsWith(CSPROJ_EXTENSION)) {
                modules.putIfAbsent(dirPath, new ModuleInfo(dirPath, "dotnet", fileName));
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
    private void scanFilesystemForBuildFiles(Path root, Path projectRoot, Map<String, ModuleInfo> modules) {
        try (var stream = Files.walk(root, 10)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return BUILD_FILES.containsKey(name) || name.endsWith(CSPROJ_EXTENSION);
                    })
                    .sorted() // deterministic
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        String relDir = projectRoot.relativize(p.getParent()).toString()
                                .replace('\\', '/');
                        if (relDir.equals(".")) relDir = "";

                        // Skip node_modules, .git, target, build directories
                        if (relDir.contains("node_modules") || relDir.contains(".git/")
                                || relDir.contains("/target/") || relDir.startsWith("target/")
                                || relDir.contains("/build/") || relDir.startsWith("build/")) {
                            return;
                        }

                        if (name.endsWith(CSPROJ_EXTENSION)) {
                            modules.putIfAbsent(relDir, new ModuleInfo(relDir, "dotnet", name));
                        } else {
                            String buildTool = BUILD_FILES.get(name);
                            if (buildTool != null) {
                                registerModule(modules, relDir, buildTool, name);
                            }
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
        // Python doesn't override non-Python
        if (existing != null && isPythonTool(buildTool) && !isPythonTool(existing.buildTool())) {
            return;
        }
        // Docker doesn't override anything
        if ("docker".equals(buildTool) && existing != null) {
            return;
        }
        // Python priority: pyproject.toml > setup.py > requirements.txt > manage.py
        if (isPythonTool(buildTool) && existing != null && isPythonTool(existing.buildTool())) {
            if (pythonPriority(fileName) >= pythonPriority(existing.buildFile())) {
                return;
            }
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
                case "npm" -> extractFromPackageJson(content);
                case "go" -> extractFromGoMod(content);
                case "cargo" -> extractFromCargoToml(content);
                case "python" -> extractFromPythonBuild(content, info.buildFile());
                case "django" -> null; // manage.py doesn't contain the name
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
