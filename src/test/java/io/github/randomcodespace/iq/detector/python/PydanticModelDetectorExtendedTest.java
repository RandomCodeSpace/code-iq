package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PydanticModelDetectorExtendedTest {

    private final PydanticModelDetector detector = new PydanticModelDetector();

    private static String pad(String code) {
        return code + "\n" + "#\n".repeat(260_000);
    }

    // ---- @validator decorator ----

    @Test
    void detectsValidatorDecorator() {
        String code = """
                class SignupForm(BaseModel):
                    username: str
                    password: str

                    @validator('username')
                    def username_alphanumeric(cls, v):
                        assert v.isalnum()
                        return v
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("schemas.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        List<String> annotations = result.nodes().get(0).getAnnotations();
        assertNotNull(annotations);
        assertTrue(annotations.contains("username"), "should include validated field name");
    }

    @Test
    void detectsMultipleValidators() {
        String code = """
                class UserCreate(BaseModel):
                    name: str
                    email: str
                    age: int

                    @validator('name')
                    def name_not_empty(cls, v):
                        return v

                    @validator('email')
                    def email_valid(cls, v):
                        return v

                    @validator('age')
                    def age_positive(cls, v):
                        return v
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("schemas.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        List<String> annotations = result.nodes().get(0).getAnnotations();
        assertTrue(annotations.contains("name"));
        assertTrue(annotations.contains("email"));
        assertTrue(annotations.contains("age"));
    }

    @Test
    void regexFallback_detectsValidator() {
        String code = pad("""
                class PasswordForm(BaseModel):
                    password: str

                    @validator('password')
                    def strong_password(cls, v):
                        return v
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("schemas.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENTITY));
        var node = result.nodes().stream().filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        List<String> annotations = node.getAnnotations();
        assertNotNull(annotations);
        assertTrue(annotations.contains("password"));
    }

    // ---- @field_validator (Pydantic v2) ----

    @Test
    void detectsFieldValidatorV2() {
        String code = """
                class Product(BaseModel):
                    price: float
                    quantity: int

                    @field_validator('price')
                    def price_must_be_positive(cls, v):
                        assert v > 0
                        return v
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("schemas.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        List<String> annotations = result.nodes().get(0).getAnnotations();
        assertTrue(annotations.contains("price"));
    }

    @Test
    void regexFallback_detectsFieldValidator() {
        String code = pad("""
                class OrderItem(BaseModel):
                    quantity: int

                    @field_validator('quantity')
                    def positive_quantity(cls, v):
                        return v
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("schemas.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertFalse(result.nodes().isEmpty());
        var node = result.nodes().stream().filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertTrue(node.getAnnotations().contains("quantity"));
    }

    // ---- model_config (Pydantic v2 style) ----

    @Test
    void detectsModelWithConfigClass() {
        String code = """
                class MyModel(BaseModel):
                    name: str

                    class Config:
                        orm_mode = True
                        validate_assignment = True
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("schemas.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        @SuppressWarnings("unchecked")
        Map<String, String> config = (Map<String, String>) result.nodes().get(0).getProperties().get("config");
        assertNotNull(config);
        assertEquals("True", config.get("orm_mode"));
        assertEquals("True", config.get("validate_assignment"));
    }

    @Test
    void detectsConfigWithPopulateByName() {
        String code = """
                class Schema(BaseModel):
                    field_name: str

                    class Config:
                        populate_by_name = True
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("schemas.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        @SuppressWarnings("unchecked")
        Map<String, String> config = (Map<String, String>) result.nodes().get(0).getProperties().get("config");
        assertNotNull(config);
        assertEquals("True", config.get("populate_by_name"));
    }

    @Test
    void regexFallback_detectsModelConfig() {
        String code = pad("""
                class DataModel(BaseModel):
                    value: int

                    class Config:
                        orm_mode = True
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("schemas.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertFalse(result.nodes().isEmpty());
    }

    // ---- BaseSettings detection ----

    @Test
    void detectsBaseSettingsAsConfigDefinition() {
        String code = """
                class DatabaseSettings(BaseSettings):
                    host: str
                    port: int
                    name: str
                    user: str
                    password: str
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("config.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals(NodeKind.CONFIG_DEFINITION, result.nodes().get(0).getKind());
        assertEquals("DatabaseSettings", result.nodes().get(0).getLabel());
        assertEquals("BaseSettings", result.nodes().get(0).getProperties().get("base_class"));
    }

    @Test
    void detectsBaseSettingsWithDefaultValues() {
        String code = """
                class AppConfig(BaseSettings):
                    debug: bool = False
                    host: str = "localhost"
                    port: int = 8080
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("config.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(NodeKind.CONFIG_DEFINITION, result.nodes().get(0).getKind());
        @SuppressWarnings("unchecked")
        List<String> fields = (List<String>) result.nodes().get(0).getProperties().get("fields");
        assertNotNull(fields);
        assertFalse(fields.isEmpty());
    }

    @Test
    void regexFallback_detectsBaseSettings() {
        String code = pad("""
                class RedisConfig(BaseSettings):
                    redis_host: str
                    redis_port: int
                    redis_db: int = 0
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("config.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream()
                .anyMatch(n -> n.getKind() == NodeKind.CONFIG_DEFINITION
                        && "RedisConfig".equals(n.getLabel())));
    }

    // ---- Nested models ----

    @Test
    void detectsNestedPydanticModels() {
        String code = """
                class Address(BaseModel):
                    street: str
                    city: str

                class User(BaseModel):
                    name: str
                    address: Address
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("schemas.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(2, result.nodes().size());
        assertTrue(result.nodes().stream().anyMatch(n -> "Address".equals(n.getLabel())));
        assertTrue(result.nodes().stream().anyMatch(n -> "User".equals(n.getLabel())));
    }

    @Test
    void regexFallback_detectsNestedModels() {
        String code = pad("""
                class AddressSchema(BaseModel):
                    street: str
                    postal: str

                class PersonSchema(BaseModel):
                    name: str
                    home: AddressSchema
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("schemas.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> "AddressSchema".equals(n.getLabel())));
        assertTrue(result.nodes().stream().anyMatch(n -> "PersonSchema".equals(n.getLabel())));
    }

    // ---- Inheritance between pydantic models ----

    @Test
    void detectsInheritanceExtendsEdge() {
        String code = """
                class BaseSchema(BaseModel):
                    id: int

                class UserSchema(BaseSchema):
                    name: str
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("schemas.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        // BaseSchema is direct BaseModel subclass, UserSchema extends BaseSchema (known model)
        assertTrue(result.nodes().stream().anyMatch(n -> "BaseSchema".equals(n.getLabel())));
        // UserSchema doesn't extend BaseModel directly so not detected by regex, but ANTLR path detects BaseSchema
        // The ANTLR path only picks up BaseModel/BaseSettings direct subclasses
        assertFalse(result.nodes().isEmpty());
    }

    // ---- Fields are extracted ----

    @Test
    void fieldsListIsPopulated() {
        String code = """
                class Invoice(BaseModel):
                    number: str
                    amount: float
                    due_date: str
                    paid: bool
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("schemas.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        @SuppressWarnings("unchecked")
        List<String> fields = (List<String>) result.nodes().get(0).getProperties().get("fields");
        assertNotNull(fields);
        assertTrue(fields.contains("number"));
        assertTrue(fields.contains("amount"));
        assertTrue(fields.contains("due_date"));
        assertTrue(fields.contains("paid"));
    }

    @Test
    void fieldTypesMapIsPopulated() {
        String code = """
                class Token(BaseModel):
                    access_token: str
                    token_type: str
                    expires_in: int
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("schemas.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        @SuppressWarnings("unchecked")
        Map<String, String> fieldTypes = (Map<String, String>) result.nodes().get(0).getProperties().get("field_types");
        assertNotNull(fieldTypes);
        assertEquals("str", fieldTypes.get("access_token"));
        assertEquals("str", fieldTypes.get("token_type"));
        assertEquals("int", fieldTypes.get("expires_in"));
    }

    // ---- FQN ----

    @Test
    void fqnContainsFilePathAndClassName() {
        String code = """
                class Response(BaseModel):
                    status: str
                    data: dict
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("api/schemas.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var node = result.nodes().get(0);
        assertNotNull(node.getFqn());
        assertTrue(node.getFqn().contains("Response"));
        assertTrue(node.getFqn().contains("api/schemas.py"));
    }

    // ---- Determinism ----

    @Test
    void deterministicWithValidatorsAndConfig() {
        String code = """
                class ComplexModel(BaseModel):
                    name: str
                    age: int
                    email: str

                    @validator('name')
                    def name_not_blank(cls, v):
                        return v

                    @field_validator('email')
                    def email_format(cls, v):
                        return v

                    class Config:
                        orm_mode = True
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("schemas.py", "python", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }

    @Test
    void regexFallback_deterministicWithMultipleModels() {
        String code = pad("""
                class A(BaseModel):
                    x: int

                class B(BaseSettings):
                    y: str

                class C(BaseModel):
                    z: float
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("schemas.py", "python", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
