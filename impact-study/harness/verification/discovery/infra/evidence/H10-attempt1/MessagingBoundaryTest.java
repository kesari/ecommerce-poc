package com.poc.order.verification;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.poc.order.application.*;
import com.poc.order.application.port.*;
import com.poc.order.domain.model.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.*;
import org.springframework.kafka.core.*;
import org.testcontainers.kafka.KafkaContainer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.awaitility.Awaitility.await;

/** Actual production publishers, registered listeners and application services; persistence ports controlled. */
class MessagingBoundaryTest {
 @Configuration @EnableKafka static class Config {}
 final com.fasterxml.jackson.databind.ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
 final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
 final Map<String,Object> observed = new LinkedHashMap<>();
 void save() throws Exception { Files.writeString(Path.of(System.getProperty("verification.observation")), json.writerWithDefaultPrettyPrinter().writeValueAsString(observed)); }
 @Test void deliveryPreservesBusinessState() throws Exception {
  String scenario = System.getProperty("verification.case"), phase = System.getProperty("verification.phase");
  observed.put("case",scenario); observed.put("phase",phase);
  try (KafkaContainer kafka = new KafkaContainer("apache/kafka:4.0.0").withLabel("discovery.verification",System.getProperty("verification.run")).withImagePullPolicy(image -> false).withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(new com.github.dockerjava.api.model.PortBinding(com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1",0),new com.github.dockerjava.api.model.ExposedPort(9093))))) {
   kafka.start(); String bootstrap = kafka.getBootstrapServers();
   List<String> topics = List.of("payment.charged.v1","payment.charged.v2","payment.charged.v1.retry.1","payment.charged.v1.retry.2","inventory.reserve.requested.v1","inventory.reserve.requested.v1.retry.1","inventory.reserve.requested.v1.retry.2","inventory.reserve.requested.v1.dlq");
   try(AdminClient admin=AdminClient.create(Map.of("bootstrap.servers",bootstrap))) { admin.createTopics(topics.stream().map(t->new NewTopic(t,1,(short)1)).toList()).all().get(); }
   var pf=new DefaultKafkaProducerFactory<String,String>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap,ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class,ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class));
   var producer=new KafkaTemplate<>(pf);
   try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()) {
    var cf=new DefaultKafkaConsumerFactory<String,String>(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap,ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class,ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class,ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest"));
    var factory=new ConcurrentKafkaListenerContainerFactory<String,String>(); factory.setConsumerFactory(cf); factory.getContainerProperties().setPollTimeout(100);
    context.register(Config.class); context.registerBean("kafkaListenerContainerFactory",ConcurrentKafkaListenerContainerFactory.class,()->factory);
    if(scenario.equals("H05")) payment(context,producer,bootstrap,phase); else inventory(context,producer,bootstrap,phase);
   } finally { pf.destroy(); }
  }
 }
 void start(AnnotationConfigApplicationContext context) {
  context.refresh(); var registry=context.getBean(KafkaListenerEndpointRegistry.class);
  await().atMost(Duration.ofSeconds(35)).until(()->!registry.getListenerContainers().isEmpty() && registry.getListenerContainers().stream().allMatch(c->c.getAssignedPartitions()!=null&&!c.getAssignedPartitions().isEmpty()));
  observed.put("registered_topics",registry.getListenerContainers().stream().map(c->List.of(c.getContainerProperties().getTopics())).toList());
 }
 int audit(String bootstrap,String topic,String body) {
  try(var consumer=new KafkaConsumer<String,String>(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap,ConsumerConfig.GROUP_ID_CONFIG,"audit-"+UUID.randomUUID(),ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class,ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class))) {
   var tp=new TopicPartition(topic,0); consumer.assign(List.of(tp));consumer.seekToBeginning(List.of(tp));
   long until=System.nanoTime()+Duration.ofSeconds(10).toNanos();
   while(System.nanoTime()<until) for(var r:consumer.poll(Duration.ofMillis(200))) if(r.value().equals(body)) return 1;
   return 0;
  }
 }
 Order order(UUID id,OrderStatus status) { return new Order(id,UUID.randomUUID(),UUID.randomUUID(),status,7,new AddressSnapshot("A","B",null,"C","D","560001","IN"),List.of(new OrderLine(UUID.randomUUID(),"Widget",2500,3)),"CREDIT_CARD","tok_success",7500,"INR",null); }
 void payment(AnnotationConfigApplicationContext context,KafkaTemplate<String,String> producer,String bootstrap,String phase) throws Exception {
  UUID id=UUID.randomUUID(); Order initial=order(id,OrderStatus.PAYMENT_PENDING); AtomicReference<OrderStatus> status=new AtomicReference<>(initial.status()); AtomicReference<UUID> paymentId=new AtomicReference<>();
  var orders=mock(OrderRepository.class); when(orders.findById(id)).thenAnswer(call->Optional.of(new Order(id,initial.userId(),initial.quoteId(),status.get(),7,initial.address(),initial.lines(),initial.paymentMethod(),initial.paymentToken(),7500,"INR",paymentId.get())));
  doAnswer(c->{ status.set(c.getArgument(1));return null; }).when(orders).updateStatus(eq(id),any());
  doAnswer(c->{ paymentId.set(c.getArgument(1));return null; }).when(orders).recordPaymentId(eq(id),any());
  var inbox=mock(InboxRepository.class); when(inbox.claim(any())).thenReturn(true);
  var outbox=mock(OutboxRepository.class); AtomicReference<EventEnvelope> emitted=new AtomicReference<>();doAnswer(c->{emitted.set(c.getArgument(1));return null;}).when(outbox).append(eq(id),any());
  var saga=new SagaOrchestrator(orders,inbox,outbox,clock);
  context.registerBean("listener",com.poc.order.infrastructure.messaging.SagaResultListener.class,()->new com.poc.order.infrastructure.messaging.SagaResultListener(saga,json,new com.poc.order.infrastructure.messaging.RetryRouter(producer,clock)));
  start(context);
  var store=mock(com.poc.payment.application.port.PaymentStore.class); when(store.claimEvent(any())).thenReturn(true);when(store.findByOrderId(id)).thenReturn(Optional.empty());
  AtomicReference<com.poc.payment.infrastructure.persistence.row.OutboxRow> pending=new AtomicReference<>();
  doAnswer(c->{pending.set(new com.poc.payment.infrastructure.persistence.row.OutboxRow(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3),false,c.getArgument(4)));return null;}).when(store).addOutboxEvent(any(),any(),anyString(),anyString(),any());
  var command=new com.poc.payment.application.EventEnvelope(UUID.randomUUID(),"payment.charge.requested",1,clock.instant(),"order-service",id,null,id,json.createObjectNode());
  new com.poc.payment.application.PaymentCommandService(store,json,clock,new SimpleMeterRegistry()).charge(command,new com.poc.payment.application.ChargeRequested(id,7500,"INR","tok_success"));
  when(store.findPendingOutbox(100)).thenAnswer(c->List.of(pending.get()));
  new com.poc.payment.infrastructure.kafka.OutboxPublisher(store,producer,json).publishPending();
  verify(store).markPublished(pending.get().eventId());
  int broker=audit(bootstrap,pending.get().topic(),pending.get().payload()); Thread.sleep(1800);
  String mainStatus=status.get().name(), mainEvent=emitted.get()==null?null:emitted.get().eventType(); UUID mainPayment=paymentId.get();
  if(!mainStatus.equals("INVENTORY_COMMIT_PENDING")) { producer.send("payment.charged.v1",id.toString(),pending.get().payload()).get(); await().atMost(Duration.ofSeconds(10)).until(()->status.get()==OrderStatus.INVENTORY_COMMIT_PENDING); }
  observed.put("producer_topic",pending.get().topic());observed.put("broker_records",broker);observed.put("consumer_liveness",status.get()==OrderStatus.INVENTORY_COMMIT_PENDING);observed.put("order_status",mainStatus);observed.put("next_event",mainEvent);observed.put("payment_id_recorded",mainPayment!=null);save();
  assertEquals(phase.equals("baseline")?"payment.charged.v1":"payment.charged.v2",pending.get().topic(),"REQUESTED_CHANGE_PRESERVED"); assertEquals(1,broker,"BROKER_DELIVERY"); assertTrue((Boolean)observed.get("consumer_liveness"),"LISTENER_LIVE");assertEquals("INVENTORY_COMMIT_PENDING",mainStatus,"PAYMENT_RESULT_ADVANCES_ORDER");assertEquals("inventory.commit.requested",mainEvent,"NEXT_COMMAND");assertNotNull(mainPayment,"PAYMENT_ID");
 }
 void inventory(AnnotationConfigApplicationContext context,KafkaTemplate<String,String> producer,String bootstrap,String phase) throws Exception {
  UUID id=UUID.randomUUID(),product=UUID.randomUUID(),user=UUID.randomUUID(),qid=UUID.randomUUID(); AtomicInteger quantity=new AtomicInteger(),stockCount=new AtomicInteger(10),reservationsMade=new AtomicInteger();
  var stock=mock(com.poc.inventory.application.port.StockRepository.class);when(stock.availableFor(any())).thenReturn(Map.of(product,10));doAnswer(c->{stockCount.addAndGet(-c.<Integer>getArgument(1));return null;}).when(stock).decrement(eq(product),anyInt());
  var reservations=mock(com.poc.inventory.application.port.ReservationRepository.class);when(reservations.findByOrderId(any())).thenReturn(Optional.empty());doAnswer(c->{var items=c.<List<com.poc.inventory.domain.model.ReservationItem>>getArgument(3);quantity.set(items.getFirst().quantity());reservationsMade.incrementAndGet();return null;}).when(reservations).create(any(),any(),any(),any());
  var inbox=mock(com.poc.inventory.application.port.InboxRepository.class);when(inbox.claim(any())).thenReturn(true);var outbox=mock(com.poc.inventory.application.port.OutboxRepository.class);
  var inventory=new com.poc.inventory.application.InventoryService(stock,reservations,inbox,outbox,clock);
  context.registerBean("listener",com.poc.inventory.infrastructure.messaging.InventoryCommandListener.class,()->new com.poc.inventory.infrastructure.messaging.InventoryCommandListener(inventory,json,new com.poc.inventory.infrastructure.messaging.RetryRouter(producer,clock)));
  start(context);
  var quote=new Quote(qid,user,7,new AddressSnapshot("A","B",null,"C","D","560001","IN"),List.of(new OrderLine(product,"Widget",2500,3)),new PriceBreakdown(7500,0,0,0,7500,"INR"),new DeliveryWindow(java.time.LocalDate.of(2026,1,2),java.time.LocalDate.of(2026,1,3)),clock.instant().plusSeconds(300));
  var quotes=mock(QuoteRepository.class);when(quotes.findById(qid)).thenReturn(Optional.of(quote));var orders=mock(OrderRepository.class);AtomicReference<Order> saved=new AtomicReference<>();doAnswer(c->{saved.set(c.getArgument(0));return null;}).when(orders).save(any());when(orders.findById(any())).thenAnswer(c->Optional.ofNullable(saved.get()));var idem=mock(IdempotencyRepository.class);when(idem.find(anyString())).thenReturn(Optional.empty());var baskets=mock(BasketPort.class);when(baskets.currentBasket(anyString())).thenReturn(new BasketPort.BasketSnapshot(7,null,7500,0,"INR",List.of()));
  var producerOutbox=mock(OutboxRepository.class);AtomicReference<EventEnvelope> envelope=new AtomicReference<>();doAnswer(c->{envelope.set(c.getArgument(1));return null;}).when(producerOutbox).append(any(),any());
  new OrderService(quotes,orders,idem,producerOutbox,baskets,clock).place("Bearer token",user,"idempotency",qid,"CREDIT_CARD","tok_success");
  String body=json.writeValueAsString(envelope.get());when(producerOutbox.findUnpublished(50)).thenReturn(List.of(new OutboxRepository.PendingEvent(envelope.get().eventId(),envelope.get().topic(),envelope.get().partitionKey(),body)));
  new com.poc.order.infrastructure.messaging.OutboxPublisher(producerOutbox,producer).publishPending();verify(producerOutbox).markPublished(envelope.get().eventId());int broker=audit(bootstrap,envelope.get().topic(),body);Thread.sleep(2200);
  int mainQuantity=quantity.get(),mainStock=stockCount.get(),mainReservations=reservationsMade.get();
  if(mainReservations==0) { var control=json.readTree(body); ((com.fasterxml.jackson.databind.node.ObjectNode)control.path("payload").path("items").get(0)).put("quantity",3).put("units",3);((com.fasterxml.jackson.databind.node.ObjectNode)control).put("eventId",UUID.randomUUID().toString());producer.send(envelope.get().topic(),envelope.get().partitionKey(),json.writeValueAsString(control)).get();await().atMost(Duration.ofSeconds(10)).until(()->reservationsMade.get()==1); }
  observed.put("producer_item",json.readTree(body).path("payload").path("items").get(0));observed.put("broker_records",broker);observed.put("consumer_liveness",reservationsMade.get()==1);observed.put("reservation_quantity",mainQuantity);observed.put("remaining_stock",mainStock);observed.put("reservations",mainReservations);save();
  assertEquals(3,json.readTree(body).path("payload").path("items").get(0).path(phase.equals("baseline")?"quantity":"units").asInt(),"REQUESTED_CHANGE_PRESERVED");assertEquals(1,broker,"BROKER_DELIVERY");assertTrue((Boolean)observed.get("consumer_liveness"),"LISTENER_LIVE");assertEquals(3,mainQuantity,"RESERVATION_QUANTITY");assertEquals(7,mainStock,"STOCK_DECREMENT");assertEquals(1,mainReservations,"RESERVATION_CREATED");
 }
}
