"""Tests for low-coverage Java detectors: GraphQL resolver, JMS, RabbitMQ, RMI."""

from osscodeiq.detectors.base import DetectorContext, DetectorResult
from osscodeiq.detectors.java.graphql_resolver import GraphqlResolverDetector
from osscodeiq.detectors.java.jms import JmsDetector
from osscodeiq.detectors.java.rabbitmq import RabbitmqDetector
from osscodeiq.detectors.java.rmi import RmiDetector
from osscodeiq.models.graph import NodeKind, EdgeKind


def _ctx(content: str, path: str = "Test.java"):
    return DetectorContext(
        file_path=path, language="java", content=content.encode(), module_name="test",
    )


# ===========================================================================
# GraphQL Resolver Detector
# ===========================================================================

class TestGraphqlResolverDetector:
    def setup_method(self):
        self.detector = GraphqlResolverDetector()

    def test_no_graphql_annotations(self):
        result = self.detector.detect(_ctx("public class Foo { }"))
        assert len(result.nodes) == 0

    def test_query_mapping(self):
        src = """\
@Controller
public class BookController {

    @QueryMapping
    public Book bookById(String id) {
        return service.findById(id);
    }
}
"""
        result = self.detector.detect(_ctx(src))
        endpoints = [n for n in result.nodes if n.kind == NodeKind.ENDPOINT]
        assert len(endpoints) == 1
        assert endpoints[0].properties["graphql_type"] == "Query"
        assert endpoints[0].properties["field"] == "bookById"

    def test_mutation_mapping_with_name(self):
        src = """\
@Controller
public class BookController {

    @MutationMapping(name = "addBook")
    public Book createBook(BookInput input) {
        return service.save(input);
    }
}
"""
        result = self.detector.detect(_ctx(src))
        endpoints = [n for n in result.nodes if n.kind == NodeKind.ENDPOINT]
        assert len(endpoints) == 1
        assert endpoints[0].properties["field"] == "addBook"
        assert endpoints[0].properties["graphql_type"] == "Mutation"

    def test_subscription_mapping(self):
        src = """\
@Controller
public class NotificationController {

    @SubscriptionMapping
    public Flux<Notification> notifications() {
        return notificationService.stream();
    }
}
"""
        result = self.detector.detect(_ctx(src))
        endpoints = [n for n in result.nodes if n.kind == NodeKind.ENDPOINT]
        assert len(endpoints) == 1
        assert endpoints[0].properties["graphql_type"] == "Subscription"

    def test_schema_mapping(self):
        src = """\
@Controller
public class AuthorController {

    @SchemaMapping(typeName = "Book")
    public Author author(Book book) {
        return authorService.findByBookId(book.getId());
    }
}
"""
        result = self.detector.detect(_ctx(src))
        endpoints = [n for n in result.nodes if n.kind == NodeKind.ENDPOINT]
        assert len(endpoints) == 1
        assert "Book" in endpoints[0].label

    def test_dgs_query(self):
        src = """\
@DgsComponent
public class ShowDatafetcher {

    @DgsQuery(field = "shows")
    public List<Show> shows() {
        return showService.findAll();
    }
}
"""
        result = self.detector.detect(_ctx(src))
        endpoints = [n for n in result.nodes if n.kind == NodeKind.ENDPOINT]
        assert len(endpoints) == 1
        assert endpoints[0].properties["field"] == "shows"

    def test_dgs_data(self):
        src = """\
@DgsComponent
public class ReviewDatafetcher {

    @DgsData(parentType = "Show", field = "reviews")
    public List<Review> reviews(DgsDataFetchingEnvironment env) {
        return reviewService.forShow(env);
    }
}
"""
        result = self.detector.detect(_ctx(src))
        endpoints = [n for n in result.nodes if n.kind == NodeKind.ENDPOINT]
        assert len(endpoints) == 1
        assert endpoints[0].properties["graphql_type"] == "Show"
        assert endpoints[0].properties["framework"] == "dgs"

    def test_edges_link_to_class(self):
        src = """\
@Controller
public class BookController {

    @QueryMapping
    public Book book() { return null; }
}
"""
        result = self.detector.detect(_ctx(src))
        exposes = [e for e in result.edges if e.kind == EdgeKind.EXPOSES]
        assert len(exposes) == 1
        assert exposes[0].source == "Test.java:BookController"

    def test_determinism(self):
        src = """\
@Controller
public class Ctrl {
    @QueryMapping
    public String foo() { return ""; }
    @MutationMapping
    public String bar() { return ""; }
}
"""
        r1 = self.detector.detect(_ctx(src))
        r2 = self.detector.detect(_ctx(src))
        assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]


# ===========================================================================
# JMS Detector
# ===========================================================================

class TestJmsDetector:
    def setup_method(self):
        self.detector = JmsDetector()

    def test_no_jms(self):
        result = self.detector.detect(_ctx("public class Foo { }"))
        assert len(result.nodes) == 0

    def test_jms_listener(self):
        src = """\
public class OrderConsumer {

    @JmsListener(destination = "order-queue")
    public void receive(String msg) { }
}
"""
        result = self.detector.detect(_ctx(src))
        queues = [n for n in result.nodes if n.kind == NodeKind.QUEUE]
        assert len(queues) == 1
        assert queues[0].properties["destination"] == "order-queue"
        consumes = [e for e in result.edges if e.kind == EdgeKind.CONSUMES]
        assert len(consumes) == 1

    def test_jms_template_send(self):
        src = """\
public class OrderProducer {

    public void send() {
        jmsTemplate.convertAndSend("order-queue", "msg");
    }
}
"""
        result = self.detector.detect(_ctx(src))
        queues = [n for n in result.nodes if n.kind == NodeKind.QUEUE]
        assert len(queues) == 1
        produces = [e for e in result.edges if e.kind == EdgeKind.PRODUCES]
        assert len(produces) == 1

    def test_jms_listener_with_container_factory(self):
        src = """\
public class Listener {

    @JmsListener(destination = "events", containerFactory = "myFactory")
    public void handle(String msg) { }
}
"""
        result = self.detector.detect(_ctx(src))
        consumes = [e for e in result.edges if e.kind == EdgeKind.CONSUMES]
        assert len(consumes) == 1
        assert consumes[0].properties.get("container_factory") == "myFactory"

    def test_determinism(self):
        src = """\
public class JmsApp {
    @JmsListener(destination = "q1")
    public void a(String m) { }
}
"""
        r1 = self.detector.detect(_ctx(src))
        r2 = self.detector.detect(_ctx(src))
        assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]


# ===========================================================================
# RabbitMQ Detector
# ===========================================================================

class TestRabbitmqDetector:
    def setup_method(self):
        self.detector = RabbitmqDetector()

    def test_no_rabbitmq(self):
        result = self.detector.detect(_ctx("public class Foo { }"))
        assert len(result.nodes) == 0

    def test_rabbit_listener(self):
        src = """\
public class EventConsumer {

    @RabbitListener(queues = "event-queue")
    public void handleEvent(String msg) { }
}
"""
        result = self.detector.detect(_ctx(src))
        queues = [n for n in result.nodes if n.kind == NodeKind.QUEUE]
        assert len(queues) == 1
        assert queues[0].properties["queue"] == "event-queue"
        consumes = [e for e in result.edges if e.kind == EdgeKind.CONSUMES]
        assert len(consumes) == 1

    def test_rabbit_template_send(self):
        src = """\
public class EventProducer {

    public void publish() {
        rabbitTemplate.convertAndSend("exchange-name", "msg");
    }
}
"""
        result = self.detector.detect(_ctx(src))
        produces = [e for e in result.edges if e.kind == EdgeKind.PRODUCES]
        assert len(produces) == 1
        assert produces[0].properties["exchange"] == "exchange-name"

    def test_exchange_declaration(self):
        src = """\
public class RabbitConfig {

    public TopicExchange topicExchange() {
        return new TopicExchange("my-exchange");
    }
}
"""
        result = self.detector.detect(_ctx(src))
        queues = [n for n in result.nodes if n.kind == NodeKind.QUEUE]
        assert len(queues) == 1
        assert "my-exchange" in queues[0].label

    def test_determinism(self):
        src = """\
public class RabbitApp {
    @RabbitListener(queues = "q1")
    public void a(String m) { }
}
"""
        r1 = self.detector.detect(_ctx(src))
        r2 = self.detector.detect(_ctx(src))
        assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]


# ===========================================================================
# RMI Detector
# ===========================================================================

class TestRmiDetector:
    def setup_method(self):
        self.detector = RmiDetector()

    def test_no_rmi(self):
        result = self.detector.detect(_ctx("public class Foo { }"))
        assert len(result.nodes) == 0

    def test_remote_interface(self):
        src = """\
import java.rmi.Remote;

public interface Calculator extends Remote {
    int add(int a, int b) throws RemoteException;
}
"""
        result = self.detector.detect(_ctx(src))
        rmi_ifaces = [n for n in result.nodes if n.kind == NodeKind.RMI_INTERFACE]
        assert len(rmi_ifaces) == 1
        assert rmi_ifaces[0].label == "Calculator"

    def test_unicast_remote_object(self):
        src = """\
public class CalculatorImpl extends UnicastRemoteObject implements Calculator {
    public int add(int a, int b) { return a + b; }
}
"""
        result = self.detector.detect(_ctx(src))
        exports = [e for e in result.edges if e.kind == EdgeKind.EXPORTS_RMI]
        assert len(exports) == 1
        assert "Calculator" in exports[0].target

    def test_registry_bind(self):
        src = """\
public class Server {
    public static void main(String[] args) {
        Registry registry = LocateRegistry.createRegistry(1099);
        Naming.rebind("Calculator", new CalculatorImpl());
    }
}
"""
        result = self.detector.detect(_ctx(src))
        exports = [e for e in result.edges if e.kind == EdgeKind.EXPORTS_RMI]
        assert len(exports) == 1
        assert exports[0].properties["binding_name"] == "Calculator"

    def test_registry_lookup(self):
        src = """\
public class Client {
    public static void main(String[] args) {
        Calculator calc = (Calculator) Naming.lookup("Calculator");
    }
}
"""
        result = self.detector.detect(_ctx(src))
        invokes = [e for e in result.edges if e.kind == EdgeKind.INVOKES_RMI]
        assert len(invokes) == 1
        assert invokes[0].properties["binding_name"] == "Calculator"

    def test_determinism(self):
        src = """\
public interface Foo extends Remote {
    void bar() throws RemoteException;
}
"""
        r1 = self.detector.detect(_ctx(src))
        r2 = self.detector.detect(_ctx(src))
        assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]
