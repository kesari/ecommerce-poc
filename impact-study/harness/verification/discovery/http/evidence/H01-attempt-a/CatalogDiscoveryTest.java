package com.poc.basket.verification;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.poc.catalog.api.dto.ProductResponse;
import com.poc.basket.infrastructure.catalog.CatalogClient;
import com.poc.basket.infrastructure.catalog.CatalogClientProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CatalogDiscoveryTest {
 @Test void actualCatalogResponseToActualBasketClient() throws Exception {
  var json = JsonMapper.builder().findAndAddModules().build();
  String phase = System.getProperty("verification.phase");
  UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
  byte[] body = json.writeValueAsBytes(new ProductResponse(id,"item","description","/image",12300,"INR",true));
  var requests = new AtomicInteger();
  var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/api/v1/products/", exchange -> {
   requests.incrementAndGet(); exchange.getResponseHeaders().set("Content-Type","application/json");
   exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
  });
  server.start();
  try {
   var client = new CatalogClient(new CatalogClientProperties("http://127.0.0.1:"+server.getAddress().getPort(),Duration.ofSeconds(2),Duration.ofSeconds(2)),CircuitBreakerRegistry.ofDefaults(),RetryRegistry.ofDefaults());
   var product = client.lookup(id);
   var obs = new LinkedHashMap<String,Object>();
   obs.put("wire",json.readTree(body)); obs.put("bound_currency",product.currency()); obs.put("bound_price_minor",product.priceMinor()); obs.put("http_requests",requests.get());
   Files.writeString(Path.of(System.getProperty("verification.observation")),json.writerWithDefaultPrettyPrinter().writeValueAsString(obs));
   assertEquals(1,requests.get(),"REAL_HTTP"); assertEquals(id,product.id(),"IDENTITY"); assertEquals(12300,product.priceMinor(),"PRICE"); assertTrue(product.active(),"ACTIVE");
   if(phase.equals("baseline")) { assertTrue(json.readTree(body).has("currency")); assertFalse(json.readTree(body).has("currencyCode")); }
   else { assertTrue(json.readTree(body).has("currencyCode"),"REQUESTED_WIRE_CHANGE"); assertFalse(json.readTree(body).has("currency")); }
   if(phase.equals("changed")) assertNull(product.currency(),"OBSERVED_BREAK");
   else assertEquals("INR",product.currency(),"BUSINESS_CURRENCY");
  } finally { server.stop(0); }
 }
}
