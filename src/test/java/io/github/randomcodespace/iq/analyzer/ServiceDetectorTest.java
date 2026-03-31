package io.github.randomcodespace.iq.analyzer;

import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServiceDetectorTest {

    private ServiceDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ServiceDetector();
    }

    // --- Existing build tool tests ---

    @Test
    void detectsMavenModule() {
        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cfg:pom.xml", NodeKind.CONFIG_FILE, "pom.xml", "services/order/pom.xml"));
        nodes.add(makeNode("ep:OrderCtrl", NodeKind.ENDPOINT, "GET /orders", "services/order/src/OrderCtrl.java"));
        nodes.add(makeNode("ent:Order", NodeKind.ENTITY, "Order", "services/order/src/Order.java"));

        var result = detector.detect(nodes, List.of(), "my-project");

        assertEquals(1, result.serviceNodes().size());
        CodeNode svc = result.serviceNodes().getFirst();
        assertEquals(NodeKind.SERVICE, svc.getKind());
        assertEquals("order", svc.getLabel());
        assertEquals("maven", svc.getProperties().get("build_tool"));
        assertEquals(1, svc.getProperties().get("endpoint_count"));
        assertEquals(1, svc.getProperties().get("entity_count"));
    }

    @Test
    void detectsNpmModule() {
        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cfg:pkg", NodeKind.CONFIG_FILE, "package.json", "frontend/package.json"));
        nodes.add(makeNode("comp:App", NodeKind.COMPONENT, "App", "frontend/src/App.tsx"));

        var result = detector.detect(nodes, List.of(), "my-project");

        assertEquals(1, result.serviceNodes().size());
        assertEquals("frontend", result.serviceNodes().getFirst().getLabel());
        assertEquals("npm", result.serviceNodes().getFirst().getProperties().get("build_tool"));
    }

    @Test
    void detectsGoModule() {
        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cfg:gomod", NodeKind.CONFIG_FILE, "go.mod", "services/auth/go.mod"));

        var result = detector.detect(nodes, List.of(), "my-project");

        assertEquals(1, result.serviceNodes().size());
        assertEquals("auth", result.serviceNodes().getFirst().getLabel());
        assertEquals("go", result.serviceNodes().getFirst().getProperties().get("build_tool"));
    }

    @Test
    void detectsGradleModule() {
        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cfg:gradle", NodeKind.CONFIG_FILE, "build.gradle", "api/build.gradle"));

        var result = detector.detect(nodes, List.of(), "my-project");

        assertEquals(1, result.serviceNodes().size());
        assertEquals("api", result.serviceNodes().getFirst().getLabel());
        assertEquals("gradle", result.serviceNodes().getFirst().getProperties().get("build_tool"));
    }

    @Test
    void detectsCargoModule() {
        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cfg:cargo", NodeKind.CONFIG_FILE, "Cargo.toml", "crates/worker/Cargo.toml"));

        var result = detector.detect(nodes, List.of(), "my-project");

        assertEquals(1, result.serviceNodes().size());
        assertEquals("worker", result.serviceNodes().getFirst().getLabel());
        assertEquals("cargo", result.serviceNodes().getFirst().getProperties().get("build_tool"));
    }

    @Test
    void detectsDotnetProject() {
        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cfg:csproj", NodeKind.CONFIG_FILE, "Api.csproj", "src/Api/Api.csproj"));

        var result = detector.detect(nodes, List.of(), "my-project");

        assertEquals(1, result.serviceNodes().size());
        assertEquals("Api", result.serviceNodes().getFirst().getLabel());
        assertEquals("dotnet", result.serviceNodes().getFirst().getProperties().get("build_tool"));
    }

    @Test
    void detectsMultipleModules() {
        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cfg:pom1", NodeKind.CONFIG_FILE, "pom.xml", "services/order/pom.xml"));
        nodes.add(makeNode("cfg:pom2", NodeKind.CONFIG_FILE, "pom.xml", "services/auth/pom.xml"));
        nodes.add(makeNode("cfg:pkg", NodeKind.CONFIG_FILE, "package.json", "frontend/package.json"));
        nodes.add(makeNode("ep:ep1", NodeKind.ENDPOINT, "GET /orders", "services/order/src/Ctrl.java"));
        nodes.add(makeNode("ep:ep2", NodeKind.ENDPOINT, "POST /login", "services/auth/src/Auth.java"));

        var result = detector.detect(nodes, List.of(), "my-project");

        assertEquals(3, result.serviceNodes().size());
        var names = result.serviceNodes().stream().map(CodeNode::getLabel).sorted().toList();
        assertEquals(List.of("auth", "frontend", "order"), names);
    }

    @Test
    void fallsBackToProjectRootWhenNoModulesDetected() {
        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cls:Foo", NodeKind.CLASS, "Foo", "src/Foo.java"));
        nodes.add(makeNode("cls:Bar", NodeKind.CLASS, "Bar", "src/Bar.java"));

        var result = detector.detect(nodes, List.of(), "my-project");

        assertEquals(1, result.serviceNodes().size());
        assertEquals("my-project", result.serviceNodes().getFirst().getLabel());
        assertEquals("unknown", result.serviceNodes().getFirst().getProperties().get("build_tool"));
    }

    @Test
    void setsServicePropertyOnChildNodes() {
        List<CodeNode> nodes = new ArrayList<>();
        CodeNode pomNode = makeNode("cfg:pom", NodeKind.CONFIG_FILE, "pom.xml", "svc/pom.xml");
        CodeNode epNode = makeNode("ep:ep1", NodeKind.ENDPOINT, "GET /test", "svc/src/Test.java");
        nodes.add(pomNode);
        nodes.add(epNode);

        detector.detect(nodes, List.of(), "project");

        assertEquals("svc", epNode.getProperties().get("service"));
    }

    @Test
    void createsContainsEdges() {
        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cfg:pom", NodeKind.CONFIG_FILE, "pom.xml", "svc/pom.xml"));
        nodes.add(makeNode("cls:A", NodeKind.CLASS, "A", "svc/src/A.java"));

        var result = detector.detect(nodes, List.of(), "project");

        assertFalse(result.serviceEdges().isEmpty());
        CodeEdge containsEdge = result.serviceEdges().stream()
                .filter(e -> e.getKind() == EdgeKind.CONTAINS)
                .findFirst()
                .orElse(null);
        assertNotNull(containsEdge);
        assertEquals("service:svc", containsEdge.getSourceId());
    }

    @Test
    void deterministic() {
        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cfg:pom1", NodeKind.CONFIG_FILE, "pom.xml", "b-svc/pom.xml"));
        nodes.add(makeNode("cfg:pom2", NodeKind.CONFIG_FILE, "pom.xml", "a-svc/pom.xml"));
        nodes.add(makeNode("ep:ep1", NodeKind.ENDPOINT, "GET /b", "b-svc/src/B.java"));
        nodes.add(makeNode("ep:ep2", NodeKind.ENDPOINT, "GET /a", "a-svc/src/A.java"));

        var result1 = detector.detect(new ArrayList<>(nodes), List.of(), "project");
        var result2 = detector.detect(new ArrayList<>(nodes), List.of(), "project");

        assertEquals(result1.serviceNodes().size(), result2.serviceNodes().size());
        for (int i = 0; i < result1.serviceNodes().size(); i++) {
            assertEquals(result1.serviceNodes().get(i).getId(), result2.serviceNodes().get(i).getId());
            assertEquals(result1.serviceNodes().get(i).getLabel(), result2.serviceNodes().get(i).getLabel());
        }
    }

    // --- New: Python detection ---

    @Test
    void detectsPythonRequirementsTxt() {
        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cfg:req", NodeKind.CONFIG_FILE, "requirements.txt",
                "services/ml-api/requirements.txt"));
        nodes.add(makeNode("ep:predict", NodeKind.ENDPOINT, "POST /predict",
                "services/ml-api/app.py"));

        var result = detector.detect(nodes, List.of(), "my-project");

        assertEquals(1, result.serviceNodes().size());
        CodeNode svc = result.serviceNodes().getFirst();
        assertEquals("ml-api", svc.getLabel());
        assertEquals("python", svc.getProperties().get("build_tool"));
        assertEquals(1, svc.getProperties().get("endpoint_count"));
    }

    @Test
    void detectsPythonSetupPy() {
        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cfg:setup", NodeKind.CONFIG_FILE, "setup.py",
                "libs/utils/setup.py"));
        nodes.add(makeNode("cls:Helper", NodeKind.CLASS, "Helper",
                "libs/utils/src/helper.py"));

        var result = detector.detect(nodes, List.of(), "my-project");

        assertEquals(1, result.serviceNodes().size());
        assertEquals("utils", result.serviceNodes().getFirst().getLabel());
        assertEquals("python", result.serviceNodes().getFirst().getProperties().get("build_tool"));
    }

    @Test
    void detectsPyprojectToml() {
        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cfg:pyproject", NodeKind.CONFIG_FILE, "pyproject.toml",
                "services/data-pipeline/pyproject.toml"));

        var result = detector.detect(nodes, List.of(), "my-project");

        assertEquals(1, result.serviceNodes().size());
        assertEquals("data-pipeline", result.serviceNodes().getFirst().getLabel());
        assertEquals("python", result.serviceNodes().getFirst().getProperties().get("build_tool"));
    }

    @Test
    void detectsDjangoManagePy() {
        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cfg:manage", NodeKind.CONFIG_FILE, "manage.py",
                "backend/manage.py"));
        nodes.add(makeNode("ep:users", NodeKind.ENDPOINT, "GET /users",
                "backend/users/views.py"));

        var result = detector.detect(nodes, List.of(), "my-project");

        assertEquals(1, result.serviceNodes().size());
        assertEquals("backend", result.serviceNodes().getFirst().getLabel());
        assertEquals("django", result.serviceNodes().getFirst().getProperties().get("build_tool"));
    }

    @Test
    void pyprojectTakesPriorityOverRequirementsTxt() {
        List<CodeNode> nodes = new ArrayList<>();
        // Both pyproject.toml and requirements.txt in same dir
        nodes.add(makeNode("cfg:pyproject", NodeKind.CONFIG_FILE, "pyproject.toml",
                "svc/pyproject.toml"));
        nodes.add(makeNode("cfg:req", NodeKind.CONFIG_FILE, "requirements.txt",
                "svc/requirements.txt"));

        var result = detector.detect(nodes, List.of(), "project");

        assertEquals(1, result.serviceNodes().size());
        assertEquals("python", result.serviceNodes().getFirst().getProperties().get("build_tool"));
        assertEquals("pyproject.toml", result.serviceNodes().getFirst().getProperties().get("detected_from"));
    }

    @Test
    void pythonDoesNotOverrideMavenInSameDir() {
        List<CodeNode> nodes = new ArrayList<>();
        // Maven and requirements.txt in same dir -- Maven should win
        nodes.add(makeNode("cfg:pom", NodeKind.CONFIG_FILE, "pom.xml", "svc/pom.xml"));
        nodes.add(makeNode("cfg:req", NodeKind.CONFIG_FILE, "requirements.txt",
                "svc/requirements.txt"));

        var result = detector.detect(nodes, List.of(), "project");

        assertEquals(1, result.serviceNodes().size());
        assertEquals("maven", result.serviceNodes().getFirst().getProperties().get("build_tool"));
    }

    // --- New: Dockerfile detection ---

    @Test
    void detectsDockerfileAsService() {
        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cfg:docker", NodeKind.CONFIG_FILE, "Dockerfile",
                "services/worker/Dockerfile"));
        nodes.add(makeNode("cls:Worker", NodeKind.CLASS, "Worker",
                "services/worker/src/worker.py"));

        var result = detector.detect(nodes, List.of(), "my-project");

        assertEquals(1, result.serviceNodes().size());
        assertEquals("worker", result.serviceNodes().getFirst().getLabel());
        assertEquals("docker", result.serviceNodes().getFirst().getProperties().get("build_tool"));
    }

    @Test
    void dockerfileDoesNotOverrideOtherBuildTool() {
        List<CodeNode> nodes = new ArrayList<>();
        // Dockerfile + package.json in same dir -- package.json should win
        nodes.add(makeNode("cfg:docker", NodeKind.CONFIG_FILE, "Dockerfile",
                "frontend/Dockerfile"));
        nodes.add(makeNode("cfg:pkg", NodeKind.CONFIG_FILE, "package.json",
                "frontend/package.json"));

        var result = detector.detect(nodes, List.of(), "my-project");

        assertEquals(1, result.serviceNodes().size());
        assertEquals("npm", result.serviceNodes().getFirst().getProperties().get("build_tool"));
    }

    // --- New: Build file content name extraction ---

    @Test
    void extractsNameFromPomXml(@TempDir Path tempDir) throws IOException {
        Path svcDir = Files.createDirectories(tempDir.resolve("services/order"));
        Files.writeString(svcDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <parent>
                        <artifactId>parent-project</artifactId>
                    </parent>
                    <artifactId>order-service</artifactId>
                    <version>1.0.0</version>
                </project>
                """, StandardCharsets.UTF_8);

        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cfg:pom", NodeKind.CONFIG_FILE, "pom.xml",
                "services/order/pom.xml"));

        var result = detector.detect(nodes, List.of(), "project", tempDir);

        assertEquals(1, result.serviceNodes().size());
        assertEquals("order-service", result.serviceNodes().getFirst().getLabel());
    }

    @Test
    void extractsNameFromPackageJson(@TempDir Path tempDir) throws IOException {
        Path frontendDir = Files.createDirectories(tempDir.resolve("frontend"));
        Files.writeString(frontendDir.resolve("package.json"), """
                {
                  "name": "@myorg/dashboard-ui",
                  "version": "2.0.0",
                  "dependencies": {}
                }
                """, StandardCharsets.UTF_8);

        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cfg:pkg", NodeKind.CONFIG_FILE, "package.json",
                "frontend/package.json"));

        var result = detector.detect(nodes, List.of(), "project", tempDir);

        assertEquals(1, result.serviceNodes().size());
        // Should strip npm scope
        assertEquals("dashboard-ui", result.serviceNodes().getFirst().getLabel());
    }

    @Test
    void extractsNameFromGoMod(@TempDir Path tempDir) throws IOException {
        Path svcDir = Files.createDirectories(tempDir.resolve("services/auth"));
        Files.writeString(svcDir.resolve("go.mod"), """
                module github.com/myorg/auth-service

                go 1.22

                require (
                    github.com/gin-gonic/gin v1.9.1
                )
                """, StandardCharsets.UTF_8);

        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cfg:gomod", NodeKind.CONFIG_FILE, "go.mod",
                "services/auth/go.mod"));

        var result = detector.detect(nodes, List.of(), "project", tempDir);

        assertEquals(1, result.serviceNodes().size());
        assertEquals("auth-service", result.serviceNodes().getFirst().getLabel());
    }

    @Test
    void extractsNameFromCargoToml(@TempDir Path tempDir) throws IOException {
        Path crateDir = Files.createDirectories(tempDir.resolve("crates/worker"));
        Files.writeString(crateDir.resolve("Cargo.toml"), """
                [package]
                name = "event-worker"
                version = "0.1.0"
                edition = "2021"
                """, StandardCharsets.UTF_8);

        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cfg:cargo", NodeKind.CONFIG_FILE, "Cargo.toml",
                "crates/worker/Cargo.toml"));

        var result = detector.detect(nodes, List.of(), "project", tempDir);

        assertEquals(1, result.serviceNodes().size());
        assertEquals("event-worker", result.serviceNodes().getFirst().getLabel());
    }

    @Test
    void extractsNameFromPyprojectToml(@TempDir Path tempDir) throws IOException {
        Path svcDir = Files.createDirectories(tempDir.resolve("services/ml"));
        Files.writeString(svcDir.resolve("pyproject.toml"), """
                [project]
                name = "ml-prediction-service"
                version = "1.0.0"

                [build-system]
                requires = ["setuptools"]
                """, StandardCharsets.UTF_8);

        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cfg:pyproject", NodeKind.CONFIG_FILE, "pyproject.toml",
                "services/ml/pyproject.toml"));

        var result = detector.detect(nodes, List.of(), "project", tempDir);

        assertEquals(1, result.serviceNodes().size());
        assertEquals("ml-prediction-service", result.serviceNodes().getFirst().getLabel());
    }

    @Test
    void extractsNameFromSetupPy(@TempDir Path tempDir) throws IOException {
        Path libDir = Files.createDirectories(tempDir.resolve("libs/utils"));
        Files.writeString(libDir.resolve("setup.py"), """
                from setuptools import setup

                setup(
                    name='data-utils',
                    version='0.2.0',
                    packages=['data_utils'],
                )
                """, StandardCharsets.UTF_8);

        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cfg:setup", NodeKind.CONFIG_FILE, "setup.py",
                "libs/utils/setup.py"));

        var result = detector.detect(nodes, List.of(), "project", tempDir);

        assertEquals(1, result.serviceNodes().size());
        assertEquals("data-utils", result.serviceNodes().getFirst().getLabel());
    }

    @Test
    void fallsBackToDirNameWhenBuildFileHasNoName(@TempDir Path tempDir) throws IOException {
        // requirements.txt has no name inside -- should fall back to dir name
        Path svcDir = Files.createDirectories(tempDir.resolve("services/api"));
        Files.writeString(svcDir.resolve("requirements.txt"), """
                flask==3.0.0
                gunicorn==22.0.0
                """, StandardCharsets.UTF_8);

        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cfg:req", NodeKind.CONFIG_FILE, "requirements.txt",
                "services/api/requirements.txt"));

        var result = detector.detect(nodes, List.of(), "project", tempDir);

        assertEquals(1, result.serviceNodes().size());
        assertEquals("api", result.serviceNodes().getFirst().getLabel());
    }

    @Test
    void multiServiceWithContentExtraction(@TempDir Path tempDir) throws IOException {
        // Maven service
        Path orderDir = Files.createDirectories(tempDir.resolve("services/order"));
        Files.writeString(orderDir.resolve("pom.xml"), """
                <project>
                    <artifactId>order-api</artifactId>
                </project>
                """, StandardCharsets.UTF_8);

        // Node.js frontend
        Path frontDir = Files.createDirectories(tempDir.resolve("frontend"));
        Files.writeString(frontDir.resolve("package.json"), """
                {"name": "admin-dashboard", "version": "1.0.0"}
                """, StandardCharsets.UTF_8);

        // Python worker
        Path workerDir = Files.createDirectories(tempDir.resolve("workers/etl"));
        Files.writeString(workerDir.resolve("pyproject.toml"), """
                [project]
                name = "etl-pipeline"
                """, StandardCharsets.UTF_8);

        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cfg:pom", NodeKind.CONFIG_FILE, "pom.xml", "services/order/pom.xml"));
        nodes.add(makeNode("cfg:pkg", NodeKind.CONFIG_FILE, "package.json", "frontend/package.json"));
        nodes.add(makeNode("cfg:py", NodeKind.CONFIG_FILE, "pyproject.toml", "workers/etl/pyproject.toml"));
        nodes.add(makeNode("ep:orders", NodeKind.ENDPOINT, "GET /orders", "services/order/src/Ctrl.java"));

        var result = detector.detect(nodes, List.of(), "project", tempDir);

        assertEquals(3, result.serviceNodes().size());
        var names = result.serviceNodes().stream().map(CodeNode::getLabel).sorted().toList();
        assertEquals(List.of("admin-dashboard", "etl-pipeline", "order-api"), names);
    }

    @Test
    void deterministicWithContentExtraction(@TempDir Path tempDir) throws IOException {
        Path aDir = Files.createDirectories(tempDir.resolve("b-svc"));
        Files.writeString(aDir.resolve("pom.xml"), "<project><artifactId>bravo</artifactId></project>",
                StandardCharsets.UTF_8);

        Path bDir = Files.createDirectories(tempDir.resolve("a-svc"));
        Files.writeString(bDir.resolve("pom.xml"), "<project><artifactId>alpha</artifactId></project>",
                StandardCharsets.UTF_8);

        List<CodeNode> nodes = new ArrayList<>();
        nodes.add(makeNode("cfg:pom1", NodeKind.CONFIG_FILE, "pom.xml", "b-svc/pom.xml"));
        nodes.add(makeNode("cfg:pom2", NodeKind.CONFIG_FILE, "pom.xml", "a-svc/pom.xml"));

        var result1 = detector.detect(new ArrayList<>(nodes), List.of(), "project", tempDir);
        var result2 = detector.detect(new ArrayList<>(nodes), List.of(), "project", tempDir);

        assertEquals(result1.serviceNodes().size(), result2.serviceNodes().size());
        for (int i = 0; i < result1.serviceNodes().size(); i++) {
            assertEquals(result1.serviceNodes().get(i).getId(), result2.serviceNodes().get(i).getId());
            assertEquals(result1.serviceNodes().get(i).getLabel(), result2.serviceNodes().get(i).getLabel());
        }
    }

    private static CodeNode makeNode(String id, NodeKind kind, String label, String filePath) {
        CodeNode node = new CodeNode(id, kind, label);
        node.setFilePath(filePath);
        return node;
    }
}
