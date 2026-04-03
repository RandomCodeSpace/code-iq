package io.github.randomcodespace.iq.analyzer.linker;

import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TopicLinkerTest {

    private final TopicLinker linker = new TopicLinker();

    @Test
    void linksProducerToConsumerViaTopic() {
        var topic = new CodeNode("topic:orders", NodeKind.TOPIC, "orders");
        var producer = new CodeNode("svc:OrderService", NodeKind.CLASS, "OrderService");
        var consumer = new CodeNode("svc:PaymentService", NodeKind.CLASS, "PaymentService");

        var producesEdge = new CodeEdge();
        producesEdge.setId("e1");
        producesEdge.setKind(EdgeKind.PRODUCES);
        producesEdge.setSourceId("svc:OrderService");
        producesEdge.setTarget(topic);

        var consumesEdge = new CodeEdge();
        consumesEdge.setId("e2");
        consumesEdge.setKind(EdgeKind.CONSUMES);
        consumesEdge.setSourceId("svc:PaymentService");
        consumesEdge.setTarget(topic);

        LinkResult result = linker.link(
                List.of(topic, producer, consumer),
                List.of(producesEdge, consumesEdge)
        );

        assertEquals(1, result.edges().size());
        CodeEdge callsEdge = result.edges().getFirst();
        assertEquals(EdgeKind.CALLS, callsEdge.getKind());
        assertEquals("svc:OrderService", callsEdge.getSourceId());
        assertEquals("svc:PaymentService", callsEdge.getTarget().getId());
        assertEquals(true, callsEdge.getProperties().get("inferred"));
        assertEquals("orders", callsEdge.getProperties().get("topic"));
    }

    @Test
    void doesNotLinkProducerToItself() {
        var topic = new CodeNode("topic:self", NodeKind.TOPIC, "self");
        var svc = new CodeNode("svc:SelfService", NodeKind.CLASS, "SelfService");

        var producesEdge = new CodeEdge();
        producesEdge.setId("e1");
        producesEdge.setKind(EdgeKind.PRODUCES);
        producesEdge.setSourceId("svc:SelfService");
        producesEdge.setTarget(topic);

        var consumesEdge = new CodeEdge();
        consumesEdge.setId("e2");
        consumesEdge.setKind(EdgeKind.CONSUMES);
        consumesEdge.setSourceId("svc:SelfService");
        consumesEdge.setTarget(topic);

        LinkResult result = linker.link(List.of(topic, svc), List.of(producesEdge, consumesEdge));

        assertTrue(result.edges().isEmpty());
    }

    @Test
    void noTopicsReturnsEmpty() {
        var node = new CodeNode("svc:A", NodeKind.CLASS, "A");
        LinkResult result = linker.link(List.of(node), List.of());

        assertTrue(result.edges().isEmpty());
        assertTrue(result.nodes().isEmpty());
    }

    @Test
    void handlesQueueNodes() {
        var queue = new CodeNode("queue:tasks", NodeKind.QUEUE, "tasks");
        var producer = new CodeNode("svc:TaskCreator", NodeKind.CLASS, "TaskCreator");
        var consumer = new CodeNode("svc:TaskWorker", NodeKind.CLASS, "TaskWorker");

        var producesEdge = new CodeEdge();
        producesEdge.setId("e1");
        producesEdge.setKind(EdgeKind.PRODUCES);
        producesEdge.setSourceId("svc:TaskCreator");
        producesEdge.setTarget(queue);

        var consumesEdge = new CodeEdge();
        consumesEdge.setId("e2");
        consumesEdge.setKind(EdgeKind.CONSUMES);
        consumesEdge.setSourceId("svc:TaskWorker");
        consumesEdge.setTarget(queue);

        LinkResult result = linker.link(
                List.of(queue, producer, consumer),
                List.of(producesEdge, consumesEdge)
        );

        assertEquals(1, result.edges().size());
    }

    @Test
    void linksSendsToReceivesFromEdgesViaTopic() {
        // Tibco EMS / Azure Service Bus pattern
        var topic = new CodeNode("topic:orders", NodeKind.TOPIC, "orders");
        var producer = new CodeNode("svc:TibcoSender", NodeKind.CLASS, "TibcoSender");
        var consumer = new CodeNode("svc:TibcoReceiver", NodeKind.CLASS, "TibcoReceiver");

        var sendsToEdge = new CodeEdge();
        sendsToEdge.setId("e1");
        sendsToEdge.setKind(EdgeKind.SENDS_TO);
        sendsToEdge.setSourceId("svc:TibcoSender");
        sendsToEdge.setTarget(topic);

        var receivesFromEdge = new CodeEdge();
        receivesFromEdge.setId("e2");
        receivesFromEdge.setKind(EdgeKind.RECEIVES_FROM);
        receivesFromEdge.setSourceId("svc:TibcoReceiver");
        receivesFromEdge.setTarget(topic);

        LinkResult result = linker.link(
                List.of(topic, producer, consumer),
                List.of(sendsToEdge, receivesFromEdge)
        );

        assertEquals(1, result.edges().size());
        CodeEdge callsEdge = result.edges().getFirst();
        assertEquals(EdgeKind.CALLS, callsEdge.getKind());
        assertEquals("svc:TibcoSender", callsEdge.getSourceId());
        assertEquals("svc:TibcoReceiver", callsEdge.getTarget().getId());
        assertEquals(true, callsEdge.getProperties().get("inferred"));
    }

    @Test
    void linksPublishesListensEdgesViaEventNode() {
        // Spring Events pattern using EVENT node kind
        var event = new CodeNode("event:UserCreated", NodeKind.EVENT, "UserCreated");
        var publisher = new CodeNode("svc:UserService", NodeKind.CLASS, "UserService");
        var listener = new CodeNode("svc:EmailService", NodeKind.CLASS, "EmailService");

        var publishesEdge = new CodeEdge();
        publishesEdge.setId("e1");
        publishesEdge.setKind(EdgeKind.PUBLISHES);
        publishesEdge.setSourceId("svc:UserService");
        publishesEdge.setTarget(event);

        var listensEdge = new CodeEdge();
        listensEdge.setId("e2");
        listensEdge.setKind(EdgeKind.LISTENS);
        listensEdge.setSourceId("svc:EmailService");
        listensEdge.setTarget(event);

        LinkResult result = linker.link(
                List.of(event, publisher, listener),
                List.of(publishesEdge, listensEdge)
        );

        assertEquals(1, result.edges().size());
        CodeEdge callsEdge = result.edges().getFirst();
        assertEquals(EdgeKind.CALLS, callsEdge.getKind());
        assertEquals("svc:UserService", callsEdge.getSourceId());
        assertEquals("svc:EmailService", callsEdge.getTarget().getId());
        assertEquals("UserCreated", callsEdge.getProperties().get("topic"));
    }

    @Test
    void handlesMessageQueueNodeKind() {
        var mq = new CodeNode("mq:notifications", NodeKind.MESSAGE_QUEUE, "notifications");
        var sender = new CodeNode("svc:NotifySender", NodeKind.CLASS, "NotifySender");
        var receiver = new CodeNode("svc:NotifyWorker", NodeKind.CLASS, "NotifyWorker");

        var sendsEdge = new CodeEdge();
        sendsEdge.setId("e1");
        sendsEdge.setKind(EdgeKind.SENDS_TO);
        sendsEdge.setSourceId("svc:NotifySender");
        sendsEdge.setTarget(mq);

        var receivesEdge = new CodeEdge();
        receivesEdge.setId("e2");
        receivesEdge.setKind(EdgeKind.RECEIVES_FROM);
        receivesEdge.setSourceId("svc:NotifyWorker");
        receivesEdge.setTarget(mq);

        LinkResult result = linker.link(
                List.of(mq, sender, receiver),
                List.of(sendsEdge, receivesEdge)
        );

        assertEquals(1, result.edges().size());
    }

    @Test
    void determinismTest() {
        var topic = new CodeNode("topic:payments", NodeKind.TOPIC, "payments");
        var prod1 = new CodeNode("svc:P1", NodeKind.CLASS, "P1");
        var prod2 = new CodeNode("svc:P2", NodeKind.CLASS, "P2");
        var cons = new CodeNode("svc:C1", NodeKind.CLASS, "C1");

        var e1 = new CodeEdge();
        e1.setId("e1"); e1.setKind(EdgeKind.PUBLISHES); e1.setSourceId("svc:P1"); e1.setTarget(topic);
        var e2 = new CodeEdge();
        e2.setId("e2"); e2.setKind(EdgeKind.SENDS_TO); e2.setSourceId("svc:P2"); e2.setTarget(topic);
        var e3 = new CodeEdge();
        e3.setId("e3"); e3.setKind(EdgeKind.LISTENS); e3.setSourceId("svc:C1"); e3.setTarget(topic);

        List<CodeNode> nodeList = new ArrayList<>(List.of(topic, prod1, prod2, cons));
        List<CodeEdge> edgeList = new ArrayList<>(List.of(e1, e2, e3));

        LinkResult result1 = linker.link(nodeList, edgeList);
        LinkResult result2 = linker.link(nodeList, edgeList);

        assertEquals(result1.edges().size(), result2.edges().size());
        for (int i = 0; i < result1.edges().size(); i++) {
            assertEquals(result1.edges().get(i).getId(), result2.edges().get(i).getId());
            assertEquals(result1.edges().get(i).getSourceId(), result2.edges().get(i).getSourceId());
        }
    }
}
