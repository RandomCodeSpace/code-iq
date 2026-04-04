package io.github.randomcodespace.iq.detector.java;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended branch-coverage tests for JpaEntityDetector targeting code paths
 * not covered by the existing JavaDetectors*Test suites.
 */
class JpaEntityDetectorExtendedTest {

    private final JpaEntityDetector detector = new JpaEntityDetector();

    private static DetectorContext ctx(String content) {
        return DetectorTestUtils.contextFor(
                "src/main/java/com/example/domain/User.java", "java", content);
    }

    // ---- @Entity with @Table(name = "...") ------------------------------------------

    @Test
    void detectsEntityWithTableName() {
        String code = """
                package com.example;
                import javax.persistence.*;
                @Entity
                @Table(name = "users")
                public class User {
                    @Id private Long id;
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        var entity = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertEquals("users", entity.getProperties().get("table_name"));
        assertTrue(entity.getLabel().contains("users"));
    }

    @Test
    void detectsEntityWithTableNameAttribute() {
        String code = """
                package com.example;
                import javax.persistence.*;
                @Entity
                @Table(name = "product_catalog")
                public class Product {
                    @Id private Long id;
                    @Column(name = "product_name") private String name;
                    @Column(name = "unit_price") private Double price;
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertEquals("product_catalog", result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY)
                .findFirst().orElseThrow()
                .getProperties().get("table_name"));
    }

    // ---- @Embeddable ----------------------------------------------------------------

    @Test
    void embeddableClassNotDetectedAsEntity() {
        // @Embeddable does not have @Entity, so the detector should return empty
        // (JpaEntityDetector.detect() checks for @Entity in the content first)
        String code = """
                package com.example;
                import javax.persistence.*;
                @Embeddable
                public class Address {
                    private String street;
                    private String city;
                }
                """;
        var result = detector.detect(ctx(code));
        // @Embeddable without @Entity → detector short-circuits
        assertTrue(result.nodes().isEmpty(),
                "@Embeddable without @Entity should return empty (no @Entity in content)");
    }

    // ---- @MappedSuperclass ----------------------------------------------------------

    @Test
    void mappedSuperclassWithoutEntityAnnotationReturnsEmpty() {
        String code = """
                package com.example;
                import javax.persistence.*;
                @MappedSuperclass
                public abstract class BaseEntity {
                    @Id private Long id;
                }
                """;
        var result = detector.detect(ctx(code));
        // No @Entity annotation → detector returns empty
        assertTrue(result.nodes().isEmpty(),
                "@MappedSuperclass without @Entity should return empty");
    }

    @Test
    void entityExtendingMappedSuperclassIsDetected() {
        String code = """
                package com.example;
                import javax.persistence.*;
                @MappedSuperclass
                public abstract class BaseEntity {
                    @Id private Long id;
                }
                @Entity
                @Table(name = "orders")
                public class Order extends BaseEntity {
                    @Column(name = "order_ref") private String ref;
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        var entity = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertEquals("orders", entity.getProperties().get("table_name"));
    }

    // ---- @Id and @GeneratedValue ----------------------------------------------------

    @Test
    void detectsEntityWithIdAndGeneratedValue() {
        String code = """
                package com.example;
                import javax.persistence.*;
                @Entity
                public class Customer {
                    @Id
                    @GeneratedValue(strategy = GenerationType.IDENTITY)
                    private Long id;
                    @Column(name = "full_name") private String name;
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        var entity = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        var columns = (List<?>) entity.getProperties().get("columns");
        assertNotNull(columns, "Entity with @Id and @Column should have columns property");
        assertFalse(columns.isEmpty());
    }

    // ---- @OneToMany ----------------------------------------------------------------

    @Test
    void detectsEntityWithOneToMany() {
        String code = """
                package com.example;
                import javax.persistence.*;
                import java.util.List;
                @Entity
                public class Department {
                    @Id private Long id;
                    @OneToMany(mappedBy = "department")
                    private List<Employee> employees;
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.MAPS_TO));
        // mappedBy should be on the edge properties
        var mapsToEdge = result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.MAPS_TO).findFirst().orElseThrow();
        assertEquals("one_to_many", mapsToEdge.getProperties().get("relationship_type"));
        assertEquals("department", mapsToEdge.getProperties().get("mapped_by"));
    }

    // ---- @ManyToOne ----------------------------------------------------------------

    @Test
    void detectsEntityWithManyToOne() {
        String code = """
                package com.example;
                import javax.persistence.*;
                @Entity
                public class Employee {
                    @Id private Long id;
                    @ManyToOne
                    private Department department;
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.MAPS_TO));
        var mapsToEdge = result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.MAPS_TO).findFirst().orElseThrow();
        assertEquals("many_to_one", mapsToEdge.getProperties().get("relationship_type"));
    }

    // ---- @ManyToMany ---------------------------------------------------------------

    @Test
    void detectsEntityWithManyToMany() {
        String code = """
                package com.example;
                import javax.persistence.*;
                import java.util.Set;
                @Entity
                public class Student {
                    @Id private Long id;
                    @ManyToMany
                    private Set<Course> courses;
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.MAPS_TO));
        var mapsToEdge = result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.MAPS_TO).findFirst().orElseThrow();
        assertEquals("many_to_many", mapsToEdge.getProperties().get("relationship_type"));
    }

    // ---- @OneToOne -----------------------------------------------------------------

    @Test
    void detectsEntityWithOneToOne() {
        String code = """
                package com.example;
                import javax.persistence.*;
                @Entity
                public class UserProfile {
                    @Id private Long id;
                    @OneToOne
                    private UserAccount account;
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.MAPS_TO));
        assertEquals("one_to_one", result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.MAPS_TO).findFirst().orElseThrow()
                .getProperties().get("relationship_type"));
    }

    // ---- @Column attributes --------------------------------------------------------

    @Test
    void detectsColumnWithNullableAndUniqueAttributes() {
        String code = """
                package com.example;
                import javax.persistence.*;
                @Entity
                public class Product {
                    @Id private Long id;
                    @Column(nullable = false, unique = true, name = "sku")
                    private String sku;
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        @SuppressWarnings("unchecked")
        var columns = (List<?>) result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow()
                .getProperties().get("columns");
        assertNotNull(columns);
        assertFalse(columns.isEmpty());
    }

    // ---- @NamedQuery ---------------------------------------------------------------

    @Test
    void detectsEntityWithNamedQuery() {
        String code = """
                package com.example;
                import javax.persistence.*;
                @Entity
                @NamedQuery(name = "User.findByEmail", query = "SELECT u FROM User u WHERE u.email = :email")
                public class UserEntity {
                    @Id private Long id;
                    @Column(name = "email") private String email;
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertEquals(NodeKind.ENTITY, result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow().getKind());
    }

    // ---- @Inheritance annotation ---------------------------------------------------

    @Test
    void detectsEntityWithInheritanceAnnotation() {
        String code = """
                package com.example;
                import javax.persistence.*;
                @Entity
                @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
                @DiscriminatorColumn(name = "vehicle_type")
                public abstract class Vehicle {
                    @Id private Long id;
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertEquals(NodeKind.ENTITY, result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow().getKind());
    }

    // ---- Non-entity class → empty --------------------------------------------------

    @Test
    void nonEntityClassReturnsEmpty() {
        String code = """
                package com.example;
                public class PlainPojo {
                    private String name;
                    public String getName() { return name; }
                }
                """;
        var result = detector.detect(ctx(code));
        assertTrue(result.nodes().isEmpty(),
                "Plain POJO without @Entity should return empty");
    }

    @Test
    void serviceClassWithoutEntityAnnotationReturnsEmpty() {
        String code = """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class UserService {
                    public void save() {}
                }
                """;
        var result = detector.detect(ctx(code));
        assertTrue(result.nodes().isEmpty(),
                "@Service class without @Entity should return empty");
    }

    // ---- CONNECTS_TO database edge -------------------------------------------------

    @Test
    void createsConnectsToDbEdge() {
        String code = """
                package com.example;
                import javax.persistence.*;
                @Entity
                public class Order {
                    @Id private Long id;
                }
                """;
        var result = detector.detect(ctx(code));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CONNECTS_TO),
                "Entity should have a CONNECTS_TO database edge");
    }

    // ---- @Entity annotation is in annotations list ---------------------------------

    @Test
    void entityNodeHasEntityAnnotation() {
        String code = """
                package com.example;
                import javax.persistence.*;
                @Entity
                public class Invoice {
                    @Id private Long id;
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        var entity = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertTrue(entity.getAnnotations().contains("@Entity"),
                "Entity node should have @Entity in its annotations list");
    }

    // ---- Multiple entity classes in same file --------------------------------------

    @Test
    void detectsOnlyEntityAnnotatedClassesInMixedFile() {
        String code = """
                package com.example;
                import javax.persistence.*;
                @Entity
                @Table(name = "orders")
                public class Order {
                    @Id private Long id;
                }
                public class OrderDto {
                    private Long id;
                    private String status;
                }
                """;
        var result = detector.detect(ctx(code));
        // Only Order (with @Entity) should be detected
        long entityCount = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).count();
        assertEquals(1, entityCount, "Only @Entity annotated class should be detected");
    }

    // ---- targetEntity attribute on @OneToMany --------------------------------------

    @Test
    void detectsTargetEntityClassReference() {
        String code = """
                package com.example;
                import javax.persistence.*;
                import java.util.List;
                @Entity
                public class Cart {
                    @Id private Long id;
                    @OneToMany(targetEntity = CartItem.class)
                    private List items;
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.MAPS_TO));
    }

    // ---- Determinism ---------------------------------------------------------------

    @Test
    void isDeterministic() {
        String code = """
                package com.example;
                import javax.persistence.*;
                import java.util.List;
                import java.util.Set;
                @Entity
                @Table(name = "catalog_items")
                public class CatalogItem {
                    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
                    private Long id;
                    @Column(name = "item_code", nullable = false, unique = true)
                    private String code;
                    @Column(name = "display_name") private String name;
                    @ManyToOne
                    private Category category;
                    @OneToMany(mappedBy = "catalogItem")
                    private List<Review> reviews;
                }
                """;
        DetectorTestUtils.assertDeterministic(detector, ctx(code));
    }

    // ---- Regex fallback (NUL byte forces JavaParser failure) ------------------------

    @Test
    void regexFallback_detectsEntityClass() {
        String code = "@Entity\n"
                + "\u0000 class Subscription {\n"
                + "    private Long id;\n"
                + "    private String name;\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect @Entity class");
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENTITY));
    }

    @Test
    void regexFallback_detectsTableAnnotation() {
        String code = "@Entity\n"
                + "@Table(name = \"subscriptions\")\n"
                + "\u0000 class Subscription {\n"
                + "    private Long id;\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect @Table name");
        var entity = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertEquals("subscriptions", entity.getProperties().get("table_name"),
                "table_name should be extracted from @Table in regex fallback");
    }

    @Test
    void regexFallback_detectsColumnAnnotation() {
        String code = "@Entity\n"
                + "\u0000 class Payment {\n"
                + "    private Long id;\n"
                + "    @Column(name = \"amount\")\n"
                + "    private Double amount;\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        var entity = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        var columns = (List<?>) entity.getProperties().get("columns");
        assertNotNull(columns, "regex fallback should extract @Column annotations");
        assertFalse(columns.isEmpty());
    }

    @Test
    void regexFallback_detectsManyToOneRelationship() {
        String code = "@Entity\n"
                + "\u0000 class Invoice {\n"
                + "    private Long id;\n"
                + "    @ManyToOne\n"
                + "    private Customer customer;\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.MAPS_TO),
                "regex fallback should create MAPS_TO edge for @ManyToOne");
        var mapsTo = result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.MAPS_TO).findFirst().orElseThrow();
        assertEquals("many_to_one", mapsTo.getProperties().get("relationship_type"));
    }

    @Test
    void regexFallback_detectsOneToManyWithMappedBy() {
        // Use a type name without dots so FIELD_RE [\w<>,\s]+ can match
        String code = "@Entity\n"
                + "\u0000 class Project {\n"
                + "    private Long id;\n"
                + "    @OneToMany(mappedBy = \"project\")\n"
                + "    private List tasks;\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.MAPS_TO),
                "regex fallback should create MAPS_TO edge for @OneToMany");
    }

    @Test
    void regexFallback_detectsManyToManyWithGenericType() {
        // Use a simple generic type so FIELD_RE and GENERIC_TYPE_RE can match
        String code = "@Entity\n"
                + "\u0000 class Course {\n"
                + "    private Long id;\n"
                + "    @ManyToMany\n"
                + "    private Set<Student> students;\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.MAPS_TO),
                "regex fallback should resolve generic type argument as target entity");
    }

    @Test
    void regexFallback_detectsConnectsToDbEdge() {
        String code = "@Entity\n"
                + "\u0000 class Ledger {\n"
                + "    private Long id;\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CONNECTS_TO),
                "regex fallback should also emit a CONNECTS_TO database edge");
    }

    @Test
    void regexFallback_noEntityAnnotation_returnsEmpty() {
        String code = "\u0000 class NotAnEntity {\n"
                + "    private Long id;\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertTrue(result.nodes().isEmpty(),
                "Without @Entity in content the detector short-circuits to empty");
    }

    @Test
    void regexFallback_defaultTableNameIsClassNameLowercase() {
        String code = "@Entity\n"
                + "\u0000 class AccountEntry {\n"
                + "    private Long id;\n"
                + "}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        var entity = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertEquals("accountentry", entity.getProperties().get("table_name"),
                "Default table name should be lowercase class name");
    }
}
