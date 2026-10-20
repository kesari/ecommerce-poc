package com.poc.order.verification;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.poc.shipment.api.dto.DeliveryEstimateResponse;
import com.poc.shipment.api.dto.DeliveryEstimateRequest;
import jakarta.validation.Validation;
import com.poc.shipment.application.DeliveryEstimator;
import com.poc.shipment.domain.model.DeliveryEstimate;
import com.poc.shipment.domain.model.DeliveryWindow;
import com.poc.order.application.CheckoutService;
import com.poc.order.application.port.AccountPort;
import com.poc.order.application.port.BasketPort;
import com.poc.order.application.port.QuoteRepository;
import com.poc.order.domain.model.AddressSnapshot;
import com.poc.order.domain.model.Quote;
import com.poc.order.infrastructure.client.ShipmentClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ShipmentDiscoveryTest {
 @Test void actualProviderToClientAndCheckout() throws Exception {
  String scenario=System.getProperty("verification.scenario"), phase=System.getProperty("verification.phase");
  var json=JsonMapper.builder().findAndAddModules().build();
  var charge=new AtomicLong(10000);
  var observations=new ArrayList<Map<String,Object>>();
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  var lastWire=new AtomicReference<Object>();
  var lastRequest=new AtomicReference<Object>();
  var today=LocalDate.of(2026,1,1);
  var validatorFactory=Validation.buildDefaultValidatorFactory(); var validator=validatorFactory.getValidator();
  server.createContext("/api/v1/delivery-estimates",exchange->{
   byte[] body; int status=200;
   try {
    var request=json.readTree(exchange.getRequestBody()); lastRequest.set(request);
    var dto=json.treeToValue(request,DeliveryEstimateRequest.class);
    if(!validator.validate(dto).isEmpty()) throw new IllegalArgumentException("request validation");
    var estimate=scenario.equals("H08") ? DeliveryEstimator.estimate(request.path("postalCode").asText(null),today)
      : new DeliveryEstimate(charge.get(),"INR",new DeliveryWindow(today.plusDays(2),today.plusDays(3)));
    body=json.writeValueAsBytes(DeliveryEstimateResponse.from(estimate)); lastWire.set(json.readTree(body));
   } catch(Exception invalid) { status=400; body=json.writeValueAsBytes(Map.of("error",invalid.getClass().getName(),"message",String.valueOf(invalid.getMessage()))); lastWire.set(json.readTree(body)); }
   exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(status,body.length); exchange.getResponseBody().write(body); exchange.close();
  });
  server.start();
  var client=new ShipmentClient(RestClient.builder(),"http://127.0.0.1:"+server.getAddress().getPort());
  record Input(String pin,long minor,boolean valid){}
  List<Input> inputs=scenario.equals("H08") ? List.of(new Input("560001",10000,true),new Input("700001",10000,true),new Input("1100",10000,true),new Input("1234567890",10000,true),new Input("56x001",10000,false),new Input("123",10000,false),new Input("12345678901",10000,false))
    : List.of(new Input("560001",0,true),new Input("560001",10000,true),new Input("560001",12300,true));
  var failures=new ArrayList<String>();
  try {
   for(var input:inputs) {
    charge.set(input.minor()); lastWire.set(null); lastRequest.set(null);
    var saved=new AtomicReference<Quote>();
    QuoteRepository quotes=new QuoteRepository(){public void save(Quote q){saved.set(q);}public Optional<Quote> findById(UUID id){return Optional.ofNullable(saved.get());}};
    BasketPort baskets=token->new BasketPort.BasketSnapshot(1,null,20000,0,"INR",List.of(new BasketPort.BasketSnapshot.Line(UUID.randomUUID(),"item",20000,1)));
    AccountPort accounts=(token,id)->new AddressSnapshot("user","street",null,"city","state",input.pin(),"IN");
    var checkout=new CheckoutService(baskets,accounts,client,quotes,Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),ZoneOffset.UTC));
    Exception error=null; Quote quote=null;
    try {quote=checkout.createQuote("Bearer local",UUID.randomUUID(),UUID.randomUUID());}catch(Exception e){error=e;}
    var obs=new LinkedHashMap<String,Object>(); obs.put("input_pin",input.pin());obs.put("input_shipping_minor",input.minor());obs.put("valid",input.valid());obs.put("request",lastRequest.get());obs.put("wire",lastWire.get());obs.put("quote_saved",saved.get()!=null);obs.put("failure",error==null?null:error.getClass().getName());obs.put("quote_shipping_minor",quote==null?null:quote.price().shippingMinor());obs.put("quote_total_minor",quote==null?null:quote.price().totalMinor());obs.put("quote_promised_from",quote==null?null:quote.promised().from().toString());observations.add(obs);
    try {
     assertNotNull(lastRequest.get(),"REAL_HTTP");
     if(!input.valid()) { assertNotNull(error,"BUSINESS_INVALID_PIN_REJECTED");assertNull(saved.get(),"INVALID_NOT_PERSISTED");
      var rejection=(com.fasterxml.jackson.databind.JsonNode)lastWire.get();
      assertEquals("java.lang.IllegalArgumentException",rejection.path("error").asText(),"BUSINESS_DTO_VALIDATION_ERROR");
      assertEquals("request validation",rejection.path("message").asText(),"BUSINESS_DTO_VALIDATION_REASON"); }
     else {
      assertNull(error,"BUSINESS_CHECKOUT_SUCCESS"); assertNotNull(saved.get(),"BUSINESS_QUOTE_PERSISTED");
      long expected=(phase.equals("changed") && scenario.equals("H02")) ? 0 : (phase.equals("changed") && scenario.equals("H07")) ? input.minor()/100 : input.minor();
      assertEquals(expected,quote.price().shippingMinor(),"BUSINESS_MINOR_UNITS");
      assertEquals(23600+expected,quote.price().totalMinor(),"BUSINESS_TOTAL");
      boolean metro=input.pin().startsWith("11")||input.pin().startsWith("56");
      int from=scenario.equals("H08")&&!metro?(phase.equals("baseline")?4:5):2;
      int to=scenario.equals("H08")&&!metro?(phase.equals("baseline")?6:7):3;
      assertEquals(today.plusDays(from),quote.promised().from(),"BUSINESS_WINDOW_FROM");
      assertEquals(today.plusDays(to),quote.promised().to(),"BUSINESS_WINDOW_TO");
      assertEquals(today.plusDays(from).toString(),((com.fasterxml.jackson.databind.JsonNode)lastWire.get()).path("fromDate").asText(),"REQUESTED_SLA_FROM");
      assertEquals(today.plusDays(to).toString(),((com.fasterxml.jackson.databind.JsonNode)lastWire.get()).path("toDate").asText(),"REQUESTED_SLA_TO");
     }
     var wire=(com.fasterxml.jackson.databind.JsonNode)lastWire.get();
     if(scenario.equals("H02")) { assertTrue(wire.has(phase.equals("baseline")?"shippingChargeMinor":"feeMinor"),"REQUESTED_WIRE_CHANGE"); assertFalse(wire.has(phase.equals("baseline")?"feeMinor":"shippingChargeMinor")); }
     if(scenario.equals("H07")) assertEquals(phase.equals("baseline")?input.minor():input.minor()/100,wire.path("shippingChargeMinor").asLong(),"REQUESTED_UNIT_CHANGE");
    } catch(AssertionError e){failures.add(e.getMessage());}
   }
   Files.writeString(Path.of(System.getProperty("verification.observation")),json.writerWithDefaultPrettyPrinter().writeValueAsString(observations));
   assertTrue(failures.isEmpty(),"PROTECTED_BUSINESS_ASSERTIONS: "+failures);
  } finally {server.stop(0);validatorFactory.close();}
 }
}
