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
    void linksSendsToWithReceivesFrom() {
        var topic = new CodeNode("topic:events", NodeKind.TOPIC, "events");
        var sender = new CodeNode("svc:TibcoSender", NodeKind.CLASS, "TibcoSender");
        var receiver = new CodeNode("svc:TibcoReceiver", NodeKind.CLASS, "TibcoReceiver");

        var sendsEdge = new CodeEdge();
        sendsEdge.setId("e1");
        sendsEdge.setKind(EdgeKind.SENDS_TO);
        sendsEdge.setSourceId("svc:TibcoSender");
        sendsEdge.setTarget(topic);

        var receivesEdge = new CodeEdge();
        receivesEdge.setId("e2");
        receivesEdge.setKind(EdgeKind.RECEIVES_FROM);
        receivesEdge.setSourceId("svc:TibcoReceiver");
        receivesEdge.setTarget(topic);

        LinkResult result = linker.link(
                List.of(topic, sender, receiver),
                List.of(sendsEdge, receivesEdge)
        );

        assertEquals(1, result.edges().size());
        CodeEdge callsEdge = result.edges().getFirst();
        assertEquals(EdgeKind.CALLS, callsEdge.getKind());
        assertEquals("svc:TibcoSender", callsEdge.getSourceId());
        assertEquals("svc:TibcoReceiver", callsEdge.getTarget().getId());
        assertEquals("events", callsEdge.getProperties().get("topic"));
    }

    @Test
    void linksMixedSendsToAndConsumes() {
        var topic = new CodeNode("topic:orders", NodeKind.TOPIC, "orders");
        var sender = new CodeNode("svc:AzureSender", NodeKind.CLASS, "AzureSender");
        var consumer = new CodeNode("svc:OrderWorker", NodeKind.CLASS, "OrderWorker");

        var sendsEdge = new CodeEdge();
        sendsEdge.setId("e1");
        sendsEdge.setKind(EdgeKind.SENDS_TO);
        sendsEdge.setSourceId("svc:AzureSender");
        sendsEdge.setTarget(topic);

        var consumesEdge = new CodeEdge();
        consumesEdge.setId("e2");
        consumesEdge.setKind(EdgeKind.CONSUMES);
        consumesEdge.setSourceId("svc:OrderWorker");
        consumesEdge.setTarget(topic);

        LinkResult result = linker.link(
                List.of(topic, sender, consumer),
                List.of(sendsEdge, consumesEdge)
        );

        assertEquals(1, result.edges().size());
        assertEquals(EdgeKind.CALLS, result.edges().getFirst().getKind());
    }

    @Test
    void linksPublisherToListenerViaEvent() {
        var event = new CodeNode("event:UserCreated", NodeKind.EVENT, "UserCreated");
        var publisher = new CodeNode("svc:UserService", NodeKind.CLASS, "UserService");
        var listener = new CodeNode("svc:NotificationService", NodeKind.CLASS, "NotificationService");

        var publishesEdge = new CodeEdge();
        publishesEdge.setId("e1");
        publishesEdge.setKind(EdgeKind.PUBLISHES);
        publishesEdge.setSourceId("svc:UserService");
        publishesEdge.setTarget(event);

        var listensEdge = new CodeEdge();
        listensEdge.setId("e2");
        listensEdge.setKind(EdgeKind.LISTENS);
        listensEdge.setSourceId("svc:NotificationService");
        listensEdge.setTarget(event);

        LinkResult result = linker.link(
                List.of(event, publisher, listener),
                List.of(publishesEdge, listensEdge)
        );

        assertEquals(1, result.edges().size());
        CodeEdge callsEdge = result.edges().getFirst();
        assertEquals(EdgeKind.CALLS, callsEdge.getKind());
        assertEquals("svc:UserService", callsEdge.getSourceId());
        assertEquals("svc:NotificationService", callsEdge.getTarget().getId());
        assertEquals("UserCreated", callsEdge.getProperties().get("topic"));
    }

    @Test
    void linksPublisherWithExistingConsumer() {
        var event = new CodeNode("event:OrderPlaced", NodeKind.EVENT, "OrderPlaced");
        var publisher = new CodeNode("svc:OrderService", NodeKind.CLASS, "OrderService");
        var consumer = new CodeNode("svc:InventoryService", NodeKind.CLASS, "InventoryService");

        var publishesEdge = new CodeEdge();
        publishesEdge.setId("e1");
        publishesEdge.setKind(EdgeKind.PUBLISHES);
        publishesEdge.setSourceId("svc:OrderService");
        publishesEdge.setTarget(event);

        var consumesEdge = new CodeEdge();
        consumesEdge.setId("e2");
        consumesEdge.setKind(EdgeKind.CONSUMES);
        consumesEdge.setSourceId("svc:InventoryService");
        consumesEdge.setTarget(event);

        LinkResult result = linker.link(
                List.of(event, publisher, consumer),
                List.of(publishesEdge, consumesEdge)
        );

        assertEquals(1, result.edges().size());
        assertEquals(EdgeKind.CALLS, result.edges().getFirst().getKind());
    }

    @Test
    void handlesMessageQueueNodes() {
        var mq = new CodeNode("mq:ibm-orders", NodeKind.MESSAGE_QUEUE, "ibm-orders");
        var producer = new CodeNode("svc:IBMProducer", NodeKind.CLASS, "IBMProducer");
        var consumer = new CodeNode("svc:IBMConsumer", NodeKind.CLASS, "IBMConsumer");

        var sendsEdge = new CodeEdge();
        sendsEdge.setId("e1");
        sendsEdge.setKind(EdgeKind.SENDS_TO);
        sendsEdge.setSourceId("svc:IBMProducer");
        sendsEdge.setTarget(mq);

        var receivesEdge = new CodeEdge();
        receivesEdge.setId("e2");
        receivesEdge.setKind(EdgeKind.RECEIVES_FROM);
        receivesEdge.setSourceId("svc:IBMConsumer");
        receivesEdge.setTarget(mq);

        LinkResult result = linker.link(
                List.of(mq, producer, consumer),
                List.of(sendsEdge, receivesEdge)
        );

        assertEquals(1, result.edges().size());
        assertEquals(EdgeKind.CALLS, result.edges().getFirst().getKind());
    }

    @Test
    void determinismTest() {
        var topic = new CodeNode("topic:payments", NodeKind.TOPIC, "payments");
        var event = new CodeNode("event:PaymentProcessed", NodeKind.EVENT, "PaymentProcessed");
        var svcA = new CodeNode("svc:A", NodeKind.CLASS, "A");
        var svcB = new CodeNode("svc:B", NodeKind.CLASS, "B");
        var svcC = new CodeNode("svc:C", NodeKind.CLASS, "C");

        List<CodeEdge> edges = new ArrayList<>();

        var e1 = new CodeEdge();
        e1.setId("e1"); e1.setKind(EdgeKind.PRODUCES); e1.setSourceId("svc:A"); e1.setTarget(topic);
        edges.add(e1);

        var e2 = new CodeEdge();
        e2.setId("e2"); e2.setKind(EdgeKind.CONSUMES); e2.setSourceId("svc:B"); e2.setTarget(topic);
        edges.add(e2);

        var e3 = new CodeEdge();
        e3.setId("e3"); e3.setKind(EdgeKind.PUBLISHES); e3.setSourceId("svc:A"); e3.setTarget(event);
        edges.add(e3);

        var e4 = new CodeEdge();
        e4.setId("e4"); e4.setKind(EdgeKind.LISTENS); e4.setSourceId("svc:C"); e4.setTarget(event);
        edges.add(e4);

        List<CodeNode> nodes = List.of(topic, event, svcA, svcB, svcC);

        LinkResult result1 = linker.link(nodes, edges);
        LinkResult result2 = linker.link(nodes, edges);

        assertEquals(result1.edges().size(), result2.edges().size());
        for (int i = 0; i < result1.edges().size(); i++) {
            assertEquals(result1.edges().get(i).getId(), result2.edges().get(i).getId());
            assertEquals(result1.edges().get(i).getSourceId(), result2.edges().get(i).getSourceId());
            assertEquals(result1.edges().get(i).getTarget().getId(), result2.edges().get(i).getTarget().getId());
        }
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

}
