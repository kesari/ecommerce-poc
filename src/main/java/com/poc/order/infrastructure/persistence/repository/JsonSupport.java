package com.poc.order.infrastructure.persistence.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.order.domain.model.AddressSnapshot;
import org.springframework.stereotype.Component;

@Component
public class JsonSupport {

    private final ObjectMapper objectMapper;

    public JsonSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(AddressSnapshot address) {
        try {
            return objectMapper.writeValueAsString(address);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize address snapshot", e);
        }
    }

    public AddressSnapshot readAddress(String json) {
        try {
            return objectMapper.readValue(json, AddressSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize address snapshot", e);
        }
    }
}
