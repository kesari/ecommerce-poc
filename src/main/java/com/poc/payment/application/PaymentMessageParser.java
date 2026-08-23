package com.poc.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public final class PaymentMessageParser {
    private static final Set<String> TOKENS = Set.of("tok_success", "tok_declined", "tok_error");

    private final ObjectMapper objectMapper;

    public PaymentMessageParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedCharge parseCharge(String json) {
        EventEnvelope envelope = parseEnvelope(json, "payment.charge.requested");
        ChargeRequested command = convert(envelope.payload(), ChargeRequested.class);
        require(command.orderId() != null, "payload.orderId is required");
        require(command.amountMinor() >= 0, "payload.amountMinor must be non-negative");
        require("INR".equals(command.currency()), "payload.currency must be INR");
        require(TOKENS.contains(command.token()), "payload.token is not a supported mock selector");
        validateRouting(envelope, command.orderId());
        return new ParsedCharge(envelope, command);
    }

    public ParsedRefund parseRefund(String json) {
        EventEnvelope envelope = parseEnvelope(json, "payment.refund.requested");
        RefundRequested command = convert(envelope.payload(), RefundRequested.class);
        require(command.orderId() != null, "payload.orderId is required");
        require(command.paymentId() != null, "payload.paymentId is required");
        require(command.amountMinor() >= 0, "payload.amountMinor must be non-negative");
        validateRouting(envelope, command.orderId());
        return new ParsedRefund(envelope, command);
    }

    private EventEnvelope parseEnvelope(String json, String expectedType) {
        try {
            EventEnvelope envelope = objectMapper.readValue(json, EventEnvelope.class);
            require(envelope.eventId() != null, "eventId is required");
            require(expectedType.equals(envelope.eventType()), "unexpected eventType");
            require(envelope.schemaVersion() == 1, "unsupported schemaVersion");
            require(envelope.occurredAt() != null, "occurredAt is required");
            require(envelope.producer() != null && !envelope.producer().isBlank(), "producer is required");
            require(envelope.correlationId() != null, "correlationId is required");
            require(envelope.partitionKey() != null, "partitionKey is required");
            require(envelope.payload() != null && envelope.payload().isObject(), "payload must be an object");
            return envelope;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            if (exception instanceof InvalidMessageException invalid) {
                throw invalid;
            }
            throw new InvalidMessageException("message is not valid JSON for the frozen contract", exception);
        }
    }

    private <T> T convert(JsonNode payload, Class<T> type) {
        try {
            return objectMapper.treeToValue(payload, type);
        } catch (JsonProcessingException exception) {
            throw new InvalidMessageException("payload does not match the frozen contract", exception);
        }
    }

    private static void validateRouting(EventEnvelope envelope, java.util.UUID orderId) {
        require(orderId.equals(envelope.correlationId()), "correlationId must equal payload.orderId");
        require(orderId.equals(envelope.partitionKey()), "partitionKey must equal payload.orderId");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new InvalidMessageException(message);
        }
    }

    public record ParsedCharge(EventEnvelope envelope, ChargeRequested command) {
    }

    public record ParsedRefund(EventEnvelope envelope, RefundRequested command) {
    }
}
