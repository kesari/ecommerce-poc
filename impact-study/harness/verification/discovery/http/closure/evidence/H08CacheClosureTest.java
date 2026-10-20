package com.poc.shipment.verification;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.poc.shipment.application.EstimateCache;
import com.poc.shipment.application.ShipmentService;
import com.poc.shipment.application.port.InboxRepository;
import com.poc.shipment.application.port.OutboxRepository;
import com.poc.shipment.application.port.ShipmentRepository;
import com.poc.shipment.domain.model.DeliveryEstimate;
import com.poc.shipment.domain.model.DeliveryWindow;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class H08CacheClosureTest {
 @Test void previousPolicyEntryMaySurviveUntilNormalExpiryThenNewPolicyComputes() throws Exception {
  String phase=System.getProperty("verification.phase");
  LocalDate today=LocalDate.of(2026,1,1);
  class ControlledCache implements EstimateCache {
   DeliveryEstimate value=new DeliveryEstimate(10000,"INR",new DeliveryWindow(today.plusDays(4),today.plusDays(6)));
   int gets,puts; String lastPin; int lastCount; long lastSubtotal;
   public Optional<DeliveryEstimate> get(String pin,int count,long subtotal){gets++;lastPin=pin;lastCount=count;lastSubtotal=subtotal;return Optional.ofNullable(value);}
   public void put(String pin,int count,long subtotal,DeliveryEstimate estimate){puts++;lastPin=pin;lastCount=count;lastSubtotal=subtotal;value=estimate;}
  }
  var cache=new ControlledCache(); var oldEntry=cache.value;
  var shipments=mock(ShipmentRepository.class);var outbox=mock(OutboxRepository.class);var inbox=mock(InboxRepository.class);
  var service=new ShipmentService(shipments,outbox,inbox,cache,Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),ZoneOffset.UTC));
  var warm=service.estimate("700001",1,20000);int putsAfterWarm=cache.puts;
  cache.value=null; // Models the allowed existing-entry lifetime ending; no real TTL timer claim.
  var cold=service.estimate("700001",1,20000);var repeated=service.estimate("700001",1,20000);
  var observed=new LinkedHashMap<String,Object>();
  observed.put("phase",phase);observed.put("warm_from",warm.window().from().toString());observed.put("warm_to",warm.window().to().toString());observed.put("cold_from",cold.window().from().toString());observed.put("cold_to",cold.window().to().toString());observed.put("repeated_from",repeated.window().from().toString());observed.put("repeated_to",repeated.window().to().toString());observed.put("gets",cache.gets);observed.put("puts",cache.puts);observed.put("puts_after_warm",putsAfterWarm);observed.put("cold_shipping_minor",cold.shippingMinor());observed.put("currency",cold.currency());
  Files.writeString(Path.of(System.getProperty("verification.observation")),JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(observed));
  assertSame(oldEntry,warm,"BUSINESS_ALLOWED_PREVIOUS_POLICY_CACHE_ENTRY");
  assertEquals(today.plusDays(4),warm.window().from(),"BUSINESS_ALLOWED_WARM_FROM");assertEquals(today.plusDays(6),warm.window().to(),"BUSINESS_ALLOWED_WARM_TO");
  int from=phase.equals("baseline")?4:5,to=phase.equals("baseline")?6:7;
  assertEquals(today.plusDays(from),cold.window().from(),"BUSINESS_NEW_COMPUTATION_FROM");assertEquals(today.plusDays(to),cold.window().to(),"BUSINESS_NEW_COMPUTATION_TO");
  assertSame(cold,repeated,"BUSINESS_NEW_ENTRY_REUSED");assertEquals(0,putsAfterWarm,"WARM_NO_RECOMPUTATION");assertEquals(1,cache.puts,"COLD_STORED_ONCE");assertEquals(3,cache.gets,"ACTUAL_SERVICE_CACHE_READS");assertEquals("700001",cache.lastPin);assertEquals(1,cache.lastCount);assertEquals(20000,cache.lastSubtotal);
  assertEquals(10000,cold.shippingMinor());assertEquals("INR",cold.currency());verifyNoInteractions(shipments,outbox,inbox);
 }
}
