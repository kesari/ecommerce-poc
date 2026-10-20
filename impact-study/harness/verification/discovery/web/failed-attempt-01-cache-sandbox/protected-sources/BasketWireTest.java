package com.poc.basket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.basket.api.dto.BasketResponse;
import com.poc.basket.api.dto.BasketItemResponse;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BasketWireTest {
 @Test void serializeActualResponse() throws Exception {
  var id=UUID.fromString("11111111-1111-1111-1111-111111111111");
  var body=new BasketResponse(id,7,null,List.of(new BasketItemResponse(id,"Bag",13000,"INR",1,13000)),13000,655,12345,"INR");
  var json=new ObjectMapper().writeValueAsString(body);
  assertTrue(json.contains("12345"));
  Files.writeString(Path.of(System.getenv("WIRE_OUTPUT")),json);
 }
}
