package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.detector.Detector;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.DetectorRegistry;
import io.github.randomcodespace.iq.detector.DetectorUtils;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/**
 * Discover and inspect available detectors (plugins).
 */
@Component
@Command(name = "plugins", mixinStandardHelpOptions = true,
        description = "Discover and inspect detectors",
        subcommands = {
                PluginsCommand.ListSubcommand.class,
                PluginsCommand.InfoSubcommand.class,
                PluginsCommand.LanguagesSubcommand.class,
                PluginsCommand.SuggestSubcommand.class,
                PluginsCommand.DocsSubcommand.class
        })
public class PluginsCommand implements Runnable {

    private final DetectorRegistry registry;

    public PluginsCommand(DetectorRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void run() {
        // Default action: list detectors
        new ListSubcommand(registry).call();
    }

    // ========== list ==========

    @Component
    @Command(name = "list", mixinStandardHelpOptions = true,
            description = "List all available detector categories")
    static class ListSubcommand implements Callable<Integer> {

        private final DetectorRegistry registry;

        ListSubcommand(DetectorRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Integer call() {
            var detectors = registry.allDetectors();
            CliOutput.bold("Available detectors (" + detectors.size() + "):");
            System.out.println();

            // Group by category
            Map<String, List<Detector>> byCategory = registry.byCategory();

            // Print header
            System.out.printf("  %-16s %-10s %s%n", "Category", "Detectors", "Description");
            System.out.println("  " + "-".repeat(60));

            for (var entry : byCategory.entrySet()) {
                String cat = entry.getKey();
                List<Detector> catDetectors = entry.getValue();
                String desc = categoryDescription(cat, catDetectors);
                System.out.printf("  %-16s %-10d %s%n", cat, catDetectors.size(), desc);
            }

            System.out.println();

            // Collect all languages
            Set<String> allLanguages = registry.allLanguages();
            CliOutput.info("Supported languages (" + allLanguages.size() + "): "
                    + String.join(", ", allLanguages));

            return 0;
        }

        /**
         * Derive a short description for a category.
         * Checks DetectorInfo annotations on detectors in the category,
         * or falls back to a sensible default.
         */
        static String categoryDescription(String category, List<Detector> detectors) {
            for (Detector d : detectors) {
                DetectorInfo info = d.getClass().getAnnotation(DetectorInfo.class);
                if (info != null && info.description() != null && !info.description().isEmpty()) {
                    // Use the first non-empty description we find
                    return info.description();
                }
            }
            // Fallback descriptions by known category names
            return switch (category) {
                case "java" -> "Java framework detectors (Spring, JPA, JAX-RS, etc.)";
                case "python" -> "Python framework detectors (Django, Flask, FastAPI, etc.)";
                case "typescript" -> "TypeScript/JS framework detectors (Express, NestJS, etc.)";
                case "kotlin" -> "Kotlin framework detectors (Ktor, etc.)";
                case "go" -> "Go framework detectors (Gin, GORM, etc.)";
                case "rust" -> "Rust framework detectors (Actix, etc.)";
                case "csharp" -> "C# framework detectors (EF Core, Minimal APIs, etc.)";
                case "cpp" -> "C++ structure detectors";
                case "scala" -> "Scala structure detectors";
                case "config" -> "Configuration and CI/CD detectors";
                case "auth" -> "Authentication and authorization detectors";
                case "frontend" -> "Frontend component and route detectors";
                case "iac" -> "Infrastructure-as-Code detectors (Terraform, Docker, etc.)";
                case "docs" -> "Documentation structure detectors";
                case "generic" -> "Cross-language generic detectors";
                case "proto" -> "Protocol Buffers structure detectors";
                case "shell" -> "Shell script detectors (Bash, PowerShell)";
                default -> detectors.size() + " detector(s)";
            };
        }
    }

    // ========== info ==========

    @Component
    @Command(name = "info", mixinStandardHelpOptions = true,
            description = "Show details for a category or specific detector")
    static class InfoSubcommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Category name or category/detector-name")
        private String query;

        private final DetectorRegistry registry;

        InfoSubcommand(DetectorRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Integer call() {
            List<Detector> matches = registry.findByQuery(query);
            if (matches.isEmpty()) {
                CliOutput.error("No detectors found for: " + query);
                CliOutput.info("Use 'code-iq plugins list' to see available categories.");
                return 1;
            }

            if (matches.size() == 1) {
                printDetectorDetail(matches.get(0));
            } else {
                // Category listing
                String category = DetectorRegistry.categoryOf(matches.get(0));
                CliOutput.bold("Category: " + category + " (" + matches.size() + " detectors)");
                System.out.println();
                for (Detector d : matches) {
                    printDetectorSummary(d);
                }
            }
            return 0;
        }

        private void printDetectorDetail(Detector d) {
            CliOutput.bold(d.getName());
            CliOutput.info("  Category:   " + DetectorRegistry.categoryOf(d));
            CliOutput.info("  Languages:  " + String.join(", ",
                    new TreeSet<>(d.getSupportedLanguages())));
            CliOutput.info("  Class:      " + d.getClass().getName());

            DetectorInfo info = d.getClass().getAnnotation(DetectorInfo.class);
            if (info != null) {
                if (!info.description().isEmpty()) {
                    CliOutput.info("  Description: " + info.description());
                }
                CliOutput.info("  Parser:     " + info.parser().name().toLowerCase());
                if (info.nodeKinds().length > 0) {
                    List<String> kinds = new ArrayList<>();
                    for (var nk : info.nodeKinds()) kinds.add(nk.getValue());
                    CliOutput.info("  Node kinds: " + String.join(", ", kinds));
                }
                if (info.edgeKinds().length > 0) {
                    List<String> kinds = new ArrayList<>();
                    for (var ek : info.edgeKinds()) kinds.add(ek.getValue());
                    CliOutput.info("  Edge kinds: " + String.join(", ", kinds));
                }
                if (info.properties().length > 0) {
                    CliOutput.info("  Properties: " + String.join(", ", info.properties()));
                }
            }
        }

        private void printDetectorSummary(Detector d) {
            String langs = String.join(", ", new TreeSet<>(d.getSupportedLanguages()));
            DetectorInfo info = d.getClass().getAnnotation(DetectorInfo.class);
            String desc = "";
            if (info != null && !info.description().isEmpty()) {
                desc = " - " + info.description();
            }
            CliOutput.print(System.out,
                    "  @|bold " + d.getName() + "|@  @|faint [" + langs + "]|@" + desc);
        }
    }

    // ========== languages ==========

    @Component
    @Command(name = "languages", mixinStandardHelpOptions = true,
            description = "List all supported languages")
    static class LanguagesSubcommand implements Callable<Integer> {

        private final DetectorRegistry registry;

        LanguagesSubcommand(DetectorRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Integer call() {
            Set<String> allLanguages = registry.allLanguages();
            CliOutput.bold("Supported languages (" + allLanguages.size() + "):");
            System.out.println();

            System.out.printf("  %-16s %-12s %s%n", "Language", "Detectors", "Extensions");
            System.out.println("  " + "-".repeat(56));

            for (String lang : allLanguages) {
                List<Detector> detectors = registry.detectorsForLanguage(lang);
                List<String> extensions = extensionsForLanguage(lang);
                System.out.printf("  %-16s %-12d %s%n", lang, detectors.size(),
                        String.join(", ", extensions));
            }
            return 0;
        }

        /**
         * Reverse lookup: find file extensions associated with a language.
         */
        static List<String> extensionsForLanguage(String language) {
            // Uses the EXTENSION_MAP from DetectorUtils via reflection-free approach:
            // we maintain a known mapping here. This is intentionally duplicated
            // to avoid exposing DetectorUtils internals.
            Map<String, List<String>> langToExt = buildLanguageExtensionMap();
            return langToExt.getOrDefault(language, List.of());
        }

        private static Map<String, List<String>> buildLanguageExtensionMap() {
            Map<String, List<String>> map = new TreeMap<>();
            // Same as DetectorUtils.EXTENSION_MAP but reversed
            addExt(map, "java", ".java");
            addExt(map, "python", ".py", ".pyi");
            addExt(map, "typescript", ".ts", ".tsx", ".mts", ".cts");
            addExt(map, "javascript", ".js", ".jsx", ".mjs", ".cjs");
            addExt(map, "xml", ".xml");
            addExt(map, "yaml", ".yaml", ".yml");
            addExt(map, "json", ".json", ".jsonc");
            addExt(map, "properties", ".properties");
            addExt(map, "gradle", ".gradle");
            addExt(map, "sql", ".sql");
            addExt(map, "graphql", ".graphql", ".gql");
            addExt(map, "proto", ".proto");
            addExt(map, "markdown", ".md", ".markdown");
            addExt(map, "bicep", ".bicep");
            addExt(map, "terraform", ".tf", ".tfvars", ".hcl");
            addExt(map, "csharp", ".cs");
            addExt(map, "go", ".go");
            addExt(map, "cpp", ".cpp", ".cc", ".cxx", ".hpp");
            addExt(map, "c", ".c", ".h");
            addExt(map, "bash", ".sh", ".bash", ".zsh");
            addExt(map, "powershell", ".ps1", ".psm1", ".psd1");
            addExt(map, "batch", ".bat", ".cmd");
            addExt(map, "ruby", ".rb");
            addExt(map, "rust", ".rs");
            addExt(map, "kotlin", ".kt", ".kts");
            addExt(map, "scala", ".scala");
            addExt(map, "swift", ".swift");
            addExt(map, "r", ".r", ".R");
            addExt(map, "perl", ".pl", ".pm");
            addExt(map, "lua", ".lua");
            addExt(map, "dart", ".dart");
            addExt(map, "dockerfile", ".dockerfile");
            addExt(map, "toml", ".toml");
            addExt(map, "ini", ".ini", ".cfg", ".conf");
            addExt(map, "dotenv", ".env");
            addExt(map, "csv", ".csv");
            addExt(map, "vue", ".vue");
            addExt(map, "svelte", ".svelte");
            addExt(map, "html", ".html", ".htm");
            addExt(map, "css", ".css");
            addExt(map, "scss", ".scss");
            addExt(map, "less", ".less");
            addExt(map, "groovy", ".groovy");
            addExt(map, "razor", ".razor");
            addExt(map, "cshtml", ".cshtml");
            addExt(map, "asciidoc", ".adoc");
            return map;
        }

        private static void addExt(Map<String, List<String>> map, String lang, String... exts) {
            map.put(lang, List.of(exts));
        }
    }

    // ========== suggest ==========

    @Component
    @Command(name = "suggest", mixinStandardHelpOptions = true,
            description = "Suggest an optimized .osscodeiq.yml for a project")
    static class SuggestSubcommand implements Callable<Integer> {

        @Parameters(index = "0", defaultValue = ".",
                description = "Path to the project directory")
        private Path projectPath;

        private final DetectorRegistry registry;

        SuggestSubcommand(DetectorRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Integer call() {
            Path root = projectPath.toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                CliOutput.error("Not a directory: " + root);
                return 1;
            }

            // Count files by extension
            Map<String, Integer> extCounts = new TreeMap<>();
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    private static final Set<String> SKIP_DIRS = Set.of(
                            "node_modules", ".git", "build", "target", "dist",
                            "__pycache__", "venv", ".venv", ".gradle",
                            ".idea", ".vscode", ".code-iq", ".code-intelligence");

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                        if (SKIP_DIRS.contains(name)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                        String name = file.getFileName().toString();
                        int dot = name.lastIndexOf('.');
                        if (dot > 0) {
                            String ext = name.substring(dot);
                            extCounts.merge(ext, 1, Integer::sum);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                CliOutput.error("Failed to scan directory: " + e.getMessage());
                return 1;
            }

            // Map extensions to languages
            Map<String, Integer> languageCounts = new TreeMap<>();
            for (var entry : extCounts.entrySet()) {
                String lang = DetectorUtils.deriveLanguage("file" + entry.getKey());
                if (lang != null) {
                    languageCounts.merge(lang, entry.getValue(), Integer::sum);
                }
            }

            if (languageCounts.isEmpty()) {
                CliOutput.warn("No recognized source files found in " + root);
                return 1;
            }

            // Find relevant categories
            Set<String> relevantCategories = new TreeSet<>();
            for (String lang : languageCounts.keySet()) {
                List<Detector> detectors = registry.detectorsForLanguage(lang);
                for (Detector d : detectors) {
                    relevantCategories.add(DetectorRegistry.categoryOf(d));
                }
            }

            // Generate YAML config
            StringBuilder yaml = new StringBuilder();
            yaml.append("# Auto-generated Code IQ configuration\n");
            yaml.append("# Optimized for this project's detected languages\n\n");

            yaml.append("languages:\n");
            for (Map.Entry<String, Integer> entry : languageCounts.entrySet()) {
                yaml.append("  - ").append(entry.getKey())
                        .append("  # ").append(entry.getValue()).append(" files\n");
            }

            yaml.append("\ndetectors:\n");
            yaml.append("  categories:\n");
            for (String cat : relevantCategories) {
                yaml.append("    - ").append(cat).append("\n");
            }

            yaml.append("\npipeline:\n");
            int totalFiles = languageCounts.values().stream().mapToInt(Integer::intValue).sum();
            if (totalFiles > 5000) {
                yaml.append("  parallelism: ").append(Runtime.getRuntime().availableProcessors()).append("\n");
            } else {
                yaml.append("  # parallelism: auto  # virtual threads\n");
            }

            yaml.append("\nexclude:\n");
            yaml.append("  # - \"**/generated/**\"\n");
            yaml.append("  # - \"**/test/**\"\n");

            System.out.print(yaml);
            return 0;
        }
    }

    // ========== docs ==========

    @Component
    @Command(name = "docs", mixinStandardHelpOptions = true,
            description = "Generate detector reference documentation")
    static class DocsSubcommand implements Callable<Integer> {

        @Option(names = {"--format", "-f"}, defaultValue = "markdown",
                description = "Output format: markdown, json, yaml")
        private String format;

        private final DetectorRegistry registry;

        DocsSubcommand(DetectorRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Integer call() {
            return switch (format.toLowerCase()) {
                case "json" -> generateJson();
                case "yaml" -> generateYaml();
                default -> generateMarkdown();
            };
        }

        private int generateMarkdown() {
            var sb = new StringBuilder();
            sb.append("# Code IQ Detector Reference\n\n");
            sb.append("Total detectors: ").append(registry.count()).append("\n\n");

            Map<String, List<Detector>> byCategory = registry.byCategory();
            for (var entry : byCategory.entrySet()) {
                String cat = entry.getKey();
                List<Detector> detectors = entry.getValue();
                sb.append("## ").append(cat).append(" (").append(detectors.size()).append(")\n\n");

                for (Detector d : detectors) {
                    sb.append("### ").append(d.getName()).append("\n\n");
                    sb.append("- **Languages:** ").append(
                            String.join(", ", new TreeSet<>(d.getSupportedLanguages()))).append("\n");
                    DetectorInfo info = d.getClass().getAnnotation(DetectorInfo.class);
                    if (info != null) {
                        sb.append("- **Description:** ").append(info.description()).append("\n");
                        sb.append("- **Parser:** ").append(info.parser().name().toLowerCase()).append("\n");
                        if (info.nodeKinds().length > 0) {
                            List<String> kinds = new ArrayList<>();
                            for (var nk : info.nodeKinds()) kinds.add(nk.getValue());
                            sb.append("- **Node kinds:** ").append(String.join(", ", kinds)).append("\n");
                        }
                        if (info.edgeKinds().length > 0) {
                            List<String> kinds = new ArrayList<>();
                            for (var ek : info.edgeKinds()) kinds.add(ek.getValue());
                            sb.append("- **Edge kinds:** ").append(String.join(", ", kinds)).append("\n");
                        }
                    }
                    sb.append("\n");
                }
            }
            System.out.print(sb);
            return 0;
        }

        private int generateJson() {
            var sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"total\": ").append(registry.count()).append(",\n");
            sb.append("  \"categories\": {\n");

            Map<String, List<Detector>> byCategory = registry.byCategory();
            var catEntries = new ArrayList<>(byCategory.entrySet());
            for (int c = 0; c < catEntries.size(); c++) {
                var entry = catEntries.get(c);
                sb.append("    \"").append(jsonEscape(entry.getKey())).append("\": [\n");

                List<Detector> detectors = entry.getValue();
                for (int i = 0; i < detectors.size(); i++) {
                    Detector d = detectors.get(i);
                    sb.append("      {\n");
                    sb.append("        \"name\": \"").append(jsonEscape(d.getName())).append("\",\n");
                    sb.append("        \"languages\": [");
                    var langs = new ArrayList<>(new TreeSet<>(d.getSupportedLanguages()));
                    for (int j = 0; j < langs.size(); j++) {
                        if (j > 0) sb.append(", ");
                        sb.append("\"").append(jsonEscape(langs.get(j))).append("\"");
                    }
                    sb.append("],\n");
                    sb.append("        \"category\": \"").append(jsonEscape(DetectorRegistry.categoryOf(d))).append("\"");

                    DetectorInfo info = d.getClass().getAnnotation(DetectorInfo.class);
                    if (info != null) {
                        sb.append(",\n");
                        sb.append("        \"description\": \"").append(jsonEscape(info.description())).append("\",\n");
                        sb.append("        \"parser\": \"").append(info.parser().name().toLowerCase()).append("\"");
                    }
                    sb.append("\n      }");
                    if (i < detectors.size() - 1) sb.append(",");
                    sb.append("\n");
                }
                sb.append("    ]");
                if (c < catEntries.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("  }\n");
            sb.append("}\n");
            System.out.print(sb);
            return 0;
        }

        private int generateYaml() {
            var sb = new StringBuilder();
            sb.append("total: ").append(registry.count()).append("\n");
            sb.append("categories:\n");

            Map<String, List<Detector>> byCategory = registry.byCategory();
            for (var entry : byCategory.entrySet()) {
                sb.append("  ").append(entry.getKey()).append(":\n");
                for (Detector d : entry.getValue()) {
                    sb.append("    - name: ").append(d.getName()).append("\n");
                    sb.append("      languages: [").append(
                            String.join(", ", new TreeSet<>(d.getSupportedLanguages()))).append("]\n");
                    sb.append("      category: ").append(DetectorRegistry.categoryOf(d)).append("\n");

                    DetectorInfo info = d.getClass().getAnnotation(DetectorInfo.class);
                    if (info != null) {
                        sb.append("      description: \"").append(info.description()).append("\"\n");
                        sb.append("      parser: ").append(info.parser().name().toLowerCase()).append("\n");
                    }
                }
            }
            System.out.print(sb);
            return 0;
        }

        private static String jsonEscape(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }
    }
}
