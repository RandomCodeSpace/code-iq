package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FastAPIAuthDetectorTest {

    private final FastAPIAuthDetector detector = new FastAPIAuthDetector();

    @Test
    void detectsDependsAuth() {
        String code = """
                async def get_items(user=Depends(get_current_user)):
                    return []
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals(NodeKind.GUARD, result.nodes().get(0).getKind());
        assertEquals("Depends(get_current_user)", result.nodes().get(0).getLabel());
        assertEquals("fastapi", result.nodes().get(0).getProperties().get("auth_type"));
    }

    @Test
    void detectsSecurityScheme() {
        String code = """
                async def protected(token=Security(oauth2_scheme)):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("Security(oauth2_scheme)", result.nodes().get(0).getLabel());
        assertEquals("oauth2_scheme", result.nodes().get(0).getProperties().get("scheme"));
    }

    @Test
    void detectsOAuth2PasswordBearer() {
        String code = """
                oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/auth/token")
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("/auth/token", result.nodes().get(0).getProperties().get("token_url"));
    }

    @Test
    void detectsHTTPBearer() {
        String code = """
                bearer = HTTPBearer()
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("HTTPBearer()", result.nodes().get(0).getLabel());
        assertEquals("bearer", result.nodes().get(0).getProperties().get("auth_flow"));
    }

    @Test
    void detectsHTTPBasic() {
        String code = """
                basic = HTTPBasic()
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("HTTPBasic()", result.nodes().get(0).getLabel());
        assertEquals("basic", result.nodes().get(0).getProperties().get("auth_flow"));
    }

    @Test
    void noMatchOnPlainCode() {
        String code = """
                def hello():
                    return "world"
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
    }

    @Test
    void deterministic() {
        String code = """
                oauth2 = OAuth2PasswordBearer(tokenUrl="/token")
                bearer = HTTPBearer()

                async def protected(user=Depends(get_current_user)):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }

    @Test
    void detectsDependsRequireAuth() {
        String code = """
                async def get_data(auth=Depends(require_auth)):
                    return {}
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("require_auth", result.nodes().get(0).getProperties().get("dependency"));
    }

    @Test
    void detectsDependsAuthPrefix() {
        String code = """
                async def endpoint(current=Depends(auth_handler)):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("fastapi", result.nodes().get(0).getProperties().get("auth_type"));
        assertEquals("oauth2", result.nodes().get(0).getProperties().get("auth_flow"));
        assertEquals(true, result.nodes().get(0).getProperties().get("auth_required"));
    }

    @Test
    void allAuthTypesInOneFile() {
        String code = """
                oauth2 = OAuth2PasswordBearer(tokenUrl="/token")
                bearer_scheme = HTTPBearer()
                basic_scheme = HTTPBasic()

                async def endpoint1(user=Depends(get_current_user)):
                    pass

                async def endpoint2(user=Security(oauth2)):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("api.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        // OAuth2PasswordBearer + HTTPBearer + HTTPBasic + Depends + Security = 5 guards
        assertTrue(result.nodes().size() >= 5);
        assertTrue(result.nodes().stream().allMatch(n -> n.getKind() == NodeKind.GUARD));
    }

    @Test
    void authRequiredPropertyIsTrue() {
        String code = """
                auth = HTTPBearer()
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(true, result.nodes().get(0).getProperties().get("auth_required"));
    }

    @Test
    void annotationsSetCorrectly() {
        String code = """
                bearer = HTTPBearer()
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        var node = result.nodes().get(0);
        assertNotNull(node.getAnnotations());
        assertFalse(node.getAnnotations().isEmpty());
        assertTrue(node.getAnnotations().contains("HTTPBearer"));
    }

    @Test
    void oauth2PasswordBearerAuthFlow() {
        String code = """
                scheme = OAuth2PasswordBearer(tokenUrl="/login")
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals("oauth2", result.nodes().get(0).getProperties().get("auth_flow"));
    }

    @Test
    void emptyFileReturnsEmpty() {
        DetectorContext ctx = DetectorTestUtils.contextFor("python", "");
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
        assertEquals(0, result.edges().size());
    }

    @Test
    void fileLevelModuleAndPath() {
        String code = """
                async def get_items(user=Depends(get_current_user)):
                    return []
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("api/routes.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("api/routes.py", result.nodes().get(0).getFilePath());
    }

    // ---- Regex fallback path (content > 500KB) ----

    private static String pad(String code) {
        return code + "\n" + "#\n".repeat(260_000);
    }

    @Test
    void regexFallback_detectsDependsAuth() {
        String code = pad("""
                async def get_items(user=Depends(get_current_user)):
                    return []
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("api/routes.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.GUARD
                && n.getLabel().contains("get_current_user")),
                "regex fallback should detect Depends(get_current_user)");
        assertEquals("fastapi", result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.GUARD).findFirst().orElseThrow()
                .getProperties().get("auth_type"));
    }

    @Test
    void regexFallback_detectsOAuth2PasswordBearer() {
        String code = pad("""
                oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/token")
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("auth.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.GUARD
                && "oauth2".equals(n.getProperties().get("auth_flow"))),
                "regex fallback should detect OAuth2PasswordBearer");
    }

    @Test
    void regexFallback_detectsHTTPBearer() {
        String code = pad("""
                security = HTTPBearer()
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("auth.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.GUARD
                && "bearer".equals(n.getProperties().get("auth_flow"))),
                "regex fallback should detect HTTPBearer");
    }

    @Test
    void regexFallback_detectsHTTPBasic() {
        String code = pad("""
                basic_auth = HTTPBasic()
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("auth.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.GUARD
                && "basic".equals(n.getProperties().get("auth_flow"))),
                "regex fallback should detect HTTPBasic");
    }
}
