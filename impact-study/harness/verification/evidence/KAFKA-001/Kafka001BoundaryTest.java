package com.poc.shipment.verification;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.poc.order.application.EventEnvelope;
import com.poc.order.application.port.OutboxRepository;
import com.poc.order.infrastructure.messaging.OutboxPublisher;
import com.poc.basket.application.BasketService;
import com.poc.shipment.application.ShipmentService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Actual producer and annotated consumer code across a real isolated Kafka broker. */
class Kafka001BoundaryTest {
    @Configuration
    @EnableKafka
    static class KafkaConfig {}

    @Test
    void renamedProducerTopicDisconnectsOldSubscriptions() throws Exception {
        String phase = System.getProperty("verification.phase");
        boolean disconnected = phase.equals("changed");
        var json = JsonMapper.builder().findAndAddModules().build();
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        AtomicInteger shipmentSaves = new AtomicInteger();
        AtomicInteger basketCheckouts = new AtomicInteger();
        AtomicInteger published = new AtomicInteger();
        try (KafkaContainer kafka = new KafkaContainer("apache/kafka:4.0.0").withImagePullPolicy(image -> false)) {
            kafka.start();
            String bootstrap = kafka.getBootstrapServers();
            try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap))) {
                admin.createTopics(List.of("order.confirmed.v1", "order.confirmed.v2",
                        "order.confirmed.v1.retry.1", "order.confirmed.v1.retry.2").stream()
                        .map(topic -> new NewTopic(topic, 1, (short) 1)).toList()).all().get();
            }
            var producerFactory = new DefaultKafkaProducerFactory<String, String>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
            var producer = new KafkaTemplate<>(producerFactory);
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                var consumerFactory = new DefaultKafkaConsumerFactory<String, String>(Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
                var listeners = new ConcurrentKafkaListenerContainerFactory<String, String>();
                listeners.setConsumerFactory(consumerFactory);
                listeners.getContainerProperties().setPollTimeout(100);
                var shipmentRepository = port(com.poc.shipment.application.port.ShipmentRepository.class, (name, args) -> switch (name) {
                    case "findByOrderId", "findById" -> Optional.empty();
                    case "save" -> { shipmentSaves.incrementAndGet(); yield null; }
                    default -> throw new UnsupportedOperationException(name);
                });
                var shipmentInbox = port(com.poc.shipment.application.port.InboxRepository.class, (name, args) ->
                        name.equals("alreadyProcessed") ? false : null);
                var shipmentOutbox = port(com.poc.shipment.application.port.OutboxRepository.class, (name, args) -> null);
                var shipments = new ShipmentService(shipmentRepository, shipmentOutbox, shipmentInbox, null, clock);
                var basketRepository = port(com.poc.basket.application.port.BasketRepository.class, (name, args) -> {
                    if (!name.equals("markCheckedOut")) throw new UnsupportedOperationException(name);
                    basketCheckouts.incrementAndGet(); return null;
                });
                var baskets = new BasketService(basketRepository, null, eventId -> true);
                context.register(KafkaConfig.class);
                context.registerBean("kafkaListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class, () -> listeners);
                context.registerBean("shipmentOrderConfirmedListener", com.poc.shipment.infrastructure.messaging.OrderConfirmedListener.class,
                        () -> new com.poc.shipment.infrastructure.messaging.OrderConfirmedListener(shipments, json));
                context.registerBean("basketOrderConfirmedListener", com.poc.basket.infrastructure.messaging.OrderConfirmedListener.class,
                        () -> new com.poc.basket.infrastructure.messaging.OrderConfirmedListener(baskets, json,
                                new com.poc.basket.infrastructure.messaging.RetryRouter(producer, clock)));
                context.refresh();
                var registry = context.getBean(KafkaListenerEndpointRegistry.class);
                await().atMost(Duration.ofSeconds(25)).until(() -> registry.getListenerContainers().size() == 2
                        && registry.getListenerContainers().stream().allMatch(container ->
                        container.getAssignedPartitions() != null && !container.getAssignedPartitions().isEmpty()));
                UUID orderId = UUID.randomUUID();
                var payload = Map.<String, Object>of("orderId", orderId.toString(), "userId", UUID.randomUUID().toString(),
                        "address", Map.of("postalCode", "560001", "city", "Bengaluru", "state", "KA", "country", "IN"));
                EventEnvelope envelope = EventEnvelope.command("order.confirmed", orderId, UUID.randomUUID(), clock.instant(), payload);
                String body = json.writeValueAsString(envelope);
                OutboxRepository outbox = new OutboxRepository() {
                    public void append(UUID id, EventEnvelope event) { throw new UnsupportedOperationException(); }
                    public List<PendingEvent> findUnpublished(int limit) {
                        return List.of(new PendingEvent(envelope.eventId(), envelope.topic(), envelope.partitionKey(), body));
                    }
                    public void markPublished(UUID id) { published.incrementAndGet(); }
                };
                new OutboxPublisher(outbox, producer).publishPending();
                assertEquals(1, published.get(), "ACTUAL_OUTBOX_PUBLISH_ACKNOWLEDGED");
                // An independent broker read proves delivery to the changed topic, even with no subscribers.
                int brokerRecords = 0;
                try (var audit = new KafkaConsumer<String, String>(Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                        ConsumerConfig.GROUP_ID_CONFIG, "independent-audit-" + UUID.randomUUID(),
                        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
                    var partition = new TopicPartition(envelope.topic(), 0);
                    audit.assign(List.of(partition)); audit.seekToBeginning(List.of(partition));
                    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                    while (brokerRecords == 0 && System.nanoTime() < deadline)
                        brokerRecords += audit.poll(Duration.ofMillis(200)).count();
                }
                if (disconnected) {
                    await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4)).until(() ->
                            shipmentSaves.get() == 0 && basketCheckouts.get() == 0);
                } else if (phase.equals("faulted-repair")) {
                    await().atMost(Duration.ofSeconds(10)).until(() -> shipmentSaves.get() == 1);
                    await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4)).until(() -> basketCheckouts.get() == 0);
                } else {
                    await().atMost(Duration.ofSeconds(10)).until(() -> shipmentSaves.get() == 1 && basketCheckouts.get() == 1);
                }
                int mainShipment = shipmentSaves.get(), mainBasket = basketCheckouts.get();
                boolean control = !disconnected && !phase.equals("faulted-repair");
                if (!control) {
                    // Positive control: the old subscriptions are healthy, not stopped or broken handlers.
                    producer.send("order.confirmed.v1", orderId.toString(), body).get();
                    await().atMost(Duration.ofSeconds(10)).until(() -> shipmentSaves.get() == 1 && basketCheckouts.get() == 1);
                    control = true;
                }
                Map<String, Object> observed = new LinkedHashMap<>();
                observed.put("phase", phase); observed.put("producer_topic", envelope.topic());
                observed.put("broker_records", brokerRecords); observed.put("publisher_acknowledged", published.get());
                observed.put("shipment_handler_saves", mainShipment); observed.put("basket_handler_checkouts", mainBasket);
                observed.put("consumer_liveness_control_passed", control);
                observed.put("subscription_topics", registry.getListenerContainers().stream()
                        .map(container -> List.of(container.getContainerProperties().getTopics())).toList());
                Files.writeString(Path.of(System.getProperty("verification.observation")),
                        json.writerWithDefaultPrettyPrinter().writeValueAsString(observed));
                assertEquals(phase.equals("baseline") ? "order.confirmed.v1" : "order.confirmed.v2", envelope.topic(), "REQUESTED_TOPIC_PRESERVED");
                assertEquals(1, brokerRecords, "BROKER_RECEIVED_ACTUAL_PRODUCER_RECORD");
                assertTrue(control, "CONSUMERS_PROVEN_LIVE");
                assertEquals(disconnected ? 0 : 1, mainShipment, "SHIPMENT_CONSUMER_DELIVERY");
                assertEquals(disconnected ? 0 : 1, mainBasket, "BASKET_CONSUMER_DELIVERY");
            } finally {
                producerFactory.destroy();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T port(Class<T> type, BiFunction<String, Object[], Object> call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> call.apply(method.getName(), args));
    }
}
