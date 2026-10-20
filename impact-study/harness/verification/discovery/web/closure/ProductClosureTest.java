package com.poc.basket.verification;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.poc.basket.infrastructure.catalog.*;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ProductClosureTest {
 @Test void realCatalogClientConsumesChangedProductWithoutEdit() throws Exception {
  var json=JsonMapper.builder().findAndAddModules().build();
  var input=json.readTree(Files.readString(Path.of(System.getenv("WIRE_INPUT"))));
  var productJson=input.isArray()?input.get(0):input;
  byte[] body=json.writeValueAsBytes(productJson); var requests=new AtomicInteger();
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/api/v1/products/",e->{requests.incrementAndGet();e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});server.start();
  try {
   var client=new CatalogClient(new CatalogClientProperties("http://127.0.0.1:"+server.getAddress().getPort(),Duration.ofSeconds(2),Duration.ofSeconds(2)),CircuitBreakerRegistry.ofDefaults(),RetryRegistry.ofDefaults());
   var result=client.lookup(UUID.fromString("11111111-1111-1111-1111-111111111111"));
   Files.writeString(Path.of(System.getenv("WIRE_OUTPUT")),json.writeValueAsString(result));
   assertEquals(1,requests.get(),"REAL_HTTP");
   assertNotNull(result,"BUSINESS_CATALOG_PRODUCT");
   assertEquals(UUID.fromString("11111111-1111-1111-1111-111111111111"),result.id(),"BUSINESS_CATALOG_ID");
   assertEquals("Bag",result.name(),"BUSINESS_CATALOG_NAME");
   assertEquals(12345,result.priceMinor(),"BUSINESS_CATALOG_PRICE");
   assertEquals("INR",result.currency(),"BUSINESS_CATALOG_CURRENCY");
   assertTrue(result.active(),"BUSINESS_CATALOG_ACTIVE");
  } finally {server.stop(0);}
 }
}
