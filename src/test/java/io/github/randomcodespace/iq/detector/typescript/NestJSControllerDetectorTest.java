package io.github.randomcodespace.iq.detector.typescript;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NestJSControllerDetectorTest {

    private final NestJSControllerDetector detector = new NestJSControllerDetector();

    @Test
    void detectsNestJSController() {
        String code = """
                import { Controller, Get, Post } from '@nestjs/common';
                @Controller('users')
                export class UsersController {
                    @Get()
                    findAll() {}
                    @Post()
                    create() {}
                    @Get('/:id')
                    findOne() {}
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("src/users.controller.ts", "typescript", code);
        DetectorResult result = detector.detect(ctx);

        // 1 class + 3 endpoints
        assertEquals(4, result.nodes().size());
        assertEquals(NodeKind.CLASS, result.nodes().get(0).getKind());
        assertEquals("nestjs", result.nodes().get(0).getProperties().get("framework"));
        // Endpoints
        assertTrue(result.nodes().stream().anyMatch(n ->
                n.getKind() == NodeKind.ENDPOINT && "GET /users".equals(n.getLabel())));
        // EXPOSES edges — each has both sourceId and target set
        assertEquals(3, result.edges().size());
        assertTrue(result.edges().stream().allMatch(e -> e.getTarget() != null),
                "All EXPOSES edges must have a target node set");
    }

    @Test
    void noMatchOnNonNestJSCode() {
        String code = "class SomeService {}";
        DetectorContext ctx = DetectorTestUtils.contextFor("typescript", code);
        DetectorResult result = detector.detect(ctx);
        assertTrue(result.nodes().isEmpty());
    }

    @Test
    void noFalsePositiveOnAngularController() {
        // Angular @Controller-like patterns without @nestjs/ import must not match
        String code = """
                import { Component } from '@angular/core';
                @Controller('items')
                export class ItemsComponent {
                    @Get()
                    list() {}
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("src/items.component.ts", "typescript", code);
        DetectorResult result = detector.detect(ctx);
        assertTrue(result.nodes().isEmpty(), "Should not match Angular component without @nestjs/ import");
    }

    @Test
    void deterministic() {
        String code = "import { Controller, Get } from '@nestjs/common';\n@Controller('test')\nexport class TestController {\n    @Get()\n    find() {}\n}";
        DetectorContext ctx = DetectorTestUtils.contextFor("typescript", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
