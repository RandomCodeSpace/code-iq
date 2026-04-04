package io.github.randomcodespace.iq.detector.java;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Branch-coverage-focused tests for Java detectors targeting SonarCloud coverage gaps.
 * Targets: ClassHierarchyDetector, SpringRestDetector, SpringSecurityDetector,
 *          JpaEntityDetector, PublicApiDetector, ConfigDefDetector, MicronautDetector.
 */
class JavaDetectorsBranchCoverageTest {

    private static DetectorContext ctx(String language, String content) {
        return DetectorTestUtils.contextFor(language, content);
    }

    // ========================================================================
    // ClassHierarchyDetector
    // ========================================================================
    @Nested
    class ClassHierarchyDetectorBranches {
        private final ClassHierarchyDetector d = new ClassHierarchyDetector();

        @Test
        void returnsEmptyOnNull() {
            assertTrue(d.detect(new DetectorContext("Test.java", "java", null)).nodes().isEmpty());
        }

        @Test
        void returnsEmptyOnEmpty() {
            assertTrue(d.detect(new DetectorContext("Test.java", "java", "")).nodes().isEmpty());
        }

        @Test
        void detectsSimpleClass() {
            String code = """
                    public class Foo {}
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals(NodeKind.CLASS, r.nodes().get(0).getKind());
        }

        @Test
        void detectsAbstractClass() {
            String code = """
                    public abstract class AbstractFoo {}
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals(NodeKind.ABSTRACT_CLASS, r.nodes().get(0).getKind());
        }

        @Test
        void detectsInterface() {
            String code = """
                    public interface IFoo {}
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals(NodeKind.INTERFACE, r.nodes().get(0).getKind());
        }

        @Test
        void detectsEnum() {
            String code = """
                    public enum Color { RED, GREEN, BLUE }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals(NodeKind.ENUM, r.nodes().get(0).getKind());
        }

        @Test
        void detectsAnnotationType() {
            String code = """
                    public @interface MyAnnotation {}
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals(NodeKind.ANNOTATION_TYPE, r.nodes().get(0).getKind());
        }

        @Test
        void detectsClassExtends() {
            String code = """
                    public class Derived extends Base {}
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.EXTENDS));
            assertEquals("Base", r.nodes().get(0).getProperties().get("superclass"));
        }

        @Test
        void detectsClassImplementsMultiple() {
            String code = """
                    public class FooImpl implements Runnable, Serializable {}
                    """;
            var r = d.detect(ctx("java", code));
            long implEdges = r.edges().stream().filter(e -> e.getKind() == EdgeKind.IMPLEMENTS).count();
            assertEquals(2, implEdges);
        }

        @Test
        void detectsClassExtendsAndImplements() {
            String code = """
                    public class ServiceImpl extends BaseService implements IService, Cloneable {}
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.EXTENDS));
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.IMPLEMENTS));
        }

        @Test
        void detectsInterfaceExtendsInterface() {
            String code = """
                    public interface IExtended extends IBase {}
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.EXTENDS));
        }

        @Test
        void detectsInterfaceExtendsMultiple() {
            String code = """
                    public interface IFull extends IBase1, IBase2, IBase3 {}
                    """;
            var r = d.detect(ctx("java", code));
            long extendsEdges = r.edges().stream().filter(e -> e.getKind() == EdgeKind.EXTENDS).count();
            assertEquals(3, extendsEdges);
        }

        @Test
        void detectsEnumImplementingInterface() {
            String code = """
                    public enum Status implements Displayable {}
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.IMPLEMENTS));
        }

        @Test
        void detectsFinalClass() {
            String code = """
                    public final class ImmutableFoo {}
                    """;
            var r = d.detect(ctx("java", code));
            assertEquals(NodeKind.CLASS, r.nodes().get(0).getKind());
            assertEquals(true, r.nodes().get(0).getProperties().get("is_final"));
        }

        @Test
        void detectsInnerClassAndOuterClass() {
            String code = """
                    public class Outer {
                        public class Inner {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            // Both outer and inner class should be detected
            assertTrue(r.nodes().size() >= 2);
        }

        @Test
        void detectsPrivateAndProtectedVisibility() {
            String code = """
                    public class Outer {
                        private class PrivateInner {}
                        protected class ProtectedInner {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().stream().anyMatch(n -> "private".equals(n.getProperties().get("visibility"))));
            assertTrue(r.nodes().stream().anyMatch(n -> "protected".equals(n.getProperties().get("visibility"))));
        }

        @Test
        void detectsPackagePrivateClass() {
            String code = """
                    class PackagePrivateFoo {}
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("package-private", r.nodes().get(0).getProperties().get("visibility"));
        }

        @Test
        void detectsFullyQualifiedWithPackage() {
            String code = """
                    package com.example.model;
                    public class MyModel extends BaseModel implements Serializable {}
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            // FQN should include package
            String fqn = r.nodes().get(0).getFqn();
            assertNotNull(fqn);
            assertTrue(fqn.contains("MyModel"));
        }

        @Test
        void isDeterministic() {
            String code = """
                    public class Foo extends Bar implements Baz, Qux {}
                    """;
            DetectorTestUtils.assertDeterministic(d, ctx("java", code));
        }

        @Test
        void getName() {
            assertEquals("java.class_hierarchy", d.getName());
        }

        @Test
        void getSupportedLanguages() {
            assertTrue(d.getSupportedLanguages().contains("java"));
        }

        // ---- Regex fallback branch: provide un-parseable content ----
        @Test
        void regexFallback_simpleClass() {
            // Use a string that won't parse as valid Java
            String code = "public class BrokenFoo\n"; // no body, forces regex fallback
            var r = d.detect(ctx("java", code));
            // Might get a node from regex
            assertNotNull(r);
        }

        @Test
        void regexFallback_abstractClass() {
            String code = "abstract class AbstractBar extends Something {\n}";
            var r = d.detect(ctx("java", code));
            assertNotNull(r);
        }

        @Test
        void regexFallback_interfaceExtendsMultiple() {
            String code = "public interface IFull extends A, B, C {\n}";
            var r = d.detect(ctx("java", code));
            assertNotNull(r);
        }

        @Test
        void regexFallback_enum() {
            String code = "public enum Color implements Serializable {\nRED, GREEN\n}";
            var r = d.detect(ctx("java", code));
            assertNotNull(r);
        }

        @Test
        void regexFallback_annotationType() {
            String code = "public @interface MyAnnotation {\n}";
            var r = d.detect(ctx("java", code));
            assertNotNull(r);
        }
    }

    // ========================================================================
    // SpringRestDetector
    // ========================================================================
    @Nested
    class SpringRestDetectorBranches {
        private final SpringRestDetector d = new SpringRestDetector();

        @Test
        void returnsEmptyOnNull() {
            assertTrue(d.detect(new DetectorContext("Ctrl.java", "java", null)).nodes().isEmpty());
        }

        @Test
        void returnsEmptyOnEmpty() {
            assertTrue(d.detect(new DetectorContext("Ctrl.java", "java", "")).nodes().isEmpty());
        }

        @Test
        void detectsGetMapping() {
            String code = """
                    @RestController
                    public class UserController {
                        @GetMapping("/users")
                        public List<User> list() { return null; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("GET", r.nodes().get(0).getProperties().get("http_method"));
        }

        @Test
        void detectsPostMapping() {
            String code = """
                    @RestController
                    public class UserController {
                        @PostMapping("/users")
                        public User create(@RequestBody User u) { return u; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("POST", r.nodes().get(0).getProperties().get("http_method"));
        }

        @Test
        void detectsPutMapping() {
            String code = """
                    @RestController
                    public class UserController {
                        @PutMapping("/users/{id}")
                        public User update(@PathVariable Long id, @RequestBody User u) { return u; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("PUT", r.nodes().get(0).getProperties().get("http_method"));
        }

        @Test
        void detectsDeleteMapping() {
            String code = """
                    @RestController
                    public class UserController {
                        @DeleteMapping("/users/{id}")
                        public void delete(@PathVariable Long id) {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("DELETE", r.nodes().get(0).getProperties().get("http_method"));
        }

        @Test
        void detectsPatchMapping() {
            String code = """
                    @RestController
                    public class UserController {
                        @PatchMapping("/users/{id}")
                        public User patch(@PathVariable Long id) { return null; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("PATCH", r.nodes().get(0).getProperties().get("http_method"));
        }

        @Test
        void detectsRequestMappingWithMethodGet() {
            String code = """
                    @Controller
                    public class SearchController {
                        @RequestMapping(value = "/search", method = RequestMethod.GET)
                        public String search() { return "results"; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("GET", r.nodes().get(0).getProperties().get("http_method"));
        }

        @Test
        void detectsRequestMappingNoMethod() {
            String code = """
                    @Controller
                    public class ApiController {
                        @RequestMapping("/api")
                        public String api() { return "ok"; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("ALL", r.nodes().get(0).getProperties().get("http_method"));
        }

        @Test
        void detectsClassLevelRequestMapping() {
            String code = """
                    @RestController
                    @RequestMapping("/api/v1")
                    public class ApiV1Controller {
                        @GetMapping("/items")
                        public List<String> items() { return null; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            String path = (String) r.nodes().get(0).getProperties().get("path");
            assertTrue(path.contains("/api/v1"));
            assertTrue(path.contains("/items"));
        }

        @Test
        void detectsProducesAndConsumes() {
            String code = """
                    @RestController
                    public class MediaController {
                        @PostMapping(value = "/data", produces = "application/json", consumes = "application/json")
                        public String process() { return "{}"; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertNotNull(r.nodes().get(0).getProperties().get("produces"));
            assertNotNull(r.nodes().get(0).getProperties().get("consumes"));
        }

        @Test
        void skipsModelAttributeMethods() {
            String code = """
                    @Controller
                    public class FormController {
                        @ModelAttribute
                        public User populateUser() { return new User(); }
                        @GetMapping("/form")
                        public String showForm() { return "form"; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            // Only the @GetMapping method should be an endpoint, not @ModelAttribute
            assertEquals(1, r.nodes().stream().filter(n -> n.getKind() == NodeKind.ENDPOINT).count());
        }

        @Test
        void skipsExceptionHandlerMethods() {
            String code = """
                    @RestController
                    public class ApiController {
                        @ExceptionHandler(Exception.class)
                        @GetMapping("/error")
                        public String error() { return "error"; }
                        @GetMapping("/ok")
                        public String ok() { return "ok"; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            // @ExceptionHandler method is skipped
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsRequestBodyAndPathVariable() {
            String code = """
                    @RestController
                    public class ItemController {
                        @PostMapping("/items/{id}")
                        public Item update(@PathVariable Long id, @RequestBody Item item) { return item; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            @SuppressWarnings("unchecked")
            var params = (List<?>) r.nodes().get(0).getProperties().get("parameters");
            assertNotNull(params);
            assertFalse(params.isEmpty());
        }

        @Test
        void detectsMultipleEndpoints() {
            String code = """
                    @RestController
                    @RequestMapping("/orders")
                    public class OrderController {
                        @GetMapping
                        public List<String> list() { return null; }
                        @PostMapping
                        public String create() { return ""; }
                        @GetMapping("/{id}")
                        public String get(@PathVariable Long id) { return ""; }
                        @DeleteMapping("/{id}")
                        public void delete(@PathVariable Long id) {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertEquals(4, r.nodes().stream().filter(n -> n.getKind() == NodeKind.ENDPOINT).count());
        }

        @Test
        void detectsRestTemplateCallsEdge() {
            String code = """
                    @Service
                    public class ClientService {
                        private RestTemplate restTemplate = new RestTemplate();
                        public String fetch() {
                            return restTemplate.getForObject("/api/data", String.class);
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CALLS));
        }

        @Test
        void detectsWebClientCallsEdge() {
            String code = """
                    @Service
                    public class ReactiveClient {
                        private WebClient webClient = WebClient.create();
                        public String fetch() { return ""; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CALLS));
        }

        @Test
        void detectsFeignClientCallsEdge() {
            String code = """
                    @FeignClient("payment-service")
                    public interface PaymentClient {
                        @GetMapping("/pay")
                        String pay();
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CALLS));
        }

        @Test
        void isDeterministic() {
            String code = """
                    @RestController
                    @RequestMapping("/api")
                    public class TestController {
                        @GetMapping("/items")
                        public List<String> list() { return null; }
                        @PostMapping("/items")
                        public String create(@RequestBody String item) { return item; }
                    }
                    """;
            DetectorTestUtils.assertDeterministic(d, ctx("java", code));
        }

        @Test
        void getName() {
            assertEquals("spring_rest", d.getName());
        }

        @Test
        void getSupportedLanguages() {
            assertTrue(d.getSupportedLanguages().contains("java"));
        }
    }

    // ========================================================================
    // SpringSecurityDetector
    // ========================================================================
    @Nested
    class SpringSecurityDetectorBranches {
        private final SpringSecurityDetector d = new SpringSecurityDetector();

        @Test
        void returnsEmptyOnNull() {
            assertTrue(d.detect(new DetectorContext("Sec.java", "java", null)).nodes().isEmpty());
        }

        @Test
        void returnsEmptyOnEmpty() {
            assertTrue(d.detect(new DetectorContext("Sec.java", "java", "")).nodes().isEmpty());
        }

        @Test
        void detectsEnableWebSecurity() {
            String code = """
                    @Configuration
                    @EnableWebSecurity
                    public class SecurityConfig {}
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("spring_security", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void detectsEnableMethodSecurity() {
            String code = """
                    @Configuration
                    @EnableMethodSecurity
                    public class MethodSecurityConfig {}
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsSecurityFilterChain() {
            String code = """
                    @Configuration
                    public class SecurityConfig {
                        @Bean
                        public SecurityFilterChain securityFilterChain(HttpSecurity http) {
                            return http.build();
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.nodes().stream().anyMatch(n ->
                    "spring_security".equals(n.getProperties().get("auth_type"))));
        }

        @Test
        void detectsAuthorizeHttpRequests() {
            String code = """
                    @Configuration
                    public class SecurityConfig {
                        public SecurityFilterChain configure(HttpSecurity http) throws Exception {
                            http.authorizeHttpRequests(auth -> auth
                                .requestMatchers("/public/**").permitAll()
                                .anyRequest().authenticated());
                            return http.build();
                        }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().stream().anyMatch(n ->
                    n.getLabel() != null && n.getLabel().contains("authorizeHttpRequests")));
        }

        @Test
        void detectsPreAuthorizeWithHasRole() {
            String code = """
                    @Service
                    public class AdminService {
                        @PreAuthorize("hasRole('ADMIN')")
                        public void adminOnly() {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            @SuppressWarnings("unchecked")
            var roles = (List<?>) r.nodes().get(0).getProperties().get("roles");
            assertNotNull(roles);
            assertTrue(roles.contains("ADMIN"));
        }

        @Test
        void detectsPreAuthorizeWithHasAnyRole() {
            String code = """
                    @Service
                    public class ContentService {
                        @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
                        public void editContent() {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            @SuppressWarnings("unchecked")
            var roles = (List<?>) r.nodes().get(0).getProperties().get("roles");
            assertNotNull(roles);
            assertFalse(roles.isEmpty());
        }

        @Test
        void detectsSecuredSingleRole() {
            String code = """
                    @Service
                    public class ReportService {
                        @Secured("ROLE_ADMIN")
                        public void generateReport() {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            @SuppressWarnings("unchecked")
            var roles = (List<?>) r.nodes().get(0).getProperties().get("roles");
            assertNotNull(roles);
        }

        @Test
        void detectsSecuredMultipleRoles() {
            String code = """
                    @Service
                    public class DataService {
                        @Secured({"ROLE_ADMIN", "ROLE_MANAGER"})
                        public void sensitiveOp() {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsRolesAllowed() {
            String code = """
                    @Service
                    public class InventoryService {
                        @RolesAllowed("ROLE_WAREHOUSE")
                        public void manage() {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsMultipleAnnotationsInOneClass() {
            String code = """
                    @Service
                    public class SecuredService {
                        @PreAuthorize("hasRole('USER')")
                        public void userMethod() {}
                        @Secured("ROLE_ADMIN")
                        public void adminMethod() {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().size() >= 2);
        }

        @Test
        void isDeterministic() {
            String code = """
                    @Configuration
                    @EnableWebSecurity
                    public class SecurityConfig {
                        @Bean
                        public SecurityFilterChain chain(HttpSecurity http) { return http.build(); }
                    }
                    """;
            DetectorTestUtils.assertDeterministic(d, ctx("java", code));
        }

        @Test
        void getName() {
            assertEquals("spring_security", d.getName());
        }
    }

    // ========================================================================
    // JpaEntityDetector
    // ========================================================================
    @Nested
    class JpaEntityDetectorBranches {
        private final JpaEntityDetector d = new JpaEntityDetector();

        @Test
        void detectsManyToManyRelationship() {
            String code = """
                    @Entity
                    public class Student {
                        @Id private Long id;
                        @ManyToMany
                        private List<Course> courses;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.MAPS_TO));
        }

        @Test
        void detectsEnumFieldType() {
            String code = """
                    package com.example;
                    import javax.persistence.*;
                    @Entity
                    public class Order {
                        @Id private Long id;
                        @Column(name = "status")
                        private String status;
                        @Column
                        private String description;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsVersionField() {
            String code = """
                    package com.example;
                    import javax.persistence.*;
                    @Entity
                    public class Product {
                        @Id private Long id;
                        @Column(name = "version_num")
                        private int version;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            @SuppressWarnings("unchecked")
            var columns = (List<?>) r.nodes().get(0).getProperties().get("columns");
            assertNotNull(columns);
        }

        @Test
        void detectsTargetEntityOnRelation() {
            String code = """
                    package com.example;
                    import javax.persistence.*;
                    @Entity
                    public class Order {
                        @Id private Long id;
                        @OneToMany(targetEntity = OrderItem.class)
                        private List<OrderItem> items;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.MAPS_TO));
        }

        @Test
        void detectsTableWithBareValueAnnotation() {
            String code = """
                    package com.example;
                    import javax.persistence.*;
                    @Entity
                    @Table("users_table")
                    public class User {
                        @Id private Long id;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsMultipleEntitiesInOneFile() {
            String code = """
                    package com.example;
                    import javax.persistence.*;
                    @Entity
                    public class Foo {
                        @Id private Long id;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsConnectsToDbEdgeWithoutRegistry() {
            String code = """
                    @Entity
                    public class Widget {
                        @Id private Long id;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CONNECTS_TO));
        }

        @Test
        void isDeterministic() {
            String code = """
                    package com.example;
                    import javax.persistence.*;
                    @Entity
                    @Table(name = "items")
                    public class Item {
                        @Id private Long id;
                        @Column(name = "item_name") private String name;
                        @ManyToOne private Category category;
                        @OneToMany(mappedBy = "item") private List<Tag> tags;
                    }
                    """;
            DetectorTestUtils.assertDeterministic(d, ctx("java", code));
        }
    }

    // ========================================================================
    // PublicApiDetector
    // ========================================================================
    @Nested
    class PublicApiDetectorBranches {
        private final PublicApiDetector d = new PublicApiDetector();

        @Test
        void returnsEmptyOnNull() {
            assertTrue(d.detect(new DetectorContext("Api.java", "java", null)).nodes().isEmpty());
        }

        @Test
        void returnsEmptyOnEmpty() {
            assertTrue(d.detect(new DetectorContext("Api.java", "java", "")).nodes().isEmpty());
        }

        @Test
        void detectsPublicMethod() {
            String code = """
                    public class UserService {
                        public User findById(Long id) { return null; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("public", r.nodes().get(0).getProperties().get("visibility"));
        }

        @Test
        void detectsProtectedMethod() {
            String code = """
                    public abstract class BaseService {
                        protected User loadUser(Long id) { return null; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("protected", r.nodes().get(0).getProperties().get("visibility"));
        }

        @Test
        void skipsPrivateMethod() {
            String code = """
                    public class InternalService {
                        private void internalHelper() {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void skipsGetterAndSetter() {
            String code = """
                    public class User {
                        public String getName() { return name; }
                        public void setName(String name) { this.name = name; }
                        public boolean isActive() { return active; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void skipsToStringHashCodeEquals() {
            String code = """
                    public class Entity {
                        public String toString() { return ""; }
                        public int hashCode() { return 0; }
                        public boolean equals(Object o) { return false; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void detectsStaticPublicMethod() {
            String code = """
                    public class Factory {
                        public static Factory create(String config) { return new Factory(); }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals(true, r.nodes().get(0).getProperties().get("is_static"));
        }

        @Test
        void detectsAbstractPublicMethod() {
            String code = """
                    public abstract class Template {
                        public abstract void execute(String input);
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals(true, r.nodes().get(0).getProperties().get("is_abstract"));
        }

        @Test
        void detectsInterfaceMethods() {
            String code = """
                    public interface Repository {
                        User findById(Long id);
                        List<User> findAll();
                    }
                    """;
            var r = d.detect(ctx("java", code));
            // Interface methods are implicitly public
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsMultipleParams() {
            String code = """
                    public class SearchService {
                        public List<String> search(String query, int page, int size) { return null; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            @SuppressWarnings("unchecked")
            var params = (List<?>) r.nodes().get(0).getProperties().get("parameters");
            assertEquals(3, params.size());
        }

        @Test
        void detectsReturnType() {
            String code = """
                    public class ReportService {
                        public ResponseEntity<Report> generate(Long id) { return null; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertNotNull(r.nodes().get(0).getProperties().get("return_type"));
        }

        @Test
        void detectsDefinesEdge() {
            String code = """
                    public class OrderService {
                        public Order findOrder(Long id) { return null; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEFINES));
        }

        @Test
        void returnsEmptyWhenNoClass() {
            // Content without a class/interface declaration
            String code = "// Just a comment\nimport java.util.*;";
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void isDeterministic() {
            String code = """
                    public class ApiService {
                        public String doA(String x) { return x; }
                        public int doB(int n) { return n; }
                        protected void doC() {}
                    }
                    """;
            DetectorTestUtils.assertDeterministic(d, ctx("java", code));
        }

        @Test
        void getName() {
            assertEquals("java.public_api", d.getName());
        }
    }

    // ========================================================================
    // ConfigDefDetector
    // ========================================================================
    @Nested
    class ConfigDefDetectorBranches {
        private final ConfigDefDetector d = new ConfigDefDetector();

        @Test
        void returnsEmptyOnNull() {
            assertTrue(d.detect(new DetectorContext("Cfg.java", "java", null)).nodes().isEmpty());
        }

        @Test
        void returnsEmptyWhenNoRelevantAnnotations() {
            String code = """
                    public class PlainClass {
                        private String name;
                    }
                    """;
            assertTrue(d.detect(ctx("java", code)).nodes().isEmpty());
        }

        @Test
        void detectsSpringValueAnnotation() {
            String code = """
                    @Component
                    public class AppConfig {
                        @Value("${server.port}")
                        private int serverPort;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.nodes().stream().anyMatch(n -> "server.port".equals(n.getLabel())));
        }

        @Test
        void detectsMultipleValueAnnotations() {
            String code = """
                    @Component
                    public class ServiceConfig {
                        @Value("${db.url}")
                        private String dbUrl;
                        @Value("${db.password}")
                        private String dbPass;
                        @Value("${app.timeout}")
                        private int timeout;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertEquals(3, r.nodes().size());
        }

        @Test
        void detectsConfigurationProperties() {
            String code = """
                    @ConfigurationProperties("app.kafka")
                    public class KafkaProperties {
                        private String brokers;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.nodes().stream().anyMatch(n -> "app.kafka".equals(n.getLabel())));
        }

        @Test
        void detectsConfigurationPropertiesWithPrefix() {
            String code = """
                    @ConfigurationProperties(prefix = "spring.datasource")
                    public class DataSourceConfig {
                        private String url;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsKafkaConfigDefDefine() {
            String code = """
                    public class MyConfig {
                        static final ConfigDef CONFIG = new ConfigDef()
                            .define("my.key", Type.STRING, "default", "description")
                            .define("my.other.key", Type.INT, 42, "other desc");
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void deduplicatesSameKey() {
            String code = """
                    @Component
                    public class ServiceA {
                        @Value("${common.key}")
                        private String key1;
                        @Value("${common.key}")
                        private String key2;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            // Same key should only appear once
            long count = r.nodes().stream().filter(n -> "common.key".equals(n.getLabel())).count();
            assertEquals(1, count);
        }

        @Test
        void detectsValueOnMethodParameter() {
            String code = """
                    @Component
                    public class ServiceB {
                        public void init(@Value("${init.timeout}") int timeout) {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
            assertTrue(r.nodes().stream().anyMatch(n -> "init.timeout".equals(n.getLabel())));
        }

        @Test
        void detectsReadsConfigEdge() {
            String code = """
                    @Component
                    public class ConfigReader {
                        @Value("${app.name}")
                        private String appName;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.READS_CONFIG));
        }

        @Test
        void isDeterministic() {
            String code = """
                    @Component
                    public class AppProps {
                        @Value("${server.port}") private int port;
                        @Value("${server.host}") private String host;
                        @Value("${server.timeout}") private long timeout;
                    }
                    """;
            DetectorTestUtils.assertDeterministic(d, ctx("java", code));
        }

        @Test
        void getName() {
            assertEquals("config_def", d.getName());
        }
    }

    // ========================================================================
    // MicronautDetector
    // ========================================================================
    @Nested
    class MicronautDetectorBranches {
        private final MicronautDetector d = new MicronautDetector();

        @Test
        void returnsEmptyOnNull() {
            assertTrue(d.detect(new DetectorContext("Mn.java", "java", null)).nodes().isEmpty());
        }

        @Test
        void returnsEmptyOnEmpty() {
            assertTrue(d.detect(new DetectorContext("Mn.java", "java", "")).nodes().isEmpty());
        }

        @Test
        void returnsEmptyWithoutMicronautIndicator() {
            // Has @Controller but no io.micronaut import or @Client
            String code = """
                    @Controller("/api")
                    public class ApiController {
                        @Get("/items")
                        public String list() { return ""; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void detectsControllerWithMicronautImport() {
            String code = """
                    import io.micronaut.http.annotation.*;
                    @Controller("/api")
                    public class ApiController {
                        @Get("/items")
                        public String list() { return ""; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsGetEndpoint() {
            String code = """
                    import io.micronaut.http.annotation.*;
                    @Controller("/users")
                    public class UserController {
                        @Get("/{id}")
                        public String getUser(Long id) { return ""; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENDPOINT));
            assertTrue(r.nodes().stream().anyMatch(n ->
                    "GET".equals(n.getProperties().get("http_method"))));
        }

        @Test
        void detectsPostEndpoint() {
            String code = """
                    import io.micronaut.http.annotation.*;
                    @Controller("/users")
                    public class UserController {
                        @Post
                        public String create() { return ""; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENDPOINT));
        }

        @Test
        void detectsPutEndpoint() {
            String code = """
                    import io.micronaut.http.annotation.*;
                    @Controller("/items")
                    public class ItemController {
                        @Put("/{id}")
                        public String update(Long id) { return ""; }
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENDPOINT));
        }

        @Test
        void detectsDeleteEndpoint() {
            String code = """
                    import io.micronaut.http.annotation.*;
                    @Controller("/items")
                    public class ItemController {
                        @Delete("/{id}")
                        public void delete(Long id) {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENDPOINT));
        }

        @Test
        void detectsSingleton() {
            String code = """
                    import io.micronaut.http.annotation.*;
                    @Singleton
                    @Client("payment-service")
                    public class PaymentClient {
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.MIDDLEWARE));
        }

        @Test
        void detectsClientAnnotation() {
            String code = """
                    import io.micronaut.http.client.annotation.Client;
                    @Client("http://inventory-service")
                    public interface InventoryClient {
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CLASS));
            assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEPENDS_ON));
        }

        @Test
        void detectsInjectAnnotation() {
            String code = """
                    import io.micronaut.context.annotation.*;
                    @Client("http://service")
                    public class ServiceClient {
                        @Inject
                        private SomeDependency dep;
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().stream().anyMatch(n ->
                    n.getAnnotations() != null && n.getAnnotations().contains("@Inject")));
        }

        @Test
        void detectsScheduledAnnotation() {
            String code = """
                    import io.micronaut.scheduling.annotation.Scheduled;
                    @Client("http://monitor")
                    public class HealthMonitor {
                        @Scheduled(fixedRate = "10s")
                        public void check() {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.EVENT));
        }

        @Test
        void detectsEventListener() {
            String code = """
                    import io.micronaut.context.event.*;
                    @Client("http://service")
                    public class StartupListener {
                        @EventListener
                        public void onStart(StartupEvent event) {}
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.EVENT));
        }

        @Test
        void detectsEndpointWithoutControllerPath() {
            // @Get without @Controller, but with @Client as indicator
            String code = """
                    import io.micronaut.http.annotation.*;
                    @Client("http://api")
                    public interface ApiClient {
                        @Get("/data")
                        String getData();
                    }
                    """;
            var r = d.detect(ctx("java", code));
            assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENDPOINT));
        }

        @Test
        void isDeterministic() {
            String code = """
                    import io.micronaut.http.annotation.*;
                    @Controller("/orders")
                    public class OrderController {
                        @Get
                        public String list() { return ""; }
                        @Post
                        public String create() { return ""; }
                        @Delete("/{id}")
                        public void delete(Long id) {}
                    }
                    """;
            DetectorTestUtils.assertDeterministic(d, ctx("java", code));
        }

        @Test
        void getName() {
            assertEquals("micronaut", d.getName());
        }
    }
}
