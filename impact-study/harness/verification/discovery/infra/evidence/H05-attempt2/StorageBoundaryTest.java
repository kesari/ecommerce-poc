package com.poc.catalog.verification;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.poc.catalog.application.CatalogService;
import com.poc.catalog.application.port.ProductRepository;
import com.poc.catalog.domain.model.Product;
import com.poc.catalog.infrastructure.cache.ProductCache;
import com.poc.catalog.infrastructure.persistence.mapper.ProductMapper;
import com.poc.catalog.infrastructure.persistence.repository.MyBatisProductRepository;
import com.poc.catalog.infrastructure.persistence.typehandler.UuidTypeHandler;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.*;
import org.testcontainers.utility.DockerImageName;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real private stores, actual catalog SQL/cache/application/controller and downstream Java HTTP client. */
class StorageBoundaryTest {
 final com.fasterxml.jackson.databind.ObjectMapper json=JsonMapper.builder().findAndAddModules().build();
 final UUID id=UUID.fromString("11111111-1111-4111-8111-111111111111");
 final Map<String,Object> observed=new LinkedHashMap<>();
 void save() throws Exception {Files.writeString(Path.of(System.getProperty("verification.observation")),json.writerWithDefaultPrettyPrinter().writeValueAsString(observed));}
 @Test void privateRepresentationPreservesPublishedBehavior() throws Exception {
  String scenario=System.getProperty("verification.case"),phase=System.getProperty("verification.phase");observed.put("case",scenario);observed.put("phase",phase);
  if(scenario.equals("H09")) sql(phase); else cache(phase);
 }
 void sql(String phase) throws Exception {
  try(var db=new PostgreSQLContainer<>("postgres:17").withLabel("discovery.verification",System.getProperty("verification.run")).withImagePullPolicy(i->false).withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(new com.github.dockerjava.api.model.PortBinding(com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1",0),new com.github.dockerjava.api.model.ExposedPort(5432))))) {
   db.start();var ds=new DriverManagerDataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword());
   Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("2").load().migrate();
   try(var connection=ds.getConnection();var statement=connection.createStatement();var row=statement.executeQuery("SELECT price_minor FROM products WHERE id='"+id+"'")) {assertTrue(row.next());assertEquals(65000,row.getLong(1),"PRE_UPGRADE_DATA");}
   Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
   try(var connection=ds.getConnection();var statement=connection.createStatement();var row=statement.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_name='products' AND column_name IN ('price_minor','unit_price_minor')")) {var cols=new ArrayList<String>();while(row.next())cols.add(row.getString(1));observed.put("private_columns",cols);assertEquals(List.of(phase.equals("baseline")?"price_minor":"unit_price_minor"),cols,"FORWARD_MIGRATION_PRESERVED");}
   var config=new org.apache.ibatis.session.Configuration(new Environment("verification",new JdbcTransactionFactory(),ds));config.getTypeHandlerRegistry().register(UUID.class,UuidTypeHandler.class);
   try(var in=Files.newInputStream(Path.of("src/main/resources/mybatis/mapper/ProductMapper.xml"))) {new XMLMapperBuilder(in,config,"ProductMapper.xml",config.getSqlFragments()).parse();}
   try(var session=new SqlSessionFactoryBuilder().build(config).openSession(true)) {
    var repo=new MyBatisProductRepository(session.getMapper(ProductMapper.class));var cache=mock(ProductCache.class);when(cache.getProduct(any())).thenReturn(Optional.empty());when(cache.getListPage(anyInt(),anyInt())).thenReturn(Optional.empty());
    var service=new CatalogService(repo,cache,json);long detail=service.getById(id).priceMinor(),list=service.listActive(0,20).stream().filter(p->p.id().equals(id)).findFirst().orElseThrow().priceMinor(),batch=service.batchLookup(List.of(id)).getFirst().priceMinor();
    observed.put("detail_price",detail);observed.put("list_price",list);observed.put("batch_price",batch);long downstream=downstream(service);observed.put("basket_price",downstream);long updated=service.updatePrice(id,72000).priceMinor(),readBack=service.getById(id).priceMinor();observed.put("updated_price",updated);observed.put("read_back_price",readBack);save();
    assertEquals(65000,detail,"PRIVATE_SQL_PUBLIC_PRICE");assertEquals(65000,list,"LIST_PRICE");assertEquals(65000,batch,"BATCH_PRICE");assertEquals(65000,downstream,"DOWNSTREAM_PRICE");assertEquals(72000,updated,"UPDATE_RESPONSE");assertEquals(72000,readBack,"UPDATE_PERSISTED");
   }
  }
 }
 void cache(String phase) throws Exception {
  try(var redis=new GenericContainer<>(DockerImageName.parse("valkey/valkey:8")).withLabel("discovery.verification",System.getProperty("verification.run")).withImagePullPolicy(i->false).withExposedPorts(6379).withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(new com.github.dockerjava.api.model.PortBinding(com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1",0),new com.github.dockerjava.api.model.ExposedPort(6379))))) {
   redis.start();var connection=new LettuceConnectionFactory(redis.getHost(),redis.getMappedPort(6379));connection.afterPropertiesSet();connection.start();
   try {var valkey=new StringRedisTemplate(connection);var cache=new ProductCache(valkey,json,new SimpleMeterRegistry());var product=new Product(id,"Basmati Rice 5kg","Rice","/rice.png",65000,"INR",true,Instant.parse("2026-01-01T00:00:00Z"),Instant.parse("2026-01-01T00:00:00Z"));var repo=mock(ProductRepository.class);when(repo.findById(id)).thenReturn(Optional.of(product));var service=new CatalogService(repo,cache,json);
    long cold=service.getById(id).priceMinor();String raw=valkey.opsForValue().get("catalog:product:"+id);observed.put("raw_cache",json.readTree(raw));
    long warm=-1,legacy=-1,basket=-1;String error=null;
    try {warm=service.getById(id).priceMinor();basket=downstream(service);valkey.opsForValue().set("catalog:product:"+id,json.writeValueAsString(product));legacy=service.getById(id).priceMinor();}catch(RuntimeException ex){error=ex.getClass().getName()+": "+ex.getMessage();}
    observed.put("cold_price",cold);observed.put("warm_price",warm);observed.put("legacy_price",legacy);observed.put("basket_price",basket);observed.put("decode_error",error);save();
    assertEquals(65000,cold,"COLD_PRICE");assertEquals(phase.equals("baseline")?false:true,json.readTree(raw).has("format"),"REQUESTED_CACHE_FORMAT");assertEquals(65000,warm,"PRIVATE_CACHE_PUBLIC_PRICE");assertEquals(65000,basket,"DOWNSTREAM_PRICE");assertEquals(65000,legacy,"LEGACY_ENTRY_COMPATIBILITY");verify(repo,times(1)).findById(id);
   }finally{connection.destroy();}
  }
 }
 long downstream(CatalogService service) throws Exception {
  var mvc=MockMvcBuilders.standaloneSetup(new com.poc.catalog.api.ProductController(service)).build();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",exchange->{try{var response=mvc.perform(MockMvcRequestBuilders.get(exchange.getRequestURI().toString())).andReturn().getResponse();exchange.getResponseHeaders().set("Content-Type","application/json");byte[] body=response.getContentAsByteArray();exchange.sendResponseHeaders(response.getStatus(),body.length);exchange.getResponseBody().write(body);}catch(Exception e){byte[] body=e.toString().getBytes();exchange.sendResponseHeaders(500,body.length);exchange.getResponseBody().write(body);}finally{exchange.close();}});server.start();
  try{var properties=new com.poc.basket.infrastructure.catalog.CatalogClientProperties("http://127.0.0.1:"+server.getAddress().getPort(),Duration.ofSeconds(2),Duration.ofSeconds(2));var client=new com.poc.basket.infrastructure.catalog.CatalogClient(properties,io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults(),io.github.resilience4j.retry.RetryRegistry.ofDefaults());return client.lookup(id).priceMinor();}finally{server.stop(0);}
 }
}
