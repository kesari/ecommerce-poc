package com.poc.order.verification;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.poc.order.infrastructure.client.BasketClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BasketClosureTest {
 @Test void realOrderClientIgnoresUnusedRenamedTotal() throws Exception {
  byte[] body=Files.readAllBytes(Path.of(System.getenv("WIRE_INPUT")));var requests=new AtomicInteger();
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/api/v1/basket",e->{requests.incrementAndGet();e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});server.start();
  try {
   var client=new BasketClient(RestClient.builder(),"http://127.0.0.1:"+server.getAddress().getPort());
   var result=client.currentBasket("Bearer local");
   Files.writeString(Path.of(System.getenv("WIRE_OUTPUT")),JsonMapper.builder().findAndAddModules().build().writeValueAsString(result));
   assertEquals(1,requests.get(),"REAL_HTTP");assertNotNull(result,"BUSINESS_BASKET_SNAPSHOT");
   assertEquals(7,result.basketVersion(),"BUSINESS_BASKET_VERSION");
   assertNull(result.couponCode(),"BUSINESS_BASKET_COUPON");
   assertEquals(13000,result.subtotalMinor(),"BUSINESS_BASKET_SUBTOTAL");
   assertEquals(655,result.discountMinor(),"BUSINESS_BASKET_DISCOUNT");
   assertEquals("INR",result.currency(),"BUSINESS_BASKET_CURRENCY");
   assertEquals(1,result.items().size(),"BUSINESS_BASKET_LINES");
   var line=result.items().getFirst();assertEquals("Bag",line.name(),"BUSINESS_BASKET_LINE_NAME");
   assertEquals(13000,line.unitPriceMinor(),"BUSINESS_BASKET_LINE_PRICE");assertEquals(1,line.quantity(),"BUSINESS_BASKET_LINE_QUANTITY");
  } finally {server.stop(0);}
 }
}
