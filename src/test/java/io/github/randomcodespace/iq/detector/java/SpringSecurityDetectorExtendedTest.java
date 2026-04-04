package io.github.randomcodespace.iq.detector.java;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended branch-coverage tests for SpringSecurityDetector targeting code paths
 * not covered by the existing JavaDetectors*Test suites.
 */
class SpringSecurityDetectorExtendedTest {

    private final SpringSecurityDetector detector = new SpringSecurityDetector();

    private static DetectorContext ctx(String content) {
        return DetectorTestUtils.contextFor(
                "src/main/java/com/example/SecurityConfig.java", "java", content);
    }

    // ---- @PreAuthorize with complex SpEL expression ---------------------------------

    @Test
    void detectsPreAuthorizeWithComplexSpel() {
        String code = """
                package com.example;
                import org.springframework.security.access.prepost.PreAuthorize;
                public class DocumentService {
                    @PreAuthorize("isAuthenticated() and hasRole('EDITOR') or hasRole('ADMIN')")
                    public void editDocument(Long id) {}
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        var node = result.nodes().get(0);
        assertEquals(NodeKind.GUARD, node.getKind());
        assertEquals("spring_security", node.getProperties().get("auth_type"));
        @SuppressWarnings("unchecked")
        var roles = (List<?>) node.getProperties().get("roles");
        assertNotNull(roles);
        assertFalse(roles.isEmpty(), "SpEL with hasRole/hasAnyRole should extract roles");
    }

    @Test
    void detectsPreAuthorizeWithHasAnyRoleMultipleValues() {
        String code = """
                package com.example;
                import org.springframework.security.access.prepost.PreAuthorize;
                public class ReportService {
                    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'SUPERVISOR')")
                    public void generateReport() {}
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        @SuppressWarnings("unchecked")
        var roles = (List<?>) result.nodes().get(0).getProperties().get("roles");
        assertNotNull(roles);
        assertEquals(3, roles.size(), "All roles in hasAnyRole should be extracted");
        assertTrue(roles.contains("ADMIN"));
        assertTrue(roles.contains("MANAGER"));
        assertTrue(roles.contains("SUPERVISOR"));
    }

    @Test
    void detectsPreAuthorizeExpressionStoredAsProperty() {
        String code = """
                package com.example;
                import org.springframework.security.access.prepost.PreAuthorize;
                public class UserService {
                    @PreAuthorize("hasRole('USER') and #id == authentication.principal.id")
                    public void updateProfile(Long id) {}
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        String expr = (String) result.nodes().get(0).getProperties().get("expression");
        assertNotNull(expr, "expression property should be set for @PreAuthorize");
        assertTrue(expr.contains("hasRole"), "expression should contain the SpEL value");
    }

    // ---- @PostAuthorize -------------------------------------------------------------

    @Test
    void detectsPostAuthorizeAnnotation() {
        // @PostAuthorize is not directly handled by SpringSecurityDetector but the
        // class-level scanning should still handle it if present.
        // The detector currently looks for @PreAuthorize, @Secured, @RolesAllowed.
        // @PostAuthorize is not listed — this test documents and verifies the behavior.
        String code = """
                package com.example;
                import org.springframework.security.access.prepost.PreAuthorize;
                import org.springframework.security.access.prepost.PostAuthorize;
                public class ResourceService {
                    @PreAuthorize("isAuthenticated()")
                    @PostAuthorize("returnObject.owner == authentication.name")
                    public String getResource(Long id) { return null; }
                }
                """;
        var result = detector.detect(ctx(code));
        // @PreAuthorize should be detected
        assertTrue(result.nodes().stream().anyMatch(n ->
                "@PreAuthorize".equals(n.getLabel())), "@PreAuthorize should be detected");
    }

    // ---- @Secured -------------------------------------------------------------------

    @Test
    void detectsSecuredWithSingleRoleString() {
        String code = """
                package com.example;
                import org.springframework.security.access.annotation.Secured;
                public class AdminService {
                    @Secured("ROLE_ADMIN")
                    public void performAdminAction() {}
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        var node = result.nodes().get(0);
        assertEquals("@Secured", node.getLabel());
        @SuppressWarnings("unchecked")
        var roles = (List<?>) node.getProperties().get("roles");
        assertNotNull(roles);
        assertTrue(roles.contains("ROLE_ADMIN"));
    }

    @Test
    void detectsSecuredWithMultipleRolesArray() {
        String code = """
                package com.example;
                import org.springframework.security.access.annotation.Secured;
                public class DataService {
                    @Secured({"ROLE_ADMIN", "ROLE_MANAGER", "ROLE_OWNER"})
                    public void sensitiveOperation() {}
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        @SuppressWarnings("unchecked")
        var roles = (List<?>) result.nodes().get(0).getProperties().get("roles");
        assertNotNull(roles);
        assertEquals(3, roles.size(), "All roles in @Secured array should be extracted");
        assertTrue(roles.contains("ROLE_ADMIN"));
        assertTrue(roles.contains("ROLE_MANAGER"));
        assertTrue(roles.contains("ROLE_OWNER"));
    }

    // ---- @RolesAllowed --------------------------------------------------------------

    @Test
    void detectsRolesAllowedWithSingleRole() {
        String code = """
                package com.example;
                import jakarta.annotation.security.RolesAllowed;
                public class InventoryService {
                    @RolesAllowed("WAREHOUSE_MANAGER")
                    public void manageStock() {}
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertEquals("@RolesAllowed", result.nodes().get(0).getLabel());
        @SuppressWarnings("unchecked")
        var roles = (List<?>) result.nodes().get(0).getProperties().get("roles");
        assertNotNull(roles);
    }

    @Test
    void detectsRolesAllowedWithMultipleRolesArray() {
        String code = """
                package com.example;
                import jakarta.annotation.security.RolesAllowed;
                public class ContentService {
                    @RolesAllowed({"CONTENT_EDITOR", "ADMIN"})
                    public void publishContent() {}
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        @SuppressWarnings("unchecked")
        var roles = (List<?>) result.nodes().get(0).getProperties().get("roles");
        assertNotNull(roles);
        assertFalse(roles.isEmpty());
    }

    // ---- SecurityFilterChain bean ---------------------------------------------------

    @Test
    void detectsSecurityFilterChainBean() {
        String code = """
                package com.example;
                import org.springframework.context.annotation.Bean;
                import org.springframework.security.web.SecurityFilterChain;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                public class WebSecurityConfig {
                    @Bean
                    public SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
                        return http.build();
                    }
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertTrue(result.nodes().stream().anyMatch(n ->
                "spring_security".equals(n.getProperties().get("auth_type"))));
        // Should capture the method name
        assertTrue(result.nodes().stream().anyMatch(n ->
                n.getProperties().containsKey("method_name")));
    }

    // ---- HttpSecurity with antMatchers/requestMatchers -----------------------------

    @Test
    void detectsAuthorizeHttpRequestsWithRequestMatchers() {
        String code = """
                package com.example;
                import org.springframework.context.annotation.Bean;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                import org.springframework.security.web.SecurityFilterChain;
                public class SecurityConfig {
                    @Bean
                    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
                        http.authorizeHttpRequests(auth -> auth
                            .requestMatchers("/public/**").permitAll()
                            .requestMatchers("/admin/**").hasRole("ADMIN")
                            .anyRequest().authenticated()
                        );
                        return http.build();
                    }
                }
                """;
        var result = detector.detect(ctx(code));
        assertTrue(result.nodes().stream().anyMatch(n ->
                n.getLabel() != null && n.getLabel().contains("authorizeHttpRequests")),
                "Should detect .authorizeHttpRequests() call");
    }

    @Test
    void detectsPermitAllAndAuthenticated() {
        String code = """
                package com.example;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                import org.springframework.security.web.SecurityFilterChain;
                public class OpenApiSecurityConfig {
                    public SecurityFilterChain openChain(HttpSecurity http) throws Exception {
                        http.authorizeHttpRequests(auth -> auth
                            .requestMatchers("/health", "/info").permitAll()
                            .anyRequest().authenticated()
                        );
                        return http.build();
                    }
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
    }

    // ---- hasRole / hasAnyRole in configuration methods ------------------------------

    @Test
    void detectsHasRoleInFilterChainBody() {
        String code = """
                package com.example;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                import org.springframework.security.web.SecurityFilterChain;
                public class SecurityConfig {
                    public SecurityFilterChain chain(HttpSecurity http) throws Exception {
                        http.authorizeHttpRequests(auth -> auth
                            .requestMatchers("/reports/**").hasRole("ANALYST")
                        );
                        return http.build();
                    }
                }
                """;
        var result = detector.detect(ctx(code));
        // SecurityFilterChain + authorizeHttpRequests both detected
        assertTrue(result.nodes().size() >= 1);
    }

    // ---- Multiple security guards in one class -------------------------------------

    @Test
    void detectsMultipleSecurityAnnotationsInOneClass() {
        String code = """
                package com.example;
                import org.springframework.security.access.prepost.PreAuthorize;
                import org.springframework.security.access.annotation.Secured;
                import jakarta.annotation.security.RolesAllowed;
                public class MultiSecService {
                    @PreAuthorize("hasRole('READER')")
                    public void read() {}
                    @Secured("ROLE_WRITER")
                    public void write() {}
                    @RolesAllowed("ADMIN")
                    public void admin() {}
                }
                """;
        var result = detector.detect(ctx(code));
        assertEquals(3, result.nodes().size(), "All 3 security annotations should be detected");
    }

    // ---- @EnableWebSecurity + @EnableMethodSecurity together -----------------------

    @Test
    void detectsBothEnableAnnotations() {
        String code = """
                package com.example;
                import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
                import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
                @EnableWebSecurity
                @EnableMethodSecurity
                public class FullSecurityConfig {}
                """;
        var result = detector.detect(ctx(code));
        assertTrue(result.nodes().size() >= 2,
                "Both @EnableWebSecurity and @EnableMethodSecurity should be detected");
        assertTrue(result.nodes().stream().anyMatch(n ->
                "@EnableWebSecurity".equals(n.getLabel())));
        assertTrue(result.nodes().stream().anyMatch(n ->
                "@EnableMethodSecurity".equals(n.getLabel())));
    }

    // ---- auth_required property is set ---------------------------------------------

    @Test
    void securityNodesHaveAuthRequiredTrue() {
        String code = """
                package com.example;
                import org.springframework.security.access.prepost.PreAuthorize;
                public class SecureOps {
                    @PreAuthorize("hasRole('OP')")
                    public void operate() {}
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertEquals(true, result.nodes().get(0).getProperties().get("auth_required"));
    }

    // ---- framework property --------------------------------------------------------

    @Test
    void securityNodesHaveSpringBootFramework() {
        String code = """
                package com.example;
                import org.springframework.security.access.annotation.Secured;
                public class SecService {
                    @Secured("ROLE_USER")
                    public void doWork() {}
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertEquals("spring_boot", result.nodes().get(0).getProperties().get("framework"));
    }

    // ---- Determinism ----------------------------------------------------------------

    @Test
    void isDeterministic() {
        String code = """
                package com.example;
                import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
                import org.springframework.security.access.prepost.PreAuthorize;
                import org.springframework.security.access.annotation.Secured;
                @EnableWebSecurity
                public class SecConfig {
                    @PreAuthorize("hasRole('ADMIN')")
                    public void adminOp() {}
                    @Secured({"ROLE_USER", "ROLE_GUEST"})
                    public void userOp() {}
                }
                """;
        DetectorTestUtils.assertDeterministic(detector, ctx(code));
    }

    // ---- getName / getSupportedLanguages --------------------------------------------

    @Test
    void returnsCorrectName() {
        assertEquals("spring_security", detector.getName());
    }

    @Test
    void supportedLanguagesContainsJava() {
        assertTrue(detector.getSupportedLanguages().contains("java"));
    }

    // ---- Regex fallback (NUL byte forces JavaParser failure) -------------------------

    @Test
    void regexFallback_detectsSecuredSingleRole() {
        String code = "\u0000 class AdminSvc {\n"
                + "    @Secured(\"ROLE_ADMIN\")\n"
                + "    public void adminAction() {}\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect @Secured single role");
        @SuppressWarnings("unchecked")
        var roles = (List<?>) result.nodes().get(0).getProperties().get("roles");
        assertNotNull(roles);
        assertTrue(roles.contains("ROLE_ADMIN"));
    }

    @Test
    void regexFallback_detectsSecuredMultipleRoles() {
        String code = "\u0000 class DataSvc {\n"
                + "    @Secured({\"ROLE_ADMIN\", \"ROLE_MANAGER\"})\n"
                + "    public void sensitiveOp() {}\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect @Secured with multiple roles");
        @SuppressWarnings("unchecked")
        var roles = (List<?>) result.nodes().get(0).getProperties().get("roles");
        assertNotNull(roles);
        assertEquals(2, roles.size());
    }

    @Test
    void regexFallback_detectsPreAuthorizeWithHasRole() {
        String code = "\u0000 class ReportSvc {\n"
                + "    @PreAuthorize(\"hasRole('ANALYST')\")\n"
                + "    public void generateReport() {}\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect @PreAuthorize");
        @SuppressWarnings("unchecked")
        var roles = (List<?>) result.nodes().get(0).getProperties().get("roles");
        assertNotNull(roles);
        assertTrue(roles.contains("ANALYST"));
    }

    @Test
    void regexFallback_detectsPreAuthorizeWithHasAnyRole() {
        String code = "\u0000 class ContentSvc {\n"
                + "    @PreAuthorize(\"hasAnyRole('EDITOR', 'ADMIN')\")\n"
                + "    public void publish() {}\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect @PreAuthorize with hasAnyRole");
        @SuppressWarnings("unchecked")
        var roles = (List<?>) result.nodes().get(0).getProperties().get("roles");
        assertNotNull(roles);
        assertFalse(roles.isEmpty());
    }

    @Test
    void regexFallback_detectsRolesAllowedSingleRole() {
        String code = "\u0000 class WareHouseSvc {\n"
                + "    @RolesAllowed(\"WAREHOUSE_MANAGER\")\n"
                + "    public void manage() {}\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect @RolesAllowed single role");
        assertEquals("@RolesAllowed", result.nodes().get(0).getLabel());
    }

    @Test
    void regexFallback_detectsRolesAllowedMultipleRoles() {
        String code = "\u0000 class CatalogSvc {\n"
                + "    @RolesAllowed({\"CATALOG_EDITOR\", \"ADMIN\"})\n"
                + "    public void editCatalog() {}\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect @RolesAllowed with multiple roles");
    }

    @Test
    void regexFallback_detectsEnableMethodSecurity() {
        String code = "@EnableMethodSecurity\n\u0000 class MethodSecCfg {\n}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect @EnableMethodSecurity");
        assertTrue(result.nodes().stream().anyMatch(n ->
                "@EnableMethodSecurity".equals(n.getLabel())));
    }

    @Test
    void regexFallback_detectsAuthorizeHttpRequests() {
        String code = "\u0000 class SecCfg {\n"
                + "    public SecurityFilterChain chain(HttpSecurity http) {\n"
                + "        http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated());\n"
                + "        return http.build();\n"
                + "    }\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect .authorizeHttpRequests()");
        assertTrue(result.nodes().stream().anyMatch(n ->
                n.getLabel() != null && n.getLabel().contains("authorizeHttpRequests")));
    }

    @Test
    void regexFallback_detectsSecurityFilterChain() {
        String code = "\u0000 class SecurityCfg {\n"
                + "    public SecurityFilterChain myChain(HttpSecurity http) { return null; }\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect SecurityFilterChain method");
        assertTrue(result.nodes().stream().anyMatch(n ->
                n.getProperties().containsKey("method_name")));
    }

    @Test
    void regexFallback_noSecurityAnnotations_returnsEmpty() {
        String code = "\u0000 class PlainSvc {\n"
                + "    public void doWork() {}\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertTrue(result.nodes().isEmpty(),
                "No security annotations should yield empty result");
    }
}
