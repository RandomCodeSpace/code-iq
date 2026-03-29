package io.github.randomcodespace.iq.analyzer;

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
import io.github.randomcodespace.iq.detector.config.BatchStructureDetector;
import io.github.randomcodespace.iq.detector.config.CloudFormationDetector;
import io.github.randomcodespace.iq.detector.config.DockerComposeDetector;
import io.github.randomcodespace.iq.detector.config.GitHubActionsDetector;
import io.github.randomcodespace.iq.detector.config.GitLabCiDetector;
import io.github.randomcodespace.iq.detector.config.HelmChartDetector;
import io.github.randomcodespace.iq.detector.config.IniStructureDetector;
import io.github.randomcodespace.iq.detector.config.JsonStructureDetector;
import io.github.randomcodespace.iq.detector.config.KubernetesDetector;
import io.github.randomcodespace.iq.detector.config.KubernetesRbacDetector;
import io.github.randomcodespace.iq.detector.config.OpenApiDetector;
import io.github.randomcodespace.iq.detector.config.PackageJsonDetector;
import io.github.randomcodespace.iq.detector.config.PropertiesDetector;
import io.github.randomcodespace.iq.detector.config.PyprojectTomlDetector;
import io.github.randomcodespace.iq.detector.config.SqlStructureDetector;
import io.github.randomcodespace.iq.detector.config.TomlStructureDetector;
import io.github.randomcodespace.iq.detector.config.TsconfigJsonDetector;
import io.github.randomcodespace.iq.detector.config.YamlStructureDetector;
import io.github.randomcodespace.iq.detector.cpp.CppStructuresDetector;
import io.github.randomcodespace.iq.detector.csharp.CSharpEfcoreDetector;
import io.github.randomcodespace.iq.detector.csharp.CSharpMinimalApisDetector;
import io.github.randomcodespace.iq.detector.csharp.CSharpStructuresDetector;
import io.github.randomcodespace.iq.detector.docs.MarkdownStructureDetector;
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
import io.github.randomcodespace.iq.detector.java.AzureFunctionsDetector;
import io.github.randomcodespace.iq.detector.java.AzureMessagingDetector;
import io.github.randomcodespace.iq.detector.java.ClassHierarchyDetector;
import io.github.randomcodespace.iq.detector.java.ConfigDefDetector;
import io.github.randomcodespace.iq.detector.java.CosmosDbDetector;
import io.github.randomcodespace.iq.detector.java.GraphqlResolverDetector;
import io.github.randomcodespace.iq.detector.java.GrpcServiceDetector;
import io.github.randomcodespace.iq.detector.java.IbmMqDetector;
import io.github.randomcodespace.iq.detector.java.JaxrsDetector;
import io.github.randomcodespace.iq.detector.java.JdbcDetector;
import io.github.randomcodespace.iq.detector.java.JmsDetector;
import io.github.randomcodespace.iq.detector.java.JpaEntityDetector;
import io.github.randomcodespace.iq.detector.java.KafkaDetector;
import io.github.randomcodespace.iq.detector.java.KafkaProtocolDetector;
import io.github.randomcodespace.iq.detector.java.MicronautDetector;
import io.github.randomcodespace.iq.detector.java.ModuleDepsDetector;
import io.github.randomcodespace.iq.detector.java.PublicApiDetector;
import io.github.randomcodespace.iq.detector.java.QuarkusDetector;
import io.github.randomcodespace.iq.detector.java.RabbitmqDetector;
import io.github.randomcodespace.iq.detector.java.RawSqlDetector;
import io.github.randomcodespace.iq.detector.java.RepositoryDetector;
import io.github.randomcodespace.iq.detector.java.RmiDetector;
import io.github.randomcodespace.iq.detector.java.SpringEventsDetector;
import io.github.randomcodespace.iq.detector.java.SpringRestDetector;
import io.github.randomcodespace.iq.detector.java.SpringSecurityDetector;
import io.github.randomcodespace.iq.detector.java.TibcoEmsDetector;
import io.github.randomcodespace.iq.detector.java.WebSocketDetector;
import io.github.randomcodespace.iq.detector.kotlin.KotlinStructuresDetector;
import io.github.randomcodespace.iq.detector.kotlin.KtorRouteDetector;
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
import io.github.randomcodespace.iq.detector.rust.ActixWebDetector;
import io.github.randomcodespace.iq.detector.rust.RustStructuresDetector;
import io.github.randomcodespace.iq.detector.scala.ScalaStructuresDetector;
import io.github.randomcodespace.iq.detector.shell.BashDetector;
import io.github.randomcodespace.iq.detector.shell.PowerShellDetector;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full pipeline integration test that analyzes a real codebase.
 * <p>
 * Only runs when BENCHMARK_DIR env var is set.
 * <p>
 * Usage:
 * <pre>
 * BENCHMARK_DIR=$HOME/projects/testDir JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 \
 *   mvn test -Dtest="FullAnalysisIntegrationTest" -Dsurefire.excludes="" -pl .
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "BENCHMARK_DIR", matches = ".+")
class FullAnalysisIntegrationTest {

    /**
     * Build all detectors manually — no Spring context needed.
     */
    private static List<Detector> allDetectors() {
        return List.of(
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
        );
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

        System.out.printf("Registered %d detectors%n", registry.count());
        return new Analyzer(registry, parser, fileDiscovery, layerClassifier, linkers, config);
    }

    @Test
    void analyzeSpringBoot() {
        Path repoPath = Path.of(System.getenv("BENCHMARK_DIR"), "spring-boot");
        assertTrue(Files.isDirectory(repoPath),
                "spring-boot directory not found at " + repoPath);

        Analyzer analyzer = buildAnalyzer();

        // Run analysis with progress reporting
        AnalysisResult result = analyzer.run(repoPath, msg -> System.out.println("  >> " + msg));

        // ---- Print results ----
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║           FULL ANALYSIS INTEGRATION TEST RESULTS            ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Repository:      %-40s ║%n", repoPath.getFileName());
        System.out.printf("║  Files discovered: %-39d ║%n", result.totalFiles());
        System.out.printf("║  Files analyzed:   %-39d ║%n", result.filesAnalyzed());
        System.out.printf("║  Nodes:            %-39d ║%n", result.nodeCount());
        System.out.printf("║  Edges:            %-39d ║%n", result.edgeCount());
        System.out.printf("║  Time:             %-39s ║%n", formatDuration(result.elapsed()));
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║  LANGUAGE BREAKDOWN (top 20)                                ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        result.languageBreakdown().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(20)
                .forEach(e -> System.out.printf("║    %-20s %,8d files                       ║%n",
                        e.getKey(), e.getValue()));
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║  NODE TYPE BREAKDOWN                                        ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        result.nodeBreakdown().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> System.out.printf("║    %-28s %,8d nodes              ║%n",
                        e.getKey(), e.getValue()));
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║  PYTHON BASELINE COMPARISON                                 ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║    %-20s %12s %12s %8s   ║%n", "Metric", "Python", "Java", "Ratio");
        System.out.printf("║    %-20s %,12d %,12d %7.1f%%   ║%n",
                "Files discovered", 10_872, result.totalFiles(),
                result.totalFiles() * 100.0 / 10_872);
        System.out.printf("║    %-20s %,12d %,12d %7.1f%%   ║%n",
                "Nodes", 27_446, result.nodeCount(),
                result.nodeCount() * 100.0 / 27_446);
        System.out.printf("║    %-20s %,12d %,12d %7.1f%%   ║%n",
                "Edges", 42_025, result.edgeCount(),
                result.edgeCount() * 100.0 / 42_025);
        System.out.printf("║    %-20s %12s %12s %7.1fx   ║%n",
                "Time", "47s", formatDuration(result.elapsed()),
                47_000.0 / result.elapsed().toMillis());
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        // ---- Sanity assertions ----
        assertTrue(result.totalFiles() > 0, "Should discover files");
        assertTrue(result.nodeCount() > 0, "Should produce nodes");
        assertTrue(result.edgeCount() > 0, "Should produce edges");
        assertTrue(result.filesAnalyzed() > 0, "Should analyze at least some files");
        assertFalse(result.languageBreakdown().isEmpty(), "Should detect languages");
        assertFalse(result.nodeBreakdown().isEmpty(), "Should have node type breakdown");

        // spring-boot is primarily Java, so we expect java files
        assertTrue(result.languageBreakdown().containsKey("java"),
                "Should detect Java files in spring-boot");

        // Should have meaningful node types for a Java project
        var nodeTypes = result.nodeBreakdown().keySet();
        System.out.println("\nNode types found: " + nodeTypes.stream().sorted().toList());
    }

    @Test
    void analyzeSpringBootDeterminism() {
        Path repoPath = Path.of(System.getenv("BENCHMARK_DIR"), "spring-boot");
        if (!Files.isDirectory(repoPath)) return;

        Analyzer analyzer = buildAnalyzer();

        // Run twice and compare
        AnalysisResult run1 = analyzer.run(repoPath, null);
        AnalysisResult run2 = analyzer.run(repoPath, null);

        System.out.println();
        System.out.println("=== DETERMINISM CHECK ===");
        System.out.printf("Run 1: %d files, %d nodes, %d edges%n",
                run1.totalFiles(), run1.nodeCount(), run1.edgeCount());
        System.out.printf("Run 2: %d files, %d nodes, %d edges%n",
                run2.totalFiles(), run2.nodeCount(), run2.edgeCount());

        assertEquals(run1.totalFiles(), run2.totalFiles(), "File count must be deterministic");
        assertEquals(run1.nodeCount(), run2.nodeCount(), "Node count must be deterministic");
        assertEquals(run1.edgeCount(), run2.edgeCount(), "Edge count must be deterministic");
        assertEquals(run1.languageBreakdown(), run2.languageBreakdown(),
                "Language breakdown must be deterministic");
        assertEquals(run1.nodeBreakdown(), run2.nodeBreakdown(),
                "Node breakdown must be deterministic");

        System.out.println("DETERMINISM: PASS");
    }

    private static String formatDuration(java.time.Duration d) {
        long totalMs = d.toMillis();
        if (totalMs < 1000) return totalMs + "ms";
        return String.format("%.1fs", totalMs / 1000.0);
    }
}
