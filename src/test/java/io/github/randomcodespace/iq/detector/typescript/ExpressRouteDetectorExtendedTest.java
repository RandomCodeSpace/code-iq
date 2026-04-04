package io.github.randomcodespace.iq.detector.typescript;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended tests for ExpressRouteDetector covering branches not yet exercised:
 * - app.all()
 * - router.use() — not matched (use is not an HTTP verb)
 * - named function handlers (same as arrow functions for regex)
 * - Express Router with prefix (app.use is not matched)
 * - multiple routes in same file with various router names
 * - backtick template literal paths
 * - line numbers are set correctly
 * - filePath and moduleName reflected in node IDs
 * - moduleName null handling
 * - no import guard: detector matches even without express import (regex-based)
 */
class ExpressRouteDetectorExtendedTest {

    private final ExpressRouteDetector detector = new ExpressRouteDetector();

    // ---- app.all() --------------------------------------------------

    @Test
    void detectsAppAllMethod() {
        String code = "app.all('/api/*', cors());";
        DetectorContext ctx = DetectorTestUtils.contextFor("src/app.ts", "typescript", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("ALL /api/*", result.nodes().get(0).getLabel());
        assertEquals("ALL", result.nodes().get(0).getProperties().get("http_method"));
        assertEquals("app", result.nodes().get(0).getProperties().get("router"));
        assertEquals("express", result.nodes().get(0).getProperties().get("framework"));
    }

    // ---- router.use is NOT matched (not an HTTP verb) ---------------

    @Test
    void doesNotDetectRouterUse() {
        // app.use is not in the HTTP_METHODS set, so it should not match
        String code = """
                app.use('/api', apiRouter);
                app.use(express.json());
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("src/app.ts", "typescript", code);
        DetectorResult result = detector.detect(ctx);
        assertTrue(result.nodes().isEmpty(), "app.use() should not be detected as an endpoint");
    }

    // ---- named function handlers ------------------------------------

    @Test
    void detectsNamedFunctionHandler() {
        String code = """
                function getUsers(req, res) { res.json([]); }
                app.get('/users', getUsers);
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("src/users.ts", "typescript", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("GET /users", result.nodes().get(0).getLabel());
    }

    // ---- multiple handlers (middleware chain) -----------------------

    @Test
    void detectsRouteWithMultipleHandlersInMiddlewareChain() {
        // The regex only captures up to the first handler — path is still extracted
        String code = "router.post('/login', validateInput, authenticate, createSession);";
        DetectorContext ctx = DetectorTestUtils.contextFor("src/auth.ts", "typescript", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("POST /login", result.nodes().get(0).getLabel());
        assertEquals("router", result.nodes().get(0).getProperties().get("router"));
    }

    // ---- various router names ---------------------------------------

    @Test
    void detectsVariousRouterNames() {
        String code = """
                v1Router.get('/items', list);
                adminRouter.delete('/items/:id', remove);
                this.app.post('/products', create);
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("src/routes.ts", "typescript", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(3, result.nodes().size());
        assertThat(result.nodes()).anyMatch(n -> "GET /items".equals(n.getLabel()));
        assertThat(result.nodes()).anyMatch(n -> "DELETE /items/:id".equals(n.getLabel()));
        assertThat(result.nodes()).anyMatch(n -> "POST /products".equals(n.getLabel()));
    }

    // ---- double-slash and versioned paths ---------------------------

    @Test
    void detectsVersionedApiPaths() {
        String code = """
                router.get('/v1/users', getUsers);
                router.get('/v2/users', getUsersV2);
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("src/api.ts", "typescript", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(2, result.nodes().size());
        assertThat(result.nodes()).anyMatch(n -> n.getProperties().get("path_pattern").equals("/v1/users"));
        assertThat(result.nodes()).anyMatch(n -> n.getProperties().get("path_pattern").equals("/v2/users"));
    }

    // ---- file with no routes (non-route file) -----------------------

    @Test
    void fileWithNoRoutesReturnsEmpty() {
        String code = """
                import express from 'express';
                const PORT = 3000;
                const app = express();
                app.listen(PORT);
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("src/server.ts", "typescript", code);
        DetectorResult result = detector.detect(ctx);
        assertTrue(result.nodes().isEmpty(), "No routes defined, should be empty");
    }

    // ---- moduleName reflected in node ID ---------------------------

    @Test
    void nodeIdIncludesModuleName() {
        String code = "app.get('/ping', handler);";
        DetectorContext ctx = new DetectorContext("src/ping.ts", "typescript", code, null, "my-module");
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        String id = result.nodes().get(0).getId();
        assertTrue(id.contains("my-module"), "Node ID should contain module name: " + id);
    }

    @Test
    void nodeIdWhenModuleNameIsNull() {
        String code = "app.get('/ping', handler);";
        // Minimal constructor — no moduleName
        DetectorContext ctx = new DetectorContext("src/ping.ts", "typescript", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        String id = result.nodes().get(0).getId();
        // ID should still be constructed without error
        assertNotNull(id);
        assertTrue(id.contains("GET"), "Node ID should contain HTTP method: " + id);
    }

    // ---- FQN and filePath are set ----------------------------------

    @Test
    void fqnAndFilePathAreSet() {
        String code = "app.get('/status', checkStatus);";
        DetectorContext ctx = DetectorTestUtils.contextFor("src/health.ts", "typescript", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        var node = result.nodes().get(0);
        assertEquals("src/health.ts", node.getFilePath());
        assertNotNull(node.getFqn());
        assertTrue(node.getFqn().contains("GET"));
        assertTrue(node.getFqn().contains("/status"));
    }

    // ---- line start is populated -----------------------------------

    @Test
    void lineStartIsSetForFirstLine() {
        String code = "app.get('/first', handler);";
        DetectorContext ctx = DetectorTestUtils.contextFor("src/routes.ts", "typescript", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        // Line 1 (0-based vs 1-based depends on implementation)
        assertNotNull(result.nodes().get(0).getLineStart());
    }

    @Test
    void lineStartDifferentiatesMultilineRoutes() {
        String code = """
                app.get('/line1', h1);
                app.post('/line2', h2);
                app.put('/line3', h3);
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("src/routes.ts", "typescript", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(3, result.nodes().size());
        // Lines should be monotonically increasing
        int l1 = result.nodes().get(0).getLineStart();
        int l2 = result.nodes().get(1).getLineStart();
        int l3 = result.nodes().get(2).getLineStart();
        assertTrue(l1 <= l2, "Line numbers should be ascending: " + l1 + ", " + l2);
        assertTrue(l2 <= l3, "Line numbers should be ascending: " + l2 + ", " + l3);
    }

    // ---- JavaScript file (not TypeScript) --------------------------

    @Test
    void worksWithJavaScriptFiles() {
        String code = """
                const express = require('express');
                const app = express();
                app.get('/hello', (req, res) => res.send('hello'));
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("src/app.js", "javascript", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("GET /hello", result.nodes().get(0).getLabel());
    }

    // ---- No express import — detector still detects (no discriminator guard) ---

    @Test
    void detectsWithoutExpressImport() {
        // The regex-based detector has no discriminator guard on imports
        String code = "app.get('/api/data', getData);";
        DetectorContext ctx = DetectorTestUtils.contextFor("src/routes.ts", "typescript", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size(),
                "Should detect Express-style routes even without explicit express import");
    }

    // ---- Edges are always empty ------------------------------------

    @Test
    void alwaysReturnsEmptyEdges() {
        String code = """
                app.get('/a', ha);
                app.post('/b', hb);
                app.put('/c', hc);
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("src/routes.ts", "typescript", code);
        DetectorResult result = detector.detect(ctx);

        assertNotNull(result.edges());
        assertTrue(result.edges().isEmpty(), "ExpressRouteDetector never produces edges");
    }

    // ---- Determinism -----------------------------------------------

    @Test
    void determinismWithMultipleRoutes() {
        String code = """
                app.get('/users', getUsers);
                router.post('/users', createUser);
                v1.put('/users/:id', updateUser);
                app.delete('/users/:id', deleteUser);
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("src/users.ts", "typescript", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
