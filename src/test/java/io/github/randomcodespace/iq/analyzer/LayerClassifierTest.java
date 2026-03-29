package io.github.randomcodespace.iq.analyzer;

import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LayerClassifierTest {

    private final LayerClassifier classifier = new LayerClassifier();

    // ---- Node kind rules ----

    @Test
    void componentIsFrontend() {
        var node = new CodeNode("c1", NodeKind.COMPONENT, "MyComponent");
        assertEquals("frontend", classifier.classifyOne(node));
    }

    @Test
    void hookIsFrontend() {
        var node = new CodeNode("h1", NodeKind.HOOK, "useAuth");
        assertEquals("frontend", classifier.classifyOne(node));
    }

    @Test
    void endpointIsBackend() {
        var node = new CodeNode("e1", NodeKind.ENDPOINT, "GET /api/users");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void repositoryIsBackend() {
        var node = new CodeNode("r1", NodeKind.REPOSITORY, "UserRepository");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void guardIsBackend() {
        var node = new CodeNode("g1", NodeKind.GUARD, "AuthGuard");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void middlewareIsBackend() {
        var node = new CodeNode("m1", NodeKind.MIDDLEWARE, "LogMiddleware");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void infraResourceIsInfra() {
        var node = new CodeNode("i1", NodeKind.INFRA_RESOURCE, "aws_s3_bucket");
        assertEquals("infra", classifier.classifyOne(node));
    }

    @Test
    void azureResourceIsInfra() {
        var node = new CodeNode("a1", NodeKind.AZURE_RESOURCE, "storage_account");
        assertEquals("infra", classifier.classifyOne(node));
    }

    @Test
    void configFileIsShared() {
        var node = new CodeNode("cf1", NodeKind.CONFIG_FILE, "application.yml");
        assertEquals("shared", classifier.classifyOne(node));
    }

    @Test
    void configKeyIsShared() {
        var node = new CodeNode("ck1", NodeKind.CONFIG_KEY, "server.port");
        assertEquals("shared", classifier.classifyOne(node));
    }

    // ---- Language rules ----

    @Test
    void terraformLanguageIsInfra() {
        var node = new CodeNode("t1", NodeKind.CLASS, "Main");
        node.setProperties(Map.of("language", "terraform"));
        assertEquals("infra", classifier.classifyOne(node));
    }

    @Test
    void dockerfileLanguageIsInfra() {
        var node = new CodeNode("d1", NodeKind.CLASS, "Dockerfile");
        node.setProperties(Map.of("language", "dockerfile"));
        assertEquals("infra", classifier.classifyOne(node));
    }

    // ---- File path rules ----

    @Test
    void tsxExtensionIsFrontend() {
        var node = new CodeNode("f1", NodeKind.CLASS, "App");
        node.setFilePath("src/App.tsx");
        assertEquals("frontend", classifier.classifyOne(node));
    }

    @Test
    void jsxExtensionIsFrontend() {
        var node = new CodeNode("f2", NodeKind.CLASS, "App");
        node.setFilePath("src/App.jsx");
        assertEquals("frontend", classifier.classifyOne(node));
    }

    @Test
    void componentsPathIsFrontend() {
        var node = new CodeNode("f3", NodeKind.CLASS, "Button");
        node.setFilePath("src/components/Button.ts");
        assertEquals("frontend", classifier.classifyOne(node));
    }

    @Test
    void pagesPathIsFrontend() {
        var node = new CodeNode("f4", NodeKind.CLASS, "Home");
        node.setFilePath("src/pages/Home.ts");
        assertEquals("frontend", classifier.classifyOne(node));
    }

    @Test
    void controllersPathIsBackend() {
        var node = new CodeNode("b1", NodeKind.CLASS, "UserController");
        node.setFilePath("src/controllers/UserController.java");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void servicesPathIsBackend() {
        var node = new CodeNode("b2", NodeKind.CLASS, "UserService");
        node.setFilePath("src/services/UserService.java");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void handlersPathIsBackend() {
        var node = new CodeNode("b3", NodeKind.CLASS, "EventHandler");
        node.setFilePath("server/handlers/EventHandler.java");
        assertEquals("backend", classifier.classifyOne(node));
    }

    // ---- Framework rules ----

    @Test
    void reactFrameworkIsFrontend() {
        var node = new CodeNode("fw1", NodeKind.CLASS, "App");
        node.setProperties(Map.of("framework", "react"));
        assertEquals("frontend", classifier.classifyOne(node));
    }

    @Test
    void springFrameworkIsBackend() {
        var node = new CodeNode("fw2", NodeKind.CLASS, "App");
        node.setProperties(Map.of("framework", "spring"));
        assertEquals("backend", classifier.classifyOne(node));
    }

    // ---- Fallback ----

    @Test
    void unknownNodeIsUnknown() {
        var node = new CodeNode("u1", NodeKind.CLASS, "Unknown");
        assertEquals("unknown", classifier.classifyOne(node));
    }

    // ---- Batch classify ----

    @Test
    void classifySetslayerOnAllNodes() {
        var frontend = new CodeNode("c1", NodeKind.COMPONENT, "Comp");
        var backend = new CodeNode("e1", NodeKind.ENDPOINT, "GET /");
        var unknown = new CodeNode("u1", NodeKind.CLASS, "Util");

        classifier.classify(List.of(frontend, backend, unknown));

        assertEquals("frontend", frontend.getLayer());
        assertEquals("backend", backend.getLayer());
        assertEquals("unknown", unknown.getLayer());
    }

    // ---- Priority: node kind beats file path ----

    @Test
    void nodeKindTakesPrecedenceOverFilePath() {
        // ENDPOINT is backend, even if file path suggests frontend
        var node = new CodeNode("e1", NodeKind.ENDPOINT, "GET /");
        node.setFilePath("src/components/api.tsx");
        assertEquals("backend", classifier.classifyOne(node));
    }
}
