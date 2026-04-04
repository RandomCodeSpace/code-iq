package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FastAPIAuthDetectorExtendedTest {

    private final FastAPIAuthDetector detector = new FastAPIAuthDetector();

    private static String pad(String code) {
        return code + "\n" + "#\n".repeat(260_000);
    }

    // ---- HTTPBearer variations ----

    @Test
    void detectsHTTPBearerWithAutoError() {
        String code = """
                security = HTTPBearer(auto_error=False)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("security.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals(NodeKind.GUARD, result.nodes().get(0).getKind());
        assertEquals("bearer", result.nodes().get(0).getProperties().get("auth_flow"));
    }

    @Test
    void httpBearerNodeHasAuthRequiredTrue() {
        String code = """
                jwt_bearer = HTTPBearer()
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("auth.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals(true, result.nodes().get(0).getProperties().get("auth_required"));
        assertEquals("fastapi", result.nodes().get(0).getProperties().get("auth_type"));
    }

    @Test
    void httpBearerAnnotationContainsHTTPBearer() {
        String code = """
                token_scheme = HTTPBearer()
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("auth.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var node = result.nodes().get(0);
        assertNotNull(node.getAnnotations());
        assertTrue(node.getAnnotations().contains("HTTPBearer"));
    }

    // ---- OAuth2PasswordBearer ----

    @Test
    void detectsOAuth2PasswordBearerWithDifferentTokenUrl() {
        String code = """
                oauth2 = OAuth2PasswordBearer(tokenUrl="/api/v1/auth/token")
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("auth.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("/api/v1/auth/token", result.nodes().get(0).getProperties().get("token_url"));
        assertEquals("oauth2", result.nodes().get(0).getProperties().get("auth_flow"));
    }

    @Test
    void oauth2PasswordBearerHasOAuth2AuthFlow() {
        String code = """
                scheme = OAuth2PasswordBearer(tokenUrl="token")
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("auth.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals("oauth2", result.nodes().get(0).getProperties().get("auth_flow"));
        assertEquals("fastapi", result.nodes().get(0).getProperties().get("auth_type"));
    }

    @Test
    void regexFallback_oauth2PasswordBearerWithPath() {
        String code = pad("""
                token_scheme = OAuth2PasswordBearer(tokenUrl="/auth/login")
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("auth.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream()
                .anyMatch(n -> n.getKind() == NodeKind.GUARD
                        && "oauth2".equals(n.getProperties().get("auth_flow"))));
        assertEquals("/auth/login", result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.GUARD)
                .findFirst().orElseThrow()
                .getProperties().get("token_url"));
    }

    // ---- HTTPBasic variations ----

    @Test
    void detectsHTTPBasicAuthFlow() {
        String code = """
                auth = HTTPBasic()
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("auth.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("basic", result.nodes().get(0).getProperties().get("auth_flow"));
    }

    @Test
    void httpBasicAnnotationIsSet() {
        String code = """
                basic_scheme = HTTPBasic()
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("auth.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().get(0).getAnnotations().contains("HTTPBasic"));
    }

    @Test
    void regexFallback_detectsHTTPBasicInLargeFile() {
        String code = pad("""
                basic_auth = HTTPBasic()

                async def login(creds=Depends(basic_auth)):
                    pass
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("auth.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream()
                .anyMatch(n -> "basic".equals(n.getProperties().get("auth_flow"))));
    }

    // ---- Multiple auth patterns in same file ----

    @Test
    void detectsMultipleAuthPatternsInOneFile() {
        String code = """
                oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/token")
                bearer_scheme = HTTPBearer()
                basic_scheme = HTTPBasic()

                async def endpoint1(token=Depends(get_current_user)):
                    pass

                async def endpoint2(creds=Security(oauth2_scheme)):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("api.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().size() >= 5);
        assertTrue(result.nodes().stream().allMatch(n -> n.getKind() == NodeKind.GUARD));
    }

    @Test
    void regexFallback_detectsMultipleAuthPatterns() {
        String code = pad("""
                oauth2 = OAuth2PasswordBearer(tokenUrl="/auth/token")
                bearer = HTTPBearer()
                basic = HTTPBasic()
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("api.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().size() >= 3);
        assertTrue(result.nodes().stream()
                .anyMatch(n -> "oauth2".equals(n.getProperties().get("auth_flow"))));
        assertTrue(result.nodes().stream()
                .anyMatch(n -> "bearer".equals(n.getProperties().get("auth_flow"))));
        assertTrue(result.nodes().stream()
                .anyMatch(n -> "basic".equals(n.getProperties().get("auth_flow"))));
    }

    // ---- File without any auth (empty result) ----

    @Test
    void fileWithoutAuthReturnsEmpty() {
        String code = """
                def regular_function():
                    return {"key": "value"}

                class SomeClass:
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("routes.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
        assertEquals(0, result.edges().size());
    }

    @Test
    void regexFallback_fileWithoutAuthReturnsEmpty() {
        String code = pad("""
                def process_data(items):
                    return [x * 2 for x in items]
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("processor.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
    }

    // ---- Depends with get_current_user ----

    @Test
    void detectsDependsGetCurrentUser() {
        String code = """
                async def read_items(current_user=Depends(get_current_user)):
                    return []
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("routes.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("get_current_user", result.nodes().get(0).getProperties().get("dependency"));
        assertEquals("fastapi", result.nodes().get(0).getProperties().get("auth_type"));
    }

    @Test
    void detectsDependsGetCurrentActiveUser() {
        String code = """
                async def me(user=Depends(get_current_active_user)):
                    return user
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("routes.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("get_current_active_user", result.nodes().get(0).getProperties().get("dependency"));
    }

    @Test
    void regexFallback_detectsDependsGetCurrentUser() {
        String code = pad("""
                async def get_profile(current_user=Depends(get_current_user)):
                    return current_user
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("routes.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream()
                .anyMatch(n -> n.getKind() == NodeKind.GUARD
                        && "fastapi".equals(n.getProperties().get("auth_type"))));
    }

    // ---- Security() calls ----

    @Test
    void detectsSecurityWithSchemeName() {
        String code = """
                async def protected(user=Security(my_oauth2_scheme)):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("api.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("my_oauth2_scheme", result.nodes().get(0).getProperties().get("scheme"));
    }

    @Test
    void regexFallback_detectsSecurity() {
        String code = pad("""
                async def secured(token=Security(jwt_scheme)):
                    pass
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("api.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream()
                .anyMatch(n -> n.getKind() == NodeKind.GUARD
                        && "jwt_scheme".equals(n.getProperties().get("scheme"))));
    }

    // ---- File path is preserved ----

    @Test
    void filePathIsSetOnAuthNode() {
        String code = """
                bearer = HTTPBearer()
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("api/auth/schemes.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals("api/auth/schemes.py", result.nodes().get(0).getFilePath());
    }

    // ---- Determinism ----

    @Test
    void deterministicWithMultipleSchemes() {
        String code = """
                oauth2 = OAuth2PasswordBearer(tokenUrl="/token")
                bearer = HTTPBearer()
                basic = HTTPBasic()

                async def a(u=Depends(get_current_user)):
                    pass

                async def b(u=Security(oauth2)):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("auth.py", "python", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
