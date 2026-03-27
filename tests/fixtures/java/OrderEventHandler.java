package com.example.order.messaging;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.context.event.EventListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class OrderEventHandler {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public OrderEventHandler(KafkaTemplate<String, Object> kafkaTemplate,
                             ApplicationEventPublisher eventPublisher) {
        this.kafkaTemplate = kafkaTemplate;
        this.eventPublisher = eventPublisher;
    }

    @KafkaListener(topics = "order-events", groupId = "order-service")
    public void handleOrderEvent(OrderEvent event) {
        // Process incoming order events
        eventPublisher.publishEvent(new OrderProcessedEvent(event));
    }

    @KafkaListener(topics = "payment-events", groupId = "order-service")
    public void handlePaymentEvent(PaymentEvent event) {
        // Update order status based on payment
    }

    public void publishOrderCreated(Order order) {
        kafkaTemplate.send("order-events", order.getId().toString(), new OrderCreatedEvent(order));
    }

    @EventListener
    public void onOrderProcessed(OrderProcessedEvent event) {
        kafkaTemplate.send("notification-events", event.getOrderId().toString(), event);
    }
}
