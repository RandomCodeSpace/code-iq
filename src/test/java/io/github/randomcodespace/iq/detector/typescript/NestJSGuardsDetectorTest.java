package io.github.randomcodespace.iq.detector.typescript;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NestJSGuardsDetectorTest {

    private final NestJSGuardsDetector detector = new NestJSGuardsDetector();

    @Test
    void detectsGuardsAndRoles() {
        String code = """
                import { UseGuards } from '@nestjs/common';
                import { AuthGuard } from '@nestjs/passport';
                @UseGuards(JwtAuthGuard, RolesGuard)
                @Roles('admin', 'user')
                canActivate(context) {
                    return true;
                }
                AuthGuard('jwt')
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("src/auth.guard.ts", "typescript", code);
        DetectorResult result = detector.detect(ctx);

        // 2 UseGuards + 1 Roles + 1 canActivate + 1 AuthGuard = 5
        assertEquals(5, result.nodes().size());
        assertTrue(result.nodes().stream().allMatch(n -> n.getKind() == NodeKind.GUARD));
        assertTrue(result.nodes().stream().anyMatch(n ->
                "UseGuards(JwtAuthGuard)".equals(n.getLabel())));
        assertTrue(result.nodes().stream().anyMatch(n ->
                n.getLabel().contains("Roles(admin, user)")));
    }

    @Test
    void noMatchWithoutNestJSImport() {
        // Generic TypeScript with canActivate() but no @nestjs import
        String code = """
                class RouteGuard {
                    canActivate(context) {
                        return this.authService.isAuthenticated();
                    }
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("src/route.guard.ts", "typescript", code);
        DetectorResult result = detector.detect(ctx);
        assertTrue(result.nodes().isEmpty());
    }

    @Test
    void noMatchOnNonGuardCode() {
        String code = "class SomeService {}";
        DetectorContext ctx = DetectorTestUtils.contextFor("typescript", code);
        DetectorResult result = detector.detect(ctx);
        assertTrue(result.nodes().isEmpty());
    }

    @Test
    void noFalsePositiveOnGenericCanActivate() {
        // canActivate() in Angular or custom code without @nestjs/ import must not match
        String code = """
                import { CanActivate } from '@angular/router';
                export class AuthGuard implements CanActivate {
                    canActivate(route, state) { return true; }
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("src/auth.guard.ts", "typescript", code);
        DetectorResult result = detector.detect(ctx);
        assertTrue(result.nodes().isEmpty(), "Should not match Angular guard without @nestjs/ import");
    }

    @Test
    void deterministic() {
        String code = "import { UseGuards } from '@nestjs/common';\n@UseGuards(AuthGuard)\n@Roles('admin')";
        DetectorContext ctx = DetectorTestUtils.contextFor("src/auth.guard.ts", "typescript", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
