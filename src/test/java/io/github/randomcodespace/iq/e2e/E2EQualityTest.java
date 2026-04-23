package io.github.randomcodespace.iq.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.randomcodespace.iq.analyzer.AnalysisResult;
import io.github.randomcodespace.iq.analyzer.Analyzer;
import io.github.randomcodespace.iq.analyzer.FileDiscovery;
import io.github.randomcodespace.iq.analyzer.LayerClassifier;
import io.github.randomcodespace.iq.analyzer.StructuredParser;
import io.github.randomcodespace.iq.analyzer.linker.EntityLinker;
import io.github.randomcodespace.iq.analyzer.linker.Linker;
import io.github.randomcodespace.iq.analyzer.linker.ModuleContainmentLinker;
import io.github.randomcodespace.iq.analyzer.linker.TopicLinker;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.detector.Detector;
import io.github.randomcodespace.iq.detector.DetectorRegistry;
import io.github.randomcodespace.iq.detector.auth.CertificateAuthDetector;
import io.github.randomcodespace.iq.detector.auth.LdapAuthDetector;
import io.github.randomcodespace.iq.detector.auth.SessionHeaderAuthDetector;
import io.github.randomcodespace.iq.detector.structured.BatchStructureDetector;
import io.github.randomcodespace.iq.detector.structured.CloudFormationDetector;
import io.github.randomcodespace.iq.detector.structured.DockerComposeDetector;
import io.github.randomcodespace.iq.detector.structured.GitHubActionsDetector;
import io.github.randomcodespace.iq.detector.structured.GitLabCiDetector;
import io.github.randomcodespace.iq.detector.structured.HelmChartDetector;
import io.github.randomcodespace.iq.detector.structured.IniStructureDetector;
import io.github.randomcodespace.iq.detector.structured.JsonStructureDetector;
import io.github.randomcodespace.iq.detector.structured.KubernetesDetector;
import io.github.randomcodespace.iq.detector.structured.KubernetesRbacDetector;
import io.github.randomcodespace.iq.detector.structured.OpenApiDetector;
import io.github.randomcodespace.iq.detector.structured.PackageJsonDetector;
import io.github.randomcodespace.iq.detector.structured.PropertiesDetector;
import io.github.randomcodespace.iq.detector.structured.PyprojectTomlDetector;
import io.github.randomcodespace.iq.detector.structured.SqlStructureDetector;
import io.github.randomcodespace.iq.detector.structured.TomlStructureDetector;
import io.github.randomcodespace.iq.detector.structured.TsconfigJsonDetector;
import io.github.randomcodespace.iq.detector.structured.YamlStructureDetector;
import io.github.randomcodespace.iq.detector.systems.cpp.CppStructuresDetector;
import io.github.randomcodespace.iq.detector.csharp.CSharpEfcoreDetector;
import io.github.randomcodespace.iq.detector.csharp.CSharpMinimalApisDetector;
import io.github.randomcodespace.iq.detector.csharp.CSharpStructuresDetector;
import io.github.randomcodespace.iq.detector.markup.MarkdownStructureDetector;
import io.github.randomcodespace.iq.detector.frontend.AngularComponentDetector;
import io.github.randomcodespace.iq.detector.frontend.FrontendRouteDetector;
import io.github.randomcodespace.iq.detector.frontend.ReactComponentDetector;
import io.github.randomcodespace.iq.detector.frontend.SvelteComponentDetector;
import io.github.randomcodespace.iq.detector.frontend.VueComponentDetector;
import io.github.randomcodespace.iq.detector.generic.GenericImportsDetector;
import io.github.randomcodespace.iq.detector.go.GoOrmDetector;
import io.github.randomcodespace.iq.detector.go.GoStructuresDetector;
import io.github.randomcodespace.iq.detector.go.GoWebDetector;
import io.github.randomcodespace.iq.detector.iac.BicepDetector;
import io.github.randomcodespace.iq.detector.iac.DockerfileDetector;
import io.github.randomcodespace.iq.detector.iac.TerraformDetector;
import io.github.randomcodespace.iq.detector.jvm.java.AzureFunctionsDetector;
import io.github.randomcodespace.iq.detector.jvm.java.AzureMessagingDetector;
import io.github.randomcodespace.iq.detector.jvm.java.ClassHierarchyDetector;
import io.github.randomcodespace.iq.detector.jvm.java.ConfigDefDetector;
import io.github.randomcodespace.iq.detector.jvm.java.CosmosDbDetector;
import io.github.randomcodespace.iq.detector.jvm.java.GraphqlResolverDetector;
import io.github.randomcodespace.iq.detector.jvm.java.GrpcServiceDetector;
import io.github.randomcodespace.iq.detector.jvm.java.IbmMqDetector;
import io.github.randomcodespace.iq.detector.jvm.java.JaxrsDetector;
import io.github.randomcodespace.iq.detector.jvm.java.JdbcDetector;
import io.github.randomcodespace.iq.detector.jvm.java.JmsDetector;
import io.github.randomcodespace.iq.detector.jvm.java.JpaEntityDetector;
import io.github.randomcodespace.iq.detector.jvm.java.KafkaDetector;
import io.github.randomcodespace.iq.detector.jvm.java.KafkaProtocolDetector;
import io.github.randomcodespace.iq.detector.jvm.java.MicronautDetector;
import io.github.randomcodespace.iq.detector.jvm.java.ModuleDepsDetector;
import io.github.randomcodespace.iq.detector.jvm.java.PublicApiDetector;
import io.github.randomcodespace.iq.detector.jvm.java.QuarkusDetector;
import io.github.randomcodespace.iq.detector.jvm.java.RabbitmqDetector;
import io.github.randomcodespace.iq.detector.jvm.java.RawSqlDetector;
import io.github.randomcodespace.iq.detector.jvm.java.RepositoryDetector;
import io.github.randomcodespace.iq.detector.jvm.java.RmiDetector;
import io.github.randomcodespace.iq.detector.jvm.java.SpringEventsDetector;
import io.github.randomcodespace.iq.detector.jvm.java.SpringRestDetector;
import io.github.randomcodespace.iq.detector.jvm.java.SpringSecurityDetector;
import io.github.randomcodespace.iq.detector.jvm.java.TibcoEmsDetector;
import io.github.randomcodespace.iq.detector.jvm.java.WebSocketDetector;
import io.github.randomcodespace.iq.detector.jvm.kotlin.KotlinStructuresDetector;
import io.github.randomcodespace.iq.detector.jvm.kotlin.KtorRouteDetector;
import io.github.randomcodespace.iq.detector.proto.ProtoStructureDetector;
import io.github.randomcodespace.iq.detector.python.CeleryTaskDetector;
import io.github.randomcodespace.iq.detector.python.DjangoAuthDetector;
import io.github.randomcodespace.iq.detector.python.DjangoModelDetector;
import io.github.randomcodespace.iq.detector.python.DjangoViewDetector;
import io.github.randomcodespace.iq.detector.python.FastAPIAuthDetector;
import io.github.randomcodespace.iq.detector.python.FastAPIRouteDetector;
import io.github.randomcodespace.iq.detector.python.FlaskRouteDetector;
import io.github.randomcodespace.iq.detector.python.KafkaPythonDetector;
import io.github.randomcodespace.iq.detector.python.PydanticModelDetector;
import io.github.randomcodespace.iq.detector.python.PythonStructuresDetector;
import io.github.randomcodespace.iq.detector.python.SQLAlchemyModelDetector;
import io.github.randomcodespace.iq.detector.systems.rust.ActixWebDetector;
import io.github.randomcodespace.iq.detector.systems.rust.RustStructuresDetector;
import io.github.randomcodespace.iq.detector.jvm.scala.ScalaStructuresDetector;
import io.github.randomcodespace.iq.detector.script.shell.BashDetector;
import io.github.randomcodespace.iq.detector.script.shell.PowerShellDetector;
import io.github.randomcodespace.iq.detector.typescript.ExpressRouteDetector;
import io.github.randomcodespace.iq.detector.typescript.FastifyRouteDetector;
import io.github.randomcodespace.iq.detector.typescript.GraphQLResolverDetector;
import io.github.randomcodespace.iq.detector.typescript.KafkaJSDetector;
import io.github.randomcodespace.iq.detector.typescript.MongooseORMDetector;
import io.github.randomcodespace.iq.detector.typescript.NestJSControllerDetector;
import io.github.randomcodespace.iq.detector.typescript.NestJSGuardsDetector;
import io.github.randomcodespace.iq.detector.typescript.PassportJwtDetector;
import io.github.randomcodespace.iq.detector.typescript.PrismaORMDetector;
import io.github.randomcodespace.iq.detector.typescript.RemixRouteDetector;
import io.github.randomcodespace.iq.detector.typescript.SequelizeORMDetector;
import io.github.randomcodespace.iq.detector.typescript.TypeORMEntityDetector;
import io.github.randomcodespace.iq.detector.typescript.TypeScriptStructuresDetector;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end quality validation test for the analysis pipeline.
 * <p>
 * Runs a full analysis against the spring-petclinic repo and validates
 * detection quality against a ground truth file. Only runs when the
 * {@code E2E_PETCLINIC_DIR} env var points to a cloned spring-petclinic repo.
 * <p>
 * Usage:
 * <pre>
 * E2E_PETCLINIC_DIR=$HOME/repos/spring-petclinic \
 *   mvn test -Dtest="E2EQualityTest" -Dsurefire.excludes="" -pl .
 * </pre>
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "E2E_PETCLINIC_DIR", matches = ".+")
class E2EQualityTest {

    private static final String GROUND_TRUTH_RESOURCE = "/e2e/ground-truth-petclinic.json";

    private AnalysisResult analysisResult;
    private List<CodeNode> allNodes;
    private List<CodeEdge> allEdges;
    private JsonNode groundTruth;

    // ── Helpers for node/edge extraction ──────────────────────────────────

    private List<CodeNode> nodesByKind(NodeKind kind) {
        return allNodes.stream()
                .filter(n -> n.getKind() == kind)
                .toList();
    }

    private Set<String> nodeLabels(NodeKind kind) {
        return nodesByKind(kind).stream()
                .map(CodeNode::getLabel)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private Set<String> nodeLabelsCaseInsensitive(NodeKind kind) {
        return nodesByKind(kind).stream()
                .map(n -> n.getLabel().toLowerCase())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    // ── Setup ─────────────────────────────────────────────────────────────

    private static List<Detector> allDetectors() {
        Detector[] detectors = {
                // Auth
                new CertificateAuthDetector(),
                new LdapAuthDetector(),
                new SessionHeaderAuthDetector(),
                // Config / Infra
                new BatchStructureDetector(),
                new CloudFormationDetector(),
                new DockerComposeDetector(),
                new GitHubActionsDetector(),
                new GitLabCiDetector(),
                new HelmChartDetector(),
                new IniStructureDetector(),
                new JsonStructureDetector(),
                new KubernetesDetector(),
                new KubernetesRbacDetector(),
                new OpenApiDetector(),
                new PackageJsonDetector(),
                new PropertiesDetector(),
                new PyprojectTomlDetector(),
                new SqlStructureDetector(),
                new TomlStructureDetector(),
                new TsconfigJsonDetector(),
                new YamlStructureDetector(),
                // C++
                new CppStructuresDetector(),
                // C#
                new CSharpEfcoreDetector(),
                new CSharpMinimalApisDetector(),
                new CSharpStructuresDetector(),
                // Docs
                new MarkdownStructureDetector(),
                // Frontend
                new AngularComponentDetector(),
                new FrontendRouteDetector(),
                new ReactComponentDetector(),
                new SvelteComponentDetector(),
                new VueComponentDetector(),
                // Generic
                new GenericImportsDetector(),
                // Go
                new GoOrmDetector(),
                new GoStructuresDetector(),
                new GoWebDetector(),
                // IaC
                new BicepDetector(),
                new DockerfileDetector(),
                new TerraformDetector(),
                // Java
                new AzureFunctionsDetector(),
                new AzureMessagingDetector(),
                new ClassHierarchyDetector(),
                new ConfigDefDetector(),
                new CosmosDbDetector(),
                new GraphqlResolverDetector(),
                new GrpcServiceDetector(),
                new IbmMqDetector(),
                new JaxrsDetector(),
                new JdbcDetector(),
                new JmsDetector(),
                new JpaEntityDetector(),
                new KafkaDetector(),
                new KafkaProtocolDetector(),
                new MicronautDetector(),
                new ModuleDepsDetector(),
                new PublicApiDetector(),
                new QuarkusDetector(),
                new RabbitmqDetector(),
                new RawSqlDetector(),
                new RepositoryDetector(),
                new RmiDetector(),
                new SpringEventsDetector(),
                new SpringRestDetector(),
                new SpringSecurityDetector(),
                new TibcoEmsDetector(),
                new WebSocketDetector(),
                // Kotlin
                new KotlinStructuresDetector(),
                new KtorRouteDetector(),
                // Proto
                new ProtoStructureDetector(),
                // Python
                new CeleryTaskDetector(),
                new DjangoAuthDetector(),
                new DjangoModelDetector(),
                new DjangoViewDetector(),
                new FastAPIAuthDetector(),
                new FastAPIRouteDetector(),
                new FlaskRouteDetector(),
                new KafkaPythonDetector(),
                new PydanticModelDetector(),
                new PythonStructuresDetector(),
                new SQLAlchemyModelDetector(),
                // Rust
                new ActixWebDetector(),
                new RustStructuresDetector(),
                // Scala
                new ScalaStructuresDetector(),
                // Shell
                new BashDetector(),
                new PowerShellDetector(),
                // TypeScript
                new ExpressRouteDetector(),
                new FastifyRouteDetector(),
                new GraphQLResolverDetector(),
                new KafkaJSDetector(),
                new MongooseORMDetector(),
                new NestJSControllerDetector(),
                new NestJSGuardsDetector(),
                new PassportJwtDetector(),
                new PrismaORMDetector(),
                new RemixRouteDetector(),
                new SequelizeORMDetector(),
                new TypeORMEntityDetector(),
                new TypeScriptStructuresDetector()
        };
        return List.of(detectors);
    }

    private static List<Linker> allLinkers() {
        return List.of(
                new EntityLinker(),
                new ModuleContainmentLinker(),
                new TopicLinker()
        );
    }

    private Analyzer buildAnalyzer() {
        var detectors = allDetectors();
        var registry = new DetectorRegistry(detectors);
        var parser = new StructuredParser();
        var config = new CodeIqConfig();
        var fileDiscovery = new FileDiscovery(config);
        var layerClassifier = new LayerClassifier();
        var linkers = allLinkers();
        return new Analyzer(registry, parser, fileDiscovery, layerClassifier, linkers, config);
    }

    @BeforeAll
    void runAnalysisAndLoadGroundTruth() throws IOException {
        // Load ground truth
        try (InputStream is = getClass().getResourceAsStream(GROUND_TRUTH_RESOURCE)) {
            assertNotNull(is, "Ground truth file not found: " + GROUND_TRUTH_RESOURCE);
            groundTruth = new ObjectMapper().readTree(is);
        }

        // Run analysis
        Path repoPath = Path.of(System.getenv("E2E_PETCLINIC_DIR"));
        assertTrue(Files.isDirectory(repoPath),
                "E2E_PETCLINIC_DIR does not point to a valid directory: " + repoPath);

        Analyzer analyzer = buildAnalyzer();
        analysisResult = analyzer.run(repoPath, msg -> System.out.println("  [e2e] " + msg));

        assertNotNull(analysisResult.nodes(), "Analysis must return nodes (not null)");

        allNodes = analysisResult.nodes();

        // Collect all edges from nodes
        allEdges = new ArrayList<>();
        for (CodeNode node : allNodes) {
            allEdges.addAll(node.getEdges());
        }

        // Print summary
        System.out.println();
        System.out.println("=== E2E QUALITY TEST - Petclinic Analysis Summary ===");
        System.out.printf("  Files: %d discovered, %d analyzed%n",
                analysisResult.totalFiles(), analysisResult.filesAnalyzed());
        System.out.printf("  Nodes: %d, Edges: %d%n",
                analysisResult.nodeCount(), analysisResult.edgeCount());
        System.out.printf("  Time:  %dms%n", analysisResult.elapsed().toMillis());
        System.out.printf("  Node kinds: %s%n", analysisResult.nodeBreakdown());
        System.out.printf("  Edge kinds: %s%n", analysisResult.edgeBreakdown());
        System.out.printf("  Languages:  %s%n", analysisResult.languageBreakdown());
        System.out.printf("  Frameworks: %s%n", analysisResult.frameworkBreakdown());
    }

    // ── Minimum count validations ─────────────────────────────────────────

    @Test
    void minNodeCountMet() {
        int expected = groundTruth.at("/expected_stats/min_nodes").asInt();
        assertTrue(analysisResult.nodeCount() >= expected,
                "Expected at least %d nodes but got %d".formatted(expected, analysisResult.nodeCount()));
    }

    @Test
    void minEdgeCountMet() {
        int expected = groundTruth.at("/expected_stats/min_edges").asInt();
        assertTrue(analysisResult.edgeCount() >= expected,
                "Expected at least %d edges but got %d".formatted(expected, analysisResult.edgeCount()));
    }

    @Test
    void minFileCountMet() {
        int expected = groundTruth.at("/expected_stats/min_files").asInt();
        assertTrue(analysisResult.totalFiles() >= expected,
                "Expected at least %d files but got %d".formatted(expected, analysisResult.totalFiles()));
    }

    // ── Primary language validation ───────────────────────────────────────

    @Test
    void primaryLanguageIsJava() {
        String expected = groundTruth.at("/expected_stats/primary_language").asText();
        Map<String, Integer> langBreakdown = analysisResult.languageBreakdown();

        assertTrue(langBreakdown.containsKey(expected),
                "Expected primary language '%s' not found in language breakdown: %s"
                        .formatted(expected, langBreakdown.keySet()));

        // Verify it's the top language by file count
        String topLanguage = langBreakdown.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("none");

        assertEquals(expected, topLanguage,
                "Expected primary language '%s' but top language is '%s'. Breakdown: %s"
                        .formatted(expected, topLanguage, langBreakdown));
    }

    // ── Entity detection ──────────────────────────────────────────────────

    @Test
    void allExpectedEntitiesDetected() {
        Set<String> detectedEntityLabels = nodeLabelsCaseInsensitive(NodeKind.ENTITY);

        JsonNode entities = groundTruth.get("entities");
        List<String> missing = new ArrayList<>();

        entities.fieldNames().forEachRemaining(entityName -> {
            String tableName = entities.get(entityName).get("table").asText();
            // Check if entity is detected by name (case-insensitive match in label)
            boolean found = detectedEntityLabels.stream()
                    .anyMatch(label -> label.contains(entityName.toLowerCase()));
            if (!found) {
                missing.add(entityName);
            }
        });

        assertTrue(missing.isEmpty(),
                "Expected entities not found: %s. Detected entity labels: %s"
                        .formatted(missing, nodeLabels(NodeKind.ENTITY)));
    }

    @Test
    void entitiesHaveTableNames() {
        JsonNode entities = groundTruth.get("entities");
        List<CodeNode> entityNodes = nodesByKind(NodeKind.ENTITY);

        List<String> entitiesMissingTable = new ArrayList<>();
        entities.fieldNames().forEachRemaining(entityName -> {
            String expectedTable = entities.get(entityName).get("table").asText();

            // Find the matching entity node
            CodeNode match = entityNodes.stream()
                    .filter(n -> n.getLabel() != null
                            && n.getLabel().toLowerCase().contains(entityName.toLowerCase()))
                    .findFirst()
                    .orElse(null);

            if (match != null) {
                String label = match.getLabel().toLowerCase();
                Map<String, Object> props = match.getProperties();
                String tableInProps = props.get("table_name") != null
                        ? props.get("table_name").toString().toLowerCase() : "";

                // Table name should be in the label or in properties
                boolean hasTable = label.contains(expectedTable.toLowerCase())
                        || tableInProps.contains(expectedTable.toLowerCase());
                if (!hasTable) {
                    entitiesMissingTable.add("%s (expected table '%s', got label='%s', table_name='%s')"
                            .formatted(entityName, expectedTable, match.getLabel(), tableInProps));
                }
            }
        });

        assertTrue(entitiesMissingTable.isEmpty(),
                "Entities missing table name in label or properties: %s"
                        .formatted(entitiesMissingTable));
    }

    // ── Endpoint detection ────────────────────────────────────────────────

    @Test
    void allExpectedEndpointsDetected() {
        List<CodeNode> endpointNodes = nodesByKind(NodeKind.ENDPOINT);

        // Build a set of "METHOD path" strings from detected endpoints
        Set<String> detectedEndpoints = new TreeSet<>();
        for (CodeNode ep : endpointNodes) {
            Map<String, Object> props = ep.getProperties();
            String method = props.get("http_method") != null
                    ? props.get("http_method").toString().toUpperCase() : "UNKNOWN";
            String path = props.get("path") != null
                    ? props.get("path").toString() : "";
            // Also try the label which often contains "METHOD path"
            detectedEndpoints.add(method + " " + path);
            if (ep.getLabel() != null) {
                detectedEndpoints.add(ep.getLabel().toUpperCase());
            }
        }

        JsonNode expectedEndpoints = groundTruth.get("endpoints");
        List<String> missing = new ArrayList<>();

        for (JsonNode ep : expectedEndpoints) {
            String method = ep.get("method").asText().toUpperCase();
            String path = ep.get("path").asText();
            String key = method + " " + path;

            boolean found = detectedEndpoints.stream()
                    .anyMatch(d -> d.contains(method) && d.contains(path));

            if (!found) {
                missing.add(key);
            }
        }

        assertTrue(missing.isEmpty(),
                "Expected endpoints not found: %s. Detected endpoints: %s"
                        .formatted(missing, detectedEndpoints));
    }

    @Test
    void minEndpointCountMet() {
        int expected = groundTruth.at("/expected_stats/min_endpoints").asInt();
        long actual = nodesByKind(NodeKind.ENDPOINT).size();

        assertTrue(actual >= expected,
                "Expected at least %d endpoints but got %d. Endpoint labels: %s"
                        .formatted(expected, actual, nodeLabels(NodeKind.ENDPOINT)));
    }

    @Test
    void endpointMethodBreakdownIsReasonable() {
        List<CodeNode> endpoints = nodesByKind(NodeKind.ENDPOINT);

        Map<String, Long> methodCounts = endpoints.stream()
                .collect(Collectors.groupingBy(
                        n -> {
                            Object m = n.getProperties().get("http_method");
                            return m != null ? m.toString().toUpperCase() : "UNKNOWN";
                        },
                        Collectors.counting()
                ));

        System.out.printf("  Endpoint method breakdown: %s%n", methodCounts);

        // Petclinic has both GET and POST endpoints
        assertTrue(methodCounts.containsKey("GET"),
                "Expected GET endpoints. Method breakdown: " + methodCounts);
        assertTrue(methodCounts.containsKey("POST"),
                "Expected POST endpoints. Method breakdown: " + methodCounts);

        // UNKNOWN/ALL methods should be rare (< 30% of total)
        long unknownCount = methodCounts.getOrDefault("UNKNOWN", 0L) + methodCounts.getOrDefault("ALL", 0L);
        assertTrue(unknownCount < endpoints.size() * 0.3,
                "Too many endpoints with UNKNOWN/ALL HTTP method (%d/%d). Method breakdown: %s"
                        .formatted(unknownCount, endpoints.size(), methodCounts));
    }

    // ── Controller detection ──────────────────────────────────────────────

    @Test
    void allExpectedControllersDetected() {
        // Controllers are typically CLASS nodes with controller-related annotations
        Set<String> detectedClassLabels = nodeLabelsCaseInsensitive(NodeKind.CLASS);

        JsonNode expectedControllers = groundTruth.get("controllers");
        List<String> missing = new ArrayList<>();

        for (JsonNode controller : expectedControllers) {
            String name = controller.asText().toLowerCase();
            boolean found = detectedClassLabels.stream()
                    .anyMatch(label -> label.contains(name));
            if (!found) {
                missing.add(controller.asText());
            }
        }

        assertTrue(missing.isEmpty(),
                "Expected controllers not found: %s. Detected class labels: %s"
                        .formatted(missing, nodeLabels(NodeKind.CLASS)));
    }

    // ── Repository detection ──────────────────────────────────────────────

    @Test
    void expectedRepositoriesDetected() {
        Set<String> detectedRepoLabels = nodeLabelsCaseInsensitive(NodeKind.REPOSITORY);

        JsonNode expectedRepos = groundTruth.get("repositories");
        List<String> missing = new ArrayList<>();

        for (JsonNode repo : expectedRepos) {
            String name = repo.asText().toLowerCase();
            boolean found = detectedRepoLabels.stream()
                    .anyMatch(label -> label.contains(name));
            if (!found) {
                missing.add(repo.asText());
            }
        }

        assertTrue(missing.isEmpty(),
                "Expected repositories not found: %s. Detected repository labels: %s"
                        .formatted(missing, nodeLabels(NodeKind.REPOSITORY)));
    }

    // ── False positive framework detection ────────────────────────────────

    @Test
    void noFalseFrameworkDetection() {
        JsonNode falseFrameworks = groundTruth.at("/expected_stats/no_false_frameworks");
        Map<String, Integer> frameworkBreakdown = analysisResult.frameworkBreakdown();

        List<String> falsePositives = new ArrayList<>();
        for (JsonNode fw : falseFrameworks) {
            String framework = fw.asText().toLowerCase();
            // Check both framework breakdown and node properties
            boolean inBreakdown = frameworkBreakdown.keySet().stream()
                    .anyMatch(k -> k.toLowerCase().contains(framework));

            if (inBreakdown) {
                falsePositives.add(fw.asText());
            }
        }

        // Also check node properties for framework false positives
        for (JsonNode fw : falseFrameworks) {
            String framework = fw.asText().toLowerCase();
            long nodesWithFramework = allNodes.stream()
                    .filter(n -> {
                        Object fwProp = n.getProperties().get("framework");
                        return fwProp != null && fwProp.toString().toLowerCase().contains(framework);
                    })
                    .count();

            if (nodesWithFramework > 0 && !falsePositives.contains(fw.asText())) {
                falsePositives.add(fw.asText() + " (%d nodes)".formatted(nodesWithFramework));
            }
        }

        assertTrue(falsePositives.isEmpty(),
                "False framework detections found: %s. Framework breakdown: %s"
                        .formatted(falsePositives, frameworkBreakdown));
    }

    // ── Architecture stats ────────────────────────────────────────────────

    @Test
    void minClassCountMet() {
        int expected = groundTruth.at("/expected_stats/min_classes").asInt();
        long actual = nodesByKind(NodeKind.CLASS).size();

        assertTrue(actual >= expected,
                "Expected at least %d classes but got %d".formatted(expected, actual));
    }

    @Test
    void minMethodCountMet() {
        int expected = groundTruth.at("/expected_stats/min_methods").asInt();
        long actual = nodesByKind(NodeKind.METHOD).size();

        assertTrue(actual >= expected,
                "Expected at least %d methods but got %d".formatted(expected, actual));
    }

    @Test
    void modulesDetected() {
        long moduleCount = nodesByKind(NodeKind.MODULE).size();
        assertTrue(moduleCount > 0,
                "Expected at least one MODULE node. Node breakdown: " + analysisResult.nodeBreakdown());
    }

    // ── Layer classification ──────────────────────────────────────────────

    @Test
    void entitiesAreBackendLayer() {
        List<CodeNode> entities = nodesByKind(NodeKind.ENTITY);

        List<String> nonBackend = entities.stream()
                .filter(n -> !"backend".equals(n.getLayer()))
                .map(n -> "%s (layer=%s)".formatted(n.getLabel(), n.getLayer()))
                .toList();

        assertTrue(nonBackend.isEmpty(),
                "All entities should be backend layer but found: " + nonBackend);
    }

    @Test
    void endpointsAreBackendLayer() {
        List<CodeNode> endpoints = nodesByKind(NodeKind.ENDPOINT);

        long backendCount = endpoints.stream()
                .filter(n -> "backend".equals(n.getLayer()))
                .count();

        // At least 80% of endpoints should be backend in petclinic
        double ratio = endpoints.isEmpty() ? 0 : (double) backendCount / endpoints.size();
        assertTrue(ratio >= 0.8,
                "Expected at least 80%% of endpoints to be backend layer but ratio is %.1f%% (%d/%d)"
                        .formatted(ratio * 100, backendCount, endpoints.size()));
    }

    // ── Edge quality ──────────────────────────────────────────────────────

    @Test
    void hasDependencyEdges() {
        long depEdges = allEdges.stream()
                .filter(e -> Set.of("imports", "depends_on", "calls", "extends", "implements")
                        .contains(e.getKind().getValue()))
                .count();

        assertTrue(depEdges > 0,
                "Expected dependency edges (imports/depends_on/calls/extends/implements) in a Java project. Edge breakdown: "
                        + analysisResult.edgeBreakdown());
    }

    @Test
    void hasContainsEdges() {
        long containsEdges = allEdges.stream()
                .filter(e -> e.getKind().getValue().equals("contains"))
                .count();

        assertTrue(containsEdges > 0,
                "Expected CONTAINS edges (module containment). Edge breakdown: "
                        + analysisResult.edgeBreakdown());
    }

    // ── Determinism ───────────────────────────────────────────────────────

    @Test
    void analysisIsDeterministic() {
        Path repoPath = Path.of(System.getenv("E2E_PETCLINIC_DIR"));
        Analyzer analyzer2 = buildAnalyzer();
        AnalysisResult run2 = analyzer2.run(repoPath, null);

        assertEquals(analysisResult.nodeCount(), run2.nodeCount(),
                "Node count must be deterministic across runs");
        assertEquals(analysisResult.edgeCount(), run2.edgeCount(),
                "Edge count must be deterministic across runs");
        assertEquals(analysisResult.totalFiles(), run2.totalFiles(),
                "File count must be deterministic across runs");
        assertEquals(analysisResult.nodeBreakdown(), run2.nodeBreakdown(),
                "Node breakdown must be deterministic across runs");
        assertEquals(analysisResult.edgeBreakdown(), run2.edgeBreakdown(),
                "Edge breakdown must be deterministic across runs");
    }

    // ── Framework detection ───────────────────────────────────────────────

    @Test
    void springBootFrameworkDetected() {
        String expected = groundTruth.get("framework").asText();
        Map<String, Integer> frameworkBreakdown = analysisResult.frameworkBreakdown();

        boolean found = frameworkBreakdown.keySet().stream()
                .anyMatch(k -> k.toLowerCase().contains(expected.toLowerCase())
                        || k.toLowerCase().contains("spring"));

        assertTrue(found,
                "Expected framework '%s' not found in framework breakdown: %s"
                        .formatted(expected, frameworkBreakdown));
    }
}
