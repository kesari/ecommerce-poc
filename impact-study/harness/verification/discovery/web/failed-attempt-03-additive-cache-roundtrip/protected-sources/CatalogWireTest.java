package com.poc.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.catalog.application.CatalogService;
import com.poc.catalog.application.port.ProductRepository;
import com.poc.catalog.infrastructure.cache.ProductCache;
import com.poc.catalog.domain.model.Product;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogWireTest {
 @Test void serializeRealListAndCacheHit() throws Exception {
  var repo=mock(ProductRepository.class); var cache=mock(ProductCache.class);
  var stored=new AtomicReference<String>();
  when(cache.getListPage(0,20)).thenAnswer(i -> Optional.ofNullable(stored.get()));
  doAnswer(i -> {stored.set(i.getArgument(2));return null;}).when(cache).putListPage(eq(0),eq(20),anyString());
  var a=new Product(UUID.fromString("11111111-1111-1111-1111-111111111111"),"Bag","Canvas","https://images.example/bag.png",12345,"INR",true,Instant.EPOCH,Instant.EPOCH);
  var b=new Product(UUID.fromString("22222222-2222-2222-2222-222222222222"),"Cup","Ceramic","https://images.example/cup.png",6789,"INR",true,Instant.EPOCH,Instant.EPOCH);
  when(repo.findActivePage(0,20)).thenReturn(List.of(a,b));
  var mapper=new ObjectMapper(); var service=new CatalogService(repo,cache,mapper);
  String miss=mapper.writeValueAsString(service.listActive(0,20));
  String hit=mapper.writeValueAsString(service.listActive(0,20));
  assertEquals(miss,hit,"CACHE_HIT_PRESERVES_ORDER_AND_VALUES");
  verify(repo,times(1)).findActivePage(0,20);
  Files.writeString(Path.of(System.getenv("WIRE_OUTPUT")),miss);
  Files.writeString(Path.of(System.getenv("WIRE_OUTPUT")+".cache.json"),hit);
 }
}
