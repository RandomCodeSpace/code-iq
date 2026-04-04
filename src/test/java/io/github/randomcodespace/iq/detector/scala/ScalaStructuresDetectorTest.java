package io.github.randomcodespace.iq.detector.scala;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScalaStructuresDetectorTest {

    private final ScalaStructuresDetector d = new ScalaStructuresDetector();

    @Test
    void detectsClass() {
        String code = "class User(val name: String, val age: Int)\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CLASS && "User".equals(n.getLabel())));
    }

    @Test
    void detectsCaseClass() {
        String code = "case class Event(id: Long, name: String)\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CLASS && "Event".equals(n.getLabel())));
    }

    @Test
    void detectsClassWithExtends() {
        String code = "class AdminUser extends User\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CLASS));
        assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.EXTENDS));
    }

    @Test
    void detectsClassWithWith() {
        String code = "class Service extends Actor with Serializable with Logging\n";
        DetectorResult r = d.detect(ctx(code));
        // has EXTENDS for Actor and IMPLEMENTS for Serializable, Logging
        assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.EXTENDS));
        assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.IMPLEMENTS));
    }

    @Test
    void detectsTrait() {
        String code = "trait Serializable {\n  def serialize(): String\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.INTERFACE && "Serializable".equals(n.getLabel())));
    }

    @Test
    void detectsObject() {
        String code = "object UserFactory {\n  def create() = new User()\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> "UserFactory".equals(n.getLabel())));
        var obj = r.nodes().stream().filter(n -> "UserFactory".equals(n.getLabel())).findFirst().orElseThrow();
        assertEquals("object", obj.getProperties().get("type"));
    }

    @Test
    void detectsDef() {
        String code = "def processRequest(req: Request): Response = ???\ndef validate(input: String): Boolean = input.nonEmpty\n";
        DetectorResult r = d.detect(ctx(code));
        assertEquals(2, r.nodes().stream().filter(n -> n.getKind() == NodeKind.METHOD).count());
    }

    @Test
    void detectsImport() {
        String code = "import scala.collection.mutable.ListBuffer\nimport akka.actor.ActorSystem\n";
        DetectorResult r = d.detect(ctx(code));
        assertEquals(2, r.edges().stream().filter(e -> e.getKind() == EdgeKind.IMPORTS).count());
    }

    @Test
    void detectsClassAndTrait() {
        DetectorResult r = d.detect(ctx("class User extends Entity\ntrait Serializable\ndef process(x: Int) = x"));
        assertTrue(r.nodes().size() >= 3);
    }

    @Test
    void emptyContentReturnsEmpty() {
        DetectorResult r = d.detect(ctx(""));
        assertTrue(r.nodes().isEmpty());
        assertTrue(r.edges().isEmpty());
    }

    @Test
    void nullContentReturnsEmpty() {
        DetectorContext ctxNull = new DetectorContext("test.scala", "scala", null);
        DetectorResult r = d.detect(ctxNull);
        assertTrue(r.nodes().isEmpty());
    }

    @Test
    void returnsCorrectName() {
        assertEquals("scala_structures", d.getName());
    }

    @Test
    void supportedLanguagesContainsScala() {
        assertTrue(d.getSupportedLanguages().contains("scala"));
    }

    @Test
    void deterministic() {
        String code = """
                import scala.collection.mutable.ListBuffer
                import akka.actor.Actor
                case class User(id: Long, name: String)
                class UserService extends Actor with Serializable {
                  def receive = ???
                }
                trait Repository[T] {
                  def findAll(): List[T]
                  def save(entity: T): Unit
                }
                object Main {
                  def main(args: Array[String]): Unit = {}
                }
                """;
        DetectorTestUtils.assertDeterministic(d, ctx(code));
    }

    private static DetectorContext ctx(String content) {
        return DetectorTestUtils.contextFor("scala", content);
    }
}
