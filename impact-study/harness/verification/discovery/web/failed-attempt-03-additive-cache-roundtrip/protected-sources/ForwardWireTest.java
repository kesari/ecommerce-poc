package com.poc.bff.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.bff.infrastructure.client.*;
import com.poc.bff.infrastructure.cache.CatalogCache;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class ForwardWireTest {
 @Test void actualControllerAndRelayForwardProviderBytes() throws Exception {
  byte[] input=Files.readAllBytes(Path.of(System.getenv("WIRE_INPUT")));
  String kind=System.getenv("WIRE_KIND"); var calls=new AtomicInteger();
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  String route=kind.equals("basket")?"/api/v1/basket":"/api/v1/products";
  server.createContext(route,e->{calls.incrementAndGet(); e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,input.length);e.getResponseBody().write(input);e.close();});
  server.start();
  try {
   String base="http://127.0.0.1:"+server.getAddress().getPort();
   var relay=new DownstreamRelay(new DownstreamProperties(base,base,base,base),CircuitBreakerRegistry.ofDefaults());
   var cache=mock(CatalogCache.class); when(cache.get(anyString())).thenReturn(Optional.empty());
   Object controller=kind.equals("basket")?new BasketProxyController(relay):new CatalogProxyController(relay,cache);
   var mvc=MockMvcBuilders.standaloneSetup(controller).build();
   var response=mvc.perform(get(route+(kind.equals("basket")?"":"?page=0&size=20"))).andReturn().getResponse();
   assertEquals(200,response.getStatus(),"BFF_HTTP_STATUS");
   var mapper=new ObjectMapper(); assertEquals(mapper.readTree(input),mapper.readTree(response.getContentAsString()),"BFF_EXACT_JSON_FORWARDING");
   assertEquals(1,calls.get(),"REAL_DOWNSTREAM_HTTP_REQUEST");
   Files.writeString(Path.of(System.getenv("WIRE_OUTPUT")),response.getContentAsString());
  } finally {server.stop(0);}
 }
}
