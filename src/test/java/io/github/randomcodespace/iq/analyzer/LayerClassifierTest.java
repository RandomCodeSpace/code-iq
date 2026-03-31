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

    @Test
    void entityIsBackend() {
        var node = new CodeNode("en1", NodeKind.ENTITY, "Owner");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void migrationIsBackend() {
        var node = new CodeNode("mg1", NodeKind.MIGRATION, "V1__init");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void serviceIsBackend() {
        var node = new CodeNode("s1", NodeKind.SERVICE, "OwnerService");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void topicIsBackend() {
        var node = new CodeNode("tp1", NodeKind.TOPIC, "order-events");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void queueIsBackend() {
        var node = new CodeNode("q1", NodeKind.QUEUE, "task-queue");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void eventIsBackend() {
        var node = new CodeNode("ev1", NodeKind.EVENT, "OrderPlaced");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void messageQueueIsBackend() {
        var node = new CodeNode("mq1", NodeKind.MESSAGE_QUEUE, "rabbitmq");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void rmiInterfaceIsBackend() {
        var node = new CodeNode("rmi1", NodeKind.RMI_INTERFACE, "RemoteService");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void websocketEndpointIsBackend() {
        var node = new CodeNode("ws1", NodeKind.WEBSOCKET_ENDPOINT, "/ws/chat");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void protocolMessageIsShared() {
        var node = new CodeNode("pm1", NodeKind.PROTOCOL_MESSAGE, "UserRequest");
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

    // ---- Fallback: path heuristics ----

    @Test
    void unknownNodeWithNoPathIsUnknown() {
        var node = new CodeNode("u1", NodeKind.CLASS, "Unknown");
        assertEquals("unknown", classifier.classifyOne(node));
    }

    // -- Java package/path heuristics --

    @Test
    void javaControllerPathIsBackend() {
        var node = new CodeNode("jc1", NodeKind.CLASS, "OwnerController");
        node.setFilePath("src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void javaModelPathIsBackend() {
        var node = new CodeNode("jm1", NodeKind.CLASS, "Owner");
        node.setFilePath("src/main/java/org/springframework/samples/petclinic/model/Owner.java");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void javaRepositoryPathIsBackend() {
        var node = new CodeNode("jr1", NodeKind.CLASS, "OwnerRepository");
        node.setFilePath("src/main/java/org/springframework/samples/petclinic/repository/OwnerRepository.java");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void javaServicePathIsBackend() {
        var node = new CodeNode("js1", NodeKind.CLASS, "ClinicService");
        node.setFilePath("src/main/java/org/springframework/samples/petclinic/service/ClinicService.java");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void javaConfigPathIsShared() {
        var node = new CodeNode("jcf1", NodeKind.CLASS, "DataSourceConfig");
        node.setFilePath("src/main/java/org/springframework/samples/petclinic/config/DataSourceConfig.java");
        assertEquals("shared", classifier.classifyOne(node));
    }

    @Test
    void javaUtilPathIsShared() {
        var node = new CodeNode("ju1", NodeKind.CLASS, "DateUtils");
        node.setFilePath("src/main/java/org/springframework/samples/petclinic/util/DateUtils.java");
        assertEquals("shared", classifier.classifyOne(node));
    }

    @Test
    void javaDomainPathIsBackend() {
        var node = new CodeNode("jd1", NodeKind.CLASS, "Pet");
        node.setFilePath("src/main/java/org/springframework/samples/petclinic/domain/Pet.java");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void javaDtoPathIsBackend() {
        var node = new CodeNode("jdt1", NodeKind.CLASS, "OwnerDto");
        node.setFilePath("src/main/java/com/example/dto/OwnerDto.java");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void javaExceptionPathIsShared() {
        var node = new CodeNode("je1", NodeKind.CLASS, "NotFoundException");
        node.setFilePath("src/main/java/com/example/exception/NotFoundException.java");
        assertEquals("shared", classifier.classifyOne(node));
    }

    @Test
    void javaSrcMainFallbackIsBackend() {
        // A Java file under src/main/java but not matching any specific package pattern
        var node = new CodeNode("jf1", NodeKind.CLASS, "PetclinicApplication");
        node.setFilePath("src/main/java/org/springframework/samples/petclinic/PetclinicApplication.java");
        assertEquals("backend", classifier.classifyOne(node));
    }

    // -- Node ID with package info --

    @Test
    void nodeIdWithControllerPackageIsBackend() {
        var node = new CodeNode("java:src/main/java/com/example/controller/UserController.java:class:UserController",
                NodeKind.CLASS, "UserController");
        node.setFilePath("src/main/java/com/example/controller/UserController.java");
        assertEquals("backend", classifier.classifyOne(node));
    }

    // -- TypeScript/JS path heuristics --

    @Test
    void tsComponentsPathIsFrontend() {
        var node = new CodeNode("tc1", NodeKind.CLASS, "Button");
        node.setFilePath("src/components/Button.ts");
        // Already covered by existing FRONTEND_PATH_RE
        assertEquals("frontend", classifier.classifyOne(node));
    }

    @Test
    void tsMiddlewarePathIsBackend() {
        var node = new CodeNode("tm1", NodeKind.CLASS, "AuthMiddleware");
        node.setFilePath("src/middleware/auth.ts");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void tsModelsPathIsBackend() {
        var node = new CodeNode("tmd1", NodeKind.CLASS, "User");
        node.setFilePath("src/models/User.ts");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void tsHelpersPathIsShared() {
        var node = new CodeNode("th1", NodeKind.CLASS, "StringHelper");
        node.setFilePath("src/helpers/string.ts");
        assertEquals("shared", classifier.classifyOne(node));
    }

    // -- Framework property heuristics --

    @Test
    void springBootFrameworkIsBackend() {
        var node = new CodeNode("sb1", NodeKind.CLASS, "App");
        node.setProperties(Map.of("framework", "spring_boot"));
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void fastifyFrameworkIsBackend() {
        var node = new CodeNode("ff1", NodeKind.CLASS, "Server");
        node.setProperties(Map.of("framework", "fastify"));
        assertEquals("backend", classifier.classifyOne(node));
    }

    // -- METHOD nodes inherit from path --

    @Test
    void methodInControllerPathIsBackend() {
        var node = new CodeNode("m1", NodeKind.METHOD, "findOwner");
        node.setFilePath("src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java");
        assertEquals("backend", classifier.classifyOne(node));
    }

    @Test
    void methodInConfigPathIsShared() {
        var node = new CodeNode("m2", NodeKind.METHOD, "dataSource");
        node.setFilePath("src/main/java/com/example/config/DataSourceConfig.java");
        assertEquals("shared", classifier.classifyOne(node));
    }

    // -- Fallback does not override existing classification --

    @Test
    void existingKindClassificationNotOverriddenByFallback() {
        // ENDPOINT is backend by node kind, even though path has "config"
        var node = new CodeNode("e1", NodeKind.ENDPOINT, "GET /config");
        node.setFilePath("src/main/java/com/example/config/ConfigController.java");
        assertEquals("backend", classifier.classifyOne(node));
    }

    // -- Case insensitivity --

    @Test
    void pathHeuristicIsCaseInsensitive() {
        var node = new CodeNode("ci1", NodeKind.CLASS, "Foo");
        node.setFilePath("src/main/java/com/example/Controller/Foo.java");
        assertEquals("backend", classifier.classifyOne(node));
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
        // "Util" with no path info remains unknown
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

    // ---- Determinism ----

    @Test
    void classificationIsDeterministic() {
        var nodes = List.of(
                createNode("n1", NodeKind.CLASS, "Owner", "src/main/java/com/example/model/Owner.java"),
                createNode("n2", NodeKind.METHOD, "findAll", "src/main/java/com/example/repository/OwnerRepo.java"),
                createNode("n3", NodeKind.CLASS, "AppConfig", "src/main/java/com/example/config/AppConfig.java"),
                createNode("n4", NodeKind.CLASS, "DateUtil", "src/main/java/com/example/util/DateUtil.java"),
                createNode("n5", NodeKind.CLASS, "Unknown", null)
        );

        // Run classification twice and verify identical results
        var run1 = nodes.stream().map(classifier::classifyOne).toList();
        var run2 = nodes.stream().map(classifier::classifyOne).toList();
        assertEquals(run1, run2, "Classification must be deterministic across runs");

        assertEquals("backend", run1.get(0));
        assertEquals("backend", run1.get(1));
        assertEquals("shared", run1.get(2));
        assertEquals("shared", run1.get(3));
        assertEquals("unknown", run1.get(4));
    }

    private CodeNode createNode(String id, NodeKind kind, String name, String filePath) {
        var node = new CodeNode(id, kind, name);
        if (filePath != null) {
            node.setFilePath(filePath);
        }
        return node;
    }
}
